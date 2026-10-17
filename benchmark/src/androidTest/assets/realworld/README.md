# Real-world clips

Unscripted audio, unlike the FLEURS read speech in `../dataset/`.

| Clip | Language | Length | Content |
|---|---|---|---|
| `zh_dialogue_01.mp4` | Mandarin (mainland) | 48 s | Fast conversational dialogue between several speakers, with names, slang (硬通货) and background sound. |

`<clip>.transcript.txt` is the reference: the clip's own subtitles, one line
per subtitle, supplied by the project owner. Two edits to them: one subtitle
that repeated four earlier lines verbatim was dropped, and 咔嚢 was corrected
to 咔嚓. There is no human reference translation; translations are judged
against the transcript.

Used by `latency/RealWorldAsrProbeTest` (recogniser comparison) and the
latency harness (`latencyClips=zh_dialogue_01`).
