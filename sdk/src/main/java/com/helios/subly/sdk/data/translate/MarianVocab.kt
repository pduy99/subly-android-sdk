package com.helios.subly.sdk.data.translate

import org.json.JSONObject
import java.io.File

/**
 * Marian / Opus-MT external vocabulary loader.
 *
 * The `.spm` files only carry SentencePiece's internal piece ordering, which
 * does NOT match the model's embedding row ordering. Marian models ship an
 * external `vocab.json` (`{piece: id}`) that maps SP piece strings to the
 * decoder/encoder embedding indices, plus special tokens like `</s>` (EOS,
 * id 0), `<unk>` (id 1) and target-language tags such as `>>vie<<`.
 */
internal class MarianVocab private constructor(
    private val pieceToId: Map<String, Int>,
    private val idToPiece: Array<String>,
) {

    val size: Int get() = idToPiece.size

    fun encode(piece: String): Int = pieceToId[piece] ?: UNK_ID

    fun decode(id: Int): String = idToPiece.getOrElse(id) { "" }

    fun contains(piece: String): Boolean = pieceToId.containsKey(piece)

    companion object {
        const val EOS_ID = 0
        const val UNK_ID = 1

        fun load(file: File): MarianVocab {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val keys = json.keys()
            // First pass: find the max id so we can size the reverse array.
            val entries = ArrayList<Pair<String, Int>>(json.length())
            while (keys.hasNext()) {
                val k = keys.next()
                entries.add(k to json.getInt(k))
            }
            val maxId = entries.maxOf { it.second }
            val reverse = Array(maxId + 1) { "" }
            val forward = HashMap<String, Int>(entries.size * 2)
            for ((piece, id) in entries) {
                forward[piece] = id
                reverse[id] = piece
            }
            return MarianVocab(forward, reverse)
        }
    }
}
