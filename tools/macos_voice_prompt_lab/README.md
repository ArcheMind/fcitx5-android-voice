# macOS voice prompt lab

This is a command-line inference core for prompt experiments. It deliberately
does not capture audio or write to macOS text fields. It uses the MNN submodule
pinned by this repository and the same Qwen2.5-Omni input-model subset as the
Android voice feature.

## Build

From the repository root on macOS, configure and build the dedicated target:

```sh
cmake -S tools/macos_voice_prompt_lab -B build/macos_voice_prompt_lab -DCMAKE_BUILD_TYPE=Release
cmake --build build/macos_voice_prompt_lab --target macos_voice_prompt_lab
```

The CMake project enables MNN LLM Omni support, which also enables the audio
feature required for `<audio>` WAV prompts. It builds against
`third_party/MNN`; do not substitute another MNN checkout when comparing with
Android.

## Run an experiment

Copy `phone-baseline.example.json`, set `model_dir` to the downloaded Android
model directory, and replace the example case with labelled WAV files.

```sh
build/macos_voice_prompt_lab/macos_voice_prompt_lab \
  prompt-experiment.json results.jsonl
```

The optional third argument overrides `model_dir`. Paths in `audio` and
`model_dir` are relative to the experiment JSON file unless absolute.

The runner evaluates every prompt variant against every case. A case can carry
multiple segments; those segments run as one MNN chat session, matching the
Android multi-segment history rule.

Set the optional top-level `repetitions` to run each case in independent chat
sessions repeatedly. Every JSONL record includes its one-based `repetition`,
so output stability is measurable without mixing session history between
attempts.

Each JSON Lines result preserves the prompt/case IDs, one-based segment index, fully rendered messages,
WAV path, reference text, raw generation, normalized text, any MNN error,
wall-clock duration, and MNN audio/prefill/decode/TTFA timings. When `expected`
is set, `matches_expected` and `failure` provide an explicit verdict. A result
that differs only by added ASCII or common Chinese punctuation is classified as
`added-punctuation`; this is a failure because the reference transcript records
only spoken words. The runner logs the raw MNN request and response to stderr.

## Prompt experiment schema

`prompts` is the prompt matrix. Its templates accept `{hotwords}`, `{context}`,
`{language}`, and, in `audio_template`, `{audio}`. `system_prompt_template`
must render identically for every case in a prompt variant because MNN pre-fills
it once, the same optimization used by Android. Use separate prompt IDs for
different system prompts.

`cases` is the labelled WAV fixture set. `expected` is intentionally optional:
the tool can collect exploratory runs before reference labels exist. The two
example baseline variants exactly represent Android's English and Chinese
Context/Audio message labels; select only the applicable one when comparing a
single language.

## Synthetic first-pass fixtures

Run `generate_synthetic_fixtures.sh` to create deterministic 16 kHz WAV files
using macOS voices: Chinese and English question, statement, command, and
three-segment conversation utterances. Then run
`synthetic-prompt-experiment.json`, supplying the model directory as the third
argument. Its matrix preserves Android's two baseline label modes and compares
three short philosophies: identity, explicit boundaries, and sentence-boundary
contrast. It repeats each prompt/case pair three times.

## Multi-turn and punctuation coverage

`few-shot-prompt-experiment.json` also contains Chinese and English cases with
an existing Context message and three audio segments. The runner sends each
case as Android does: one System message, one Context user message, then an
Audio user message and normalized assistant message for every segment. Its
expected text has no punctuation, so generated punctuation is reported as the
distinct `added-punctuation` failure mode rather than being hidden by a
punctuation-insensitive comparison.
