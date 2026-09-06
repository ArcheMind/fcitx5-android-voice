# Acoustic voice test

Run from the repository on the Mac connected to V2429:

```sh
python3 tools/voice_acoustic_test.py --trials 2
```

Requires `adb`, Python 3, macOS `say` with Tingting, and `afplay`. Keep the phone near the LG display speakers. The runner uses the existing Mac output without changing routing or volume. The phone must be unlocked and unfolded at 2200×2480, with the Chinese UI, the debug voice keyboard selected, local recognition enabled, and its model installed. The fixed space-key position is specific to this verified layout.

The runner opens the keyboard settings' empty hotword editor, plays the same speech for each trial, checks committed text against the actual editor, and cancels the dialog afterward. Existing nonempty hotwords cause an abort. It never confirms or saves the editor. Do not interact with the phone during a run. No application rebuild or audio-injection modification is needed.

Each run prints a unique temporary artifact directory containing the generated audio, command inputs and raw outputs, app logcat, and `report.json`. Exit status is nonzero on a recognition mismatch, timeout, or cleanup failure. Content matching ignores punctuation and whitespace and treats `三点` and `3点` as equivalent; UI text must still exactly match the commit log.

`stop_to_commit_s` uses two device log timestamps. It is not an acoustic-end-to-visible-pixels measurement. Host playback times must not be subtracted from device timestamps. The runner does not force a cold start; the first trial may include model loading. Multiple segments are reported separately for request-to-response timing. A few trials do not establish p50/p95 performance.
