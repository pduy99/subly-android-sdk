package com.helios.subly.sdk.data.translate

import java.io.File
import java.text.Normalizer

/**
 * Pure-Kotlin SentencePiece Unigram tokenizer.
 *
 * Loads a binary `.spm` (SentencePiece ModelProto) file and exposes
 * `encode` / `decode` for Marian Opus-MT models. Avoids any native JNI so it
 * is Android-compatible (DJL's SentencePiece library only ships Linux/macOS
 * native libs).
 *
 * Implements:
 *  - Minimal hand-rolled protobuf reader over `pieces { piece, score }` only.
 *  - NFKC normalisation + whitespace-to-`▁` replacement per SentencePiece spec.
 *  - Viterbi best-segmentation over Unigram log-scores.
 *
 * Limitations vs. upstream SentencePiece:
 *  - No byte-fallback (BYTE-type pieces are emitted as UNK).
 *  - No custom normalisation rules beyond NFKC.
 *
 * These are acceptable for Marian Opus-MT inference where the source / target
 * vocabulary already covers the relevant character set.
 */
internal class SentencePieceProcessor private constructor(
    private val pieces: List<String>,
    private val scores: FloatArray,
    private val pieceToId: HashMap<String, Int>,
    private val unkId: Int,
    private val maxPieceLen: Int,
) {

    val vocabSize: Int get() = pieces.size

    fun idToPiece(id: Int): String = pieces.getOrElse(id) { "" }

    fun pieceToId(piece: String): Int = pieceToId[piece] ?: unkId

    /**
     * Tokenise [text] into Unigram piece strings (no ids). For Marian /
     * Opus-MT use the pieces as keys into the model's external `vocab.json`
     * - the SP-internal id ordering does NOT match the model's embedding
     * row ordering.
     */
    fun encodeAsPieces(text: String): List<String> {
        val ids = encode(text)
        return ids.map { pieces.getOrElse(it) { "" } }
    }

    /**
     * Detokenise raw SentencePiece pieces (in any id space) back to text.
     */
    fun decodePieces(piecesIn: List<String>): String {
        val sb = StringBuilder(piecesIn.size * 4)
        for (p in piecesIn) sb.append(p)
        return sb.toString().replace(SPACE_MARKER, ' ').trim()
    }

    /**
     * Tokenise [text] into Unigram ids. Returns ids only (no BOS/EOS); caller
     * decides whether to append the Marian-style EOS (id 0).
     */
    fun encode(text: String): IntArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return IntArray(0)

        val n = normalized.length
        val bestScore = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val bestEnd = IntArray(n + 1) { -1 }
        val bestId = IntArray(n + 1) { -1 }
        bestScore[0] = 0.0

        for (start in 0 until n) {
            if (bestScore[start] == Double.NEGATIVE_INFINITY) continue
            val limit = minOf(start + maxPieceLen, n)
            for (end in start + 1..limit) {
                val piece = normalized.substring(start, end)
                val id = pieceToId[piece] ?: continue
                val candidate = bestScore[start] + scores[id]
                if (candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    bestEnd[end] = start
                    bestId[end] = id
                }
            }
            // Single-char fallback to UNK so unknown characters never trap
            // the Viterbi pass.
            val unkEnd = start + 1
            val unkCandidate = bestScore[start] + UNK_SCORE_PENALTY
            if (unkCandidate > bestScore[unkEnd]) {
                bestScore[unkEnd] = unkCandidate
                bestEnd[unkEnd] = start
                bestId[unkEnd] = unkId
            }
        }

        val out = ArrayDeque<Int>()
        var cursor = n
        while (cursor > 0) {
            val id = bestId[cursor]
            if (id < 0) break
            out.addFirst(id)
            cursor = bestEnd[cursor]
        }
        return IntArray(out.size) { out[it] }
    }

    /**
     * Convert Unigram ids back to a readable string. Replaces SentencePiece's
     * `▁` (U+2581) with a regular space, strips the leading space introduced
     * by normalisation.
     */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder(ids.size * 4)
        for (id in ids) {
            val piece = pieces.getOrNull(id) ?: continue
            sb.append(piece)
        }
        return sb.toString().replace(SPACE_MARKER, ' ').trim()
    }

    private fun normalize(text: String): String {
        // SentencePiece prefixes a leading space and replaces every space
        // with `▁` so word boundaries become part of the token alphabet.
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val builder = StringBuilder(nfkc.length + 1)
        builder.append(SPACE_MARKER)
        for (ch in nfkc) {
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                if (builder.last() != SPACE_MARKER) builder.append(SPACE_MARKER)
            } else {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    companion object {
        private const val SPACE_MARKER = '\u2581' // ▁
        // Penalty applied when falling back to UNK on a single character so
        // Viterbi never strictly prefers UNK over a real piece.
        private const val UNK_SCORE_PENALTY = -10.0

        fun load(spmFile: File): SentencePieceProcessor {
            val bytes = spmFile.readBytes()
            val pieces = mutableListOf<String>()
            val scoresList = mutableListOf<Float>()
            val types = mutableListOf<Int>()

            // ModelProto: pieces are field 1 (length-delimited submessage).
            val reader = ProtoReader(bytes)
            while (reader.hasMore()) {
                val tag = reader.readVarint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x7L).toInt()
                if (field == 1 && wire == 2) {
                    val sub = reader.readLengthDelimited()
                    parsePiece(sub, pieces, scoresList, types)
                } else {
                    reader.skip(wire)
                }
            }

            require(pieces.isNotEmpty()) { "SentencePiece model contains no pieces" }

            val pieceToId = HashMap<String, Int>(pieces.size * 2)
            var unk = 0
            var maxLen = 0
            for (i in pieces.indices) {
                pieceToId[pieces[i]] = i
                if (types[i] == TYPE_UNKNOWN) unk = i
                if (pieces[i].length > maxLen) maxLen = pieces[i].length
            }

            return SentencePieceProcessor(
                pieces = pieces,
                scores = FloatArray(scoresList.size) { scoresList[it] },
                pieceToId = pieceToId,
                unkId = unk,
                maxPieceLen = maxLen.coerceAtLeast(1),
            )
        }

        private const val TYPE_UNKNOWN = 2

        private fun parsePiece(
            data: ByteArray,
            pieces: MutableList<String>,
            scores: MutableList<Float>,
            types: MutableList<Int>,
        ) {
            val reader = ProtoReader(data)
            var piece = ""
            var score = 0f
            var type = 1 // NORMAL
            while (reader.hasMore()) {
                val tag = reader.readVarint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x7L).toInt()
                when {
                    field == 1 && wire == 2 -> piece = String(reader.readLengthDelimited(), Charsets.UTF_8)
                    field == 2 && wire == 5 -> score = reader.readFloat()
                    field == 3 && wire == 0 -> type = reader.readVarint().toInt()
                    else -> reader.skip(wire)
                }
            }
            pieces.add(piece)
            scores.add(score)
            types.add(type)
        }
    }

    /** Minimal protobuf wire-format reader (varint / fixed32 / length-delimited only). */
    private class ProtoReader(private val data: ByteArray) {
        private var pos = 0
        fun hasMore(): Boolean = pos < data.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[pos].toInt() and 0xff
                pos++
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readLengthDelimited(): ByteArray {
            val len = readVarint().toInt()
            val out = ByteArray(len)
            System.arraycopy(data, pos, out, 0, len)
            pos += len
            return out
        }

        fun readFloat(): Float {
            val bits = ((data[pos].toInt() and 0xff)) or
                    ((data[pos + 1].toInt() and 0xff) shl 8) or
                    ((data[pos + 2].toInt() and 0xff) shl 16) or
                    ((data[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return Float.fromBits(bits)
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> { val len = readVarint().toInt(); pos += len }
                5 -> pos += 4
                else -> error("Unsupported wire type: $wire")
            }
        }
    }
}
