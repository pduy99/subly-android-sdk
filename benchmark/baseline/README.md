# No valid baseline

The previous baseline was voided rather than superseded, so this directory is
deliberately empty of scores until the first FLEURS run is judged and committed.

Two independent reasons, either sufficient on its own:

1. **Truncated audio.** The harness computed "playback finished" as elapsed time
   from `start()`, but `FileAudioCapture` decodes the whole mp4 inside `frames()`
   before pacing begins, so the clock started roughly 25 s before the audio did.
   Every clip in that baseline is missing its tail — 268 characters on one 65 s
   clip, a third of a minute of speech. Fixed in `2d09918`.

2. **The corpus was replaced.** LJ Speech, AISHELL-1 and Common Voice gave way to
   Google FLEURS, whose clips are parallel across languages and whose reference
   translations are human FLoRes work rather than machine output. Scores are only
   ever comparable within one corpus, so the old numbers cannot be carried over.

Do not compare anything against the numbers in this directory's git history.
Regenerate with a full matrix run, judge it, and commit `report.md` +
`scores.json` here — see `benchmark/README.md`.
