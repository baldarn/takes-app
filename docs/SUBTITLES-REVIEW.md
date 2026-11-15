# Subtitles: review against the original iOS app

Reviewed: this repository at tag `v0.6` (commit `43db294`) against the original iOS
app at its commit `faf1815`. An independent reading of the source on both sides; the
README and `docs/PARITY.md` were used only as claims to check.

Paths: `O/` is `YouRec/` in the original repository, `A/` is
`app/src/main/kotlin/app/takes/`, `C/` is `core/src/main/kotlin/app/takes/core/`,
`S/` is `speech/src/main/kotlin/app/takes/speech/`.

How each statement was established is marked:

- **[read]** verified by reading both sides;
- **[probe]** verified by running code (see "Probes" near the end);
- **[inferred]** follows from the source but was not observed.

One correction to the premise of the review request: the original does **not** use
Whisper. It runs Qwen3-ASR-0.6B through MLX (`O/SubtitleTranscriber.swift:2,42`,
`O/SubtitleModelPack.swift:7-8`). At the time of the review only the port used Whisper.

**Update, 2026-11-15.** Findings 1, 9 and 14 and verdict rows 2a and 2b describe v0.6.
Since then the port runs the original's model, Qwen3-ASR 0.6B, through sherpa-onnx, with
Whisper small as the lighter choice, and "Auto" no longer lets the first piece decide.
What changed is written under each of those findings, marked **Now**; the rest of the
review is as it was. File and line references of the unchanged text are those of v0.6.

## Verdict

| Area | Verdict | In one line |
|---|---|---|
| 1. What is transcribed | **differs** | Original: the edited mix over the selected range. v0.6: each clip's own recording, whole project, as in 0.5. Nothing changed here between 0.5 and 0.6. |
| 2a. Model and decoding | **matches** where the phone has the memory (was: differs) | v0.6: Qwen3-ASR-0.6B 4-bit, 713 MB, against Whisper base int8, 161 MB. Now: Qwen3-ASR 0.6B on both, another conversion (int8 ONNX, 987 MB); Whisper small on phones with less memory. |
| 2b. Download, verification, storage, removal | **matches**, small differences | Pinned revision, SHA-256 per file, resume, cancel, outside backups, removable on both. Offline behaviour and space reserve differ. |
| 2c. Chunking of long audio | **matches** | Same plan, identical numbers [probe]. It is fed different audio (see 1). |
| 2d. Text post-processing | **differs** slightly | Original: none. Port: removes bracketed notes. Neither filters invented phrases. |
| 3a. Word timing, grouping, text correction | **matches** | Same formulas, identical to 1e-9 on the cases tried [probe]. Tokenizer is a different library. |
| 3b. Cues through edits (split, trim, move, speed, delete, undo, paste) | **matches** | Words in source seconds, owned by value, cut at the edges of what plays. |
| 3c. Re-transcription | **differs** | Original replaces the selected range and keeps the rest; port replaces everything. |
| 3d. Pause and resume of a transcription | **differs** | Original pauses on backgrounding and resumes from a checkpoint; port has neither. |
| 4. Editing UI | **differs** | Same actions and nearly the same words; no own transport, no "Following playback", no drag and pinch, no timeline badge. |
| 5a. Layout maths, defaults, ranges, draw order, stacking | **matches** | Reference canvas, wrapping width, box, limits, two stacked captions, subtitles over titles [probe for the maths]. |
| 5b. Appearance settings | **differs** | Original edits a full title design and motion; port has four styles, six colours, five fonts. |
| 5c. Fonts, line breaks, pixel result | **not verifiable from source** | Different fonts and text engines; needs both devices. |
| 5d. Preview and export agree | **matches** on each side | One drawing path for both, on both platforms. Pixel equality on Android not verifiable here. |
| 6. Export or sharing of subtitles | **matches** | Neither has SRT, VTT or any file export. Both copy the transcript to the clipboard. |

## Findings, most visible first

Severity: **every use** (seen each time the tool is used), **specific cases**,
**subtle**, **internal**.

### 1. A different recognizer writes the text — every use — [read]

- Original: Qwen3-ASR-0.6B, aufklarer's 4-bit MLX conversion at a pinned revision,
  five files, 712,705,305 bytes, shown as "713 MB"
  (`O/SubtitleModelPack.swift:7-16`, `O/SubtitleEditor.swift:117,240-241`). Loaded per
  job and released after it (`O/SubtitleTranscriber.swift:41-43`). Decoding: greedy
  (temperature 0), repetition penalty off, at most 256 tokens per piece, language hint
  or none, no context prompt (`O/SubtitleTranscriber.swift:70-71`,
  `ThirdParty/QwenASR/Sources/YouRecQwen/Qwen3ASR/Qwen3ASR.swift:21-40`). The library's
  no-repeat rule only starts above 15 s of audio (`Qwen3ASR.swift:87-96`) and pieces
  are at most 12 s, so it never applies.
- Port: multilingual Whisper base, 8-bit weights, sherpa-onnx's export at a pinned
  revision, three files, 160,609,290 bytes, shown as "161 MB"
  (`S/SpeechModel.kt:98-105,127`, `A/SubtitleModel.kt:29-32`). sherpa-onnx 2.0.1
  (`speech/build.gradle.kts:14`). Decoding: greedy, task "transcribe", 3 s of tail
  padding, no prompt, up to four threads (`S/SpeechRecognizer.kt:53,77-89,162`).
  Whisper small (375 MB) is described in the code but not offered
  (`S/SpeechModel.kt:108-115`).
- Difference: the words themselves, the punctuation and the casing come from a
  different model, a smaller one. Accuracy cannot be compared from source.
- Fix: a decision, not a bug. If accuracy on a phone is not good enough, offer Whisper
  small as a second download (already described in `SpeechModel`), or follow
  `docs/MODELS-ON-ANDROID.md` and try Qwen through sherpa-onnx.
- **Now**: done. The port runs Qwen3-ASR 0.6B, the sherpa-onnx project's int8 export
  at a pinned revision, six files, 987,015,347 bytes, shown as "987 MB"
  (`S/SpeechModel.kt`, `Qwen3Asr`); its tokenizer files are byte for byte the
  original's. sherpa-onnx is still 2.0.1, which already had the recognizer. Decoding as
  the original: greedy, at most 256 tokens per piece, "language <name>" after the sound
  when a language is chosen and nothing when it is Auto (`S/SpeechRecognizer.kt`).
  Whisper small (375 MB) is the second model on offer and the one a phone with under
  7 GB of memory gets by itself; Whisper base is used only where an earlier version
  downloaded it. A model is a description (`SpeechModel`: id, name, folder, kind of
  recognizer, files with size and SHA-256, memory it takes), the choice between them is
  in `S/SpeechModels.kt`, and the tool shows it as one line, "Model: … · n MB".
  Measured on a phone sold with 8 GB, on 88.5 s of Italian speech with English
  technical words, pieces of at most 12 s: Qwen3-ASR loads in 5 to 13 s, recognizes in
  45 to 55 s (0.5 to 0.6 of real time) and takes 2.1 GB; Whisper small loads in 3 s, recognizes
  in 57 s and takes 0.8 GB. By reading, with the language set to Italian, Qwen3-ASR
  wrote about 9 clearly wrong words out of 190 and Whisper small about 18; Qwen3-ASR
  got "porting dell'app", "computer", "ram" and "input" right, Whisper small wrote
  "compiuto" and "ramba". There was no reference text, so these counts are a reading,
  not a score.

### 2. Sound on the audio tracks is not transcribed — specific cases — [read]

- Original: reads every audio track of the preview item through its audio mix
  (`O/SubtitleTranscriber.swift:88-96`, called with `item.asset` and `item.audioMix`
  at `O/SubtitleEditor.swift:403`), so a voice-over or any added sound is heard. Each
  word is then attached to the loudest unmuted audio item playing at its middle
  (`O/Subtitles.swift:219-238`), and captions follow that item when it moves
  (`Tests/SubtitleTests.swift:46-49`).
- Port: only clips are listened to and only clips own words
  (`C/SubtitleEditing.kt:12-14,59-76,84-95`, `A/SubtitleTranscriber.kt:37-39,80`).
  `AudioItem` has no words (`C/Project.kt:219-228`).
- Difference: a movie narrated with an added voice-over gets no captions for the
  narration. If the clips are silent the result is "No speech was detected".
- Fix: give `AudioItem` `subtitleWords`, add the items to `stretches` and `install`,
  and place their cues from `item.start`. Level, mute and overlap rules can follow
  `O/Subtitles.swift:221-224`.

### 3. Always the whole project; re-transcribing replaces every caption — specific cases — [read]

- Original: the tool opens on the marked selection or, with none, the whole project
  (`O/EditorView.swift:1011-1019`), shows the scope (`O/SubtitleEditor.swift:101`),
  transcribes that range (`:234`), removes only words whose middle lies in it and
  regroups only the new words (`O/Subtitles.swift:211-217,241-246`;
  `Tests/SubtitleTests.swift:52-55`). The prompt says "Replace subtitles in this
  range?" (`O/SubtitleEditor.swift:174`). The cue lists show the range only
  (`:54,351`), with "No captions in this range." (`:111`). When the footage changed,
  a "Re-transcribe whole project" button appears (`:252`).
- Port: `transcribe` takes all clips and `install` rebuilds the words of every clip
  (`A/SubtitlesPanel.kt:219,224`, `C/SubtitleEditing.kt:84-95`). Prompt: "Replace the
  subtitles? Corrected text will be replaced." (`strings.xml` `subtitles_replace_title`).
- Difference: fixing one badly heard sentence by re-transcribing it loses every
  correction made elsewhere, and costs the time of the whole movie.
- Fix: add a range to `SubtitleTranscriber.transcribe` and `SubtitleEditing.install`
  (keep words whose timeline middle is outside it; group only the new ones). The
  editor already has a marked range for other tools to pass in.

### 4. Appearance is the old four styles, not the title designer — every use of Appearance — [read]

- Original: "Appearance" holds the title inspector bound to the series' design and
  motion: looks, font (imported fonts included), fill and gradient, outline, shadow,
  background plate, entrance and exit motion with a replay
  (`O/SubtitleEditor.swift:279-294`). The old style, colour and font are only the
  starting point (`O/Subtitles.swift:43`, `O/TitleDesign.swift:86-97`). A styled series
  is laid out and painted by the styled text renderer
  (`O/SubtitleRenderer.swift:134-138,160`) and animated per caption (`:75-83,114`).
- Port: chips for Style (Plain, Bold, Box, Highlight), Color (six), Font (five)
  (`A/SubtitlesPanel.kt:336-349`). `appearance` and `motion` are kept as raw JSON and
  never drawn (`C/Subtitles.kt:37-39,55-56`, `A/SubtitleRendering.kt:34-35`).
- Difference: no outline, gradient, custom font or motion for captions. A project
  styled on an iPhone and opened here shows its captions in the old style behind the
  design, and `valid` does not check the two fields.
- Fix: the port already draws styled titles (`A/TitleRendering.kt`, `C/TitleDesign.kt`).
  Type `appearance` as `TitleDesign` and `motion` as `TitleMotion`, draw through the
  title painter when set, and reuse `TitlePanel`'s controls.

### 5. Captions cannot be dragged or pinched on the picture — every use of placement — [read]

- Original: drag moves the series, pinch resizes it around the fingers, a toggle
  switches to inspecting the picture, an outline shows the largest caption of the
  project, and accessibility actions move and resize
  (`O/SubtitleEditor.swift:84-89,108,183-200`, `O/EditorView.swift:1413-1422`). Sliders
  exist as well (`O/SubtitleEditor.swift:295-302`).
- Port: sliders for size, horizontal and vertical, and a reset
  (`A/SubtitlesPanel.kt:350-359`). The preview canvas has no caption gesture
  (`A/EditorScreen.kt:780-784`).
- Difference: placement is by slider only; no envelope guide.
- Fix: a `pointerInput` on the preview while the tool is open, calling
  `SubtitlePlacement.constrained` as the sliders do; draw `largest` as the guide.

### 6. No pause and resume; nothing protects a job in the background — specific cases — [read]

- Original: on going to the background a running job is cancelled keeping a
  checkpoint; the tool shows "Transcription paused. Resume here to continue from the
  last completed section." and the button becomes "Resume transcription"
  (`O/SubtitleEditor.swift:150-157,233-235`). A checkpoint is saved after every piece
  and reused only if range, language and plan are unchanged
  (`O/SubtitleTranscriber.swift:4-10,45-50,78`). A download interrupted the same way
  says "Download interrupted. Tap Download to continue." Applying with a paused job
  asks first (`O/SubtitleEditor.swift:159-162`).
- Port: one coroutine in the panel's scope (`A/SubtitlesPanel.kt:186-199`); the screen
  is kept on (`A/EditorScreen.kt:507-511`); no lifecycle handling, no checkpoint, no
  foreground service.
- Difference: leaving the app during a long transcription either keeps working
  unprotected or loses everything done if the process is stopped. [inferred for what
  the system does]
- Fix: keep the words of finished pieces and the plan in the panel (as
  `TranscriptionCheckpoint`), stop on `ON_STOP`, and offer "Resume transcription".

### 7. The tool is a panel under the editor, not its own editor — every use — [read]

Order of the original's controls (`O/SubtitleEditor.swift:72-118`): Cancel / title /
Apply; own preview with play, seek slider and time, limited to the range; scope title;
language and Transcribe (or download note and button); Copy full transcript; failure;
Max words stepper (and stale note); "Drag subtitles to move · pinch to resize";
Appearance; **Following playback** (the five captions around the playhead, "Pause in
speech" between captions); Remove all subtitles; "All subtitles · N" (collapsible,
open); privacy note; Remove downloaded model.

Order in the port (`A/SubtitlesPanel.kt:253-404`): Cancel / title / Apply; language
and Transcribe (or download note and button); failure; Copy full transcript; Max words
with − and +; stale note; Appearance; "All subtitles · N" with a hint; the rows;
Remove all subtitles; privacy note; Remove downloaded model.

Differences:

- No "Following playback" block (`O/SubtitleEditor.swift:306-315`), where one tap
  edits. The port scrolls the full list to the current caption while playing
  (`A/SubtitlesPanel.kt:246-248`).
- Editing is a dialog with Save, Delete and Cancel (`A/SubtitlesPanel.kt:407-430`);
  the original edits in place and the header buttons become "Cancel edit" and "Save
  text" (`O/SubtitleEditor.swift:73-75,322-325`). Delete is the port's addition; in
  the original an emptied text removes the caption (`O/Subtitles.swift:120`), which
  the port also does (`C/SubtitleEditing.kt:129`).
- "Remove all subtitles" comes after the list in the port and before it in the
  original (`O/SubtitleEditor.swift:112-114`), where it stays reachable above a long
  transcript.
- The selected row is deselected when the playhead leaves that caption
  (`O/SubtitleEditor.swift:143-147`); in the port it stays selected, so a later single
  tap on it opens the editor (`A/SubtitlesPanel.kt:376`).
- Row time: `m:ss.cc` rounded to a 30th of a second (`O/EditorTimeline.swift:1052-1055`)
  against `m:ss.t` (`A/SubtitlesPanel.kt:108-111`). The port seeks 10 ms into the
  caption (`:378`), the original to its start (`O/SubtitleEditor.swift:329`).

Fix: add the five-row block above the list (one tap edits), move "Remove all" above
the list, clear `selected` when the playhead leaves the caption.

### 8. Speed, level, fades and noise reduction are not heard — specific cases — [read], plan numbers [probe]

Original: the mix as played, 16 kHz mono, with pitch-preserved speed changes
(`O/SubtitleTranscriber.swift:85-95`). Port: each recording as made, "at its natural
speed, without the clip's level, fades or noise reduction"
(`A/SubtitleTranscriber.kt:37-39`).

| Aspect | Original | Port v0.6 | Visible effect |
|---|---|---|---|
| Speed | Recognizer hears the sped or slowed sound; times are estimated in played seconds and mapped back to source (`O/Subtitles.swift:231-233`) | Hears natural speed; times in source seconds, shown through `clip.rate` (`C/SubtitleEditing.kt:26`, `C/Subtitles.kt:288-294`) | Text at 2–4× is likely better in the port. The speech plan differs: the same sound gave 7 pieces at natural speed and 3 at double speed [probe], so pauses shorter than 180 ms played are not anchors in the original. |
| Clip level | In the mix: a clip turned far down can fall under the gate (floor capped at −52 dB, `O/SubtitleSpeech.swift:16`) | Ignored | A clip at very low level is captioned in the port, not in the original. |
| Mute | Silent in the mix; cues of muted items hidden (`O/Subtitles.swift:158`) | Skipped and hidden (`C/SubtitleEditing.kt:17,25,63`) | Same result. |
| Fades | In the mix | Ignored | Words under a fade-out may be captioned in the port only. |
| Noise reduction | Whatever the preview's audio mix carries (not traced further) [inferred] | Raw recording | Recognition on noisy takes may differ. |
| Transitions | One mix; a word goes to one item | Each clip's recording separately | See finding 12. |
| Unlinked sound | Words live on the audio item and follow it | `AudioUnlinking` mutes the clip (`C/AudioUnlinking.kt:9-13`), which hides its captions and drops its words at the next transcription (`C/SubtitleEditing.kt:25,87`) | Not reachable in v0.6 (no call from `app/`), but wrong the day unlinking gets a button. |
| Range read | Only the range (`O/SubtitleTranscriber.swift:91`) | Every used recording is decoded whole (`A/SubtitleTranscriber.kt:84-90,154`), then only the used stretches are planned (`:92-104`) | Longer "Reading the sound…" when a short part of a long take is used. |

Fix: transcribing the mix needs the export's audio graph rendered to 16 kHz; until
then, decode only `stretches` (seek the extractor) and, when unlinking ships, let the
item with the clip's id own the words.

### 9. With "Auto", the first piece decides the language of the whole job — specific cases — [read]

- Original: each piece is decoded with no language hint (`O/SubtitleTranscriber.swift:71`).
- Port: the language detected in the first piece is forced on all later ones
  (`A/SubtitleTranscriber.kt:130`, comment at `:72-75`).
- Difference: a movie that starts in one language and continues in another is
  written in the first throughout. If the first piece is noise or music, a wrong
  detection applies to everything. [inferred]
- Fix: detect on the longest of the first few pieces, or show the detected language
  so the person can correct it.
- **Now**: the port does what the original does. With Auto every piece goes to the
  model without a hint (`A/SubtitleTranscriber.kt`, `transcribe`), with Qwen3-ASR and
  with Whisper alike. The price is the original's too: on the test speech, Italian
  throughout, Qwen3-ASR wrote one short piece in Spanish ("Sí, este es el porting del
  app") when left on Auto and wrote it in Italian when told the language. Whisper small
  wrote the same text with Auto and with Italian.

### 10. No protection against Whisper's invented phrases — specific cases — [inferred]

- Original: the model's text is used as it is (`O/SubtitleTranscriber.swift:69-77`).
- Port: removes notes in square brackets and a text that is one round-bracket note
  (`C/SubtitleSpeechAudio.kt:55-58`), and skips pieces under −70 dB
  (`S/SpeechRecognizer.kt:126,165`).
- Difference: music or noise above the energy gate is still handed to Whisper, which
  is known to write stock phrases for it ("Thanks for watching", in Italian
  "Sottotitoli creati dalla comunità Amara.org"). Neither side filters repeated or
  stock phrases; whether Qwen needs it less cannot be told from source.
- Fix: drop a piece's text when it equals a short list of known phrases per language,
  or when one phrase repeats three times.

### 11. No "Subtitles" badge on the timeline — every use — [read]

- Original: one region from the first caption to the last, labelled "Subtitles",
  opens the tool on the whole project and can remove all captions
  (`O/VideoTools.swift:458,493,570-571`).
- Port: the tool button is tinted when the project has subtitles
  (`A/EditorScreen.kt:983-985`); nothing on the timeline.
- Both add the first caption's start to the navigation stops
  (`O/TimelineNavigation.swift:133-153`, `C/TimelineNavigation.kt:97,225`).
- Fix: draw a bar like the titles' bar over the cue span, opening the tool.

### 12. Two captions can show during a dissolve where the original shows one — specific cases — [inferred]

- Original: both clips are in one mix; a word is attached to a single item
  (`O/Subtitles.swift:202-204,225-237`).
- Port: each clip's recording is transcribed to its edges, so both clips have words
  inside the overlap; `active` then shows two stacked (`C/SubtitleEditing.kt:33-34`).
- Fix: in `cues`, end a clip's captions at the boundary of its transition
  (`TransitionLayout.Placement.lower`/`upper`).

### 13. Fonts are the nearest Android faces — every use of Font — [read]; result not verifiable

- Original: system, Avenir Next, Georgia, Menlo, DIN Condensed, then a bold trait on
  top for every style but plain (`O/Subtitles.swift:18-26`,
  `O/SubtitleRenderer.swift:139-141`).
- Port: sans-serif, sans-serif-medium or -light, serif, monospace,
  sans-serif-condensed (`A/SubtitleRendering.kt:96-102`).
- Difference: "Modern" is medium where the original is demi-bold made bold, and light
  where the original is regular. Widths differ, so lines break at other words than on
  an iPhone, and `largest` and the size limit differ with them.
- Fix: ship open fonts with close metrics, or accept and say so in the README.

### 14. Download: waiting, space and interruption — specific cases — [read]

| | Original | Port |
|---|---|---|
| Source | Hugging Face, pinned revision (`O/SubtitleModelPack.swift:58`) | Hugging Face, pinned revision, HTTPS only, client name "Takes" (`S/SpeechModel.kt:101`, `A/SubtitleModel.kt:85,91`) |
| Verification | SHA-256 of each file; installed = marker + sizes (`:20-30,63`) | Same (`S/SpeechModelFetcher.kt:127-131`, `S/SpeechModel.kt:38-40`) |
| Resume | URLSession resume data per file (`:50-56,68`) | `.part` file and a `Range` request (`S/SpeechModelFetcher.kt:80-87`, `A/SubtitleModel.kt:94`) |
| Storage | Application Support, excluded from backup (`:17,35`) | `noBackupFilesDir/speech/base`; app backup is off altogether (`A/SubtitleModel.kt:34`, manifest `allowBackup="false"`) |
| Offline | Waits for a connection (`:42`), showing "Downloading speech model…" at 0 | Fails at once: "The download did not finish (…). Tap Download to continue." (`A/SubtitlesPanel.kt:209-211`) |
| Space | Model + 150 MB, message says 900 MB (`:36-37`) | Model + 20 MB, message says 181 MB (`A/SubtitleModel.kt:39-44,72`, `A/SubtitlesPanel.kt:176`) |
| Progress | Bar only | "Downloading speech model… n of 161 MB" |
| Remove | Asks; keeps subtitles (`O/SubtitleEditor.swift:167-173`) | Same (`A/SubtitlesPanel.kt:443-452`) |

**Now**: the table holds for each of the two models, with their own folders
(`noBackupFilesDir/speech/qwen3-asr-0.6b`, `…/small`; `…/base` for a Whisper base left
by an earlier version) and sizes (987 MB, 375 MB). Qwen3-ASR comes from
`huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` at revision
`68818b2313fe77bd06f6a7c5068ff3ef59d02b8a`, the sherpa-onnx project's second account,
file by file, so nothing is unpacked on the phone. An interrupted download shows
"Continue download · n MB left", and the choice of model shows how much of each is on
the phone, with a bin to remove it. Seen on a phone against the real server: the
download of both models, a stop in the middle of a file, and the continuation with a
`Range` request answered with 206.

The port's 20 MB reserve is thin: the scratch file of a later transcription is
230 MB per hour of recording (`speech/README.md`), and nothing checks space for it.
Fix: check free space before "Reading the sound…".

### 15. Cancel waits for the piece in hand — subtle — [read]

Original: cancellation is checked before every decoder step
(`O/SubtitleTranscriber.swift:70,73`; `Qwen3ASR.swift:279-306`). Port: between pieces
only; a piece of up to 12 s cannot be interrupted (`A/SubtitleTranscriber.kt:74,124`,
`S/SpeechRecognizer.kt:38-40`). Both show "Stopping…". Fix: none short of sherpa-onnx
support; pieces are short.

### 16. Word splitting is another library — subtle — [read]; iPhone output not verifiable

`NLTokenizer` (`O/Subtitles.swift:64-75`) against ICU `BreakIterator`
(`C/Subtitles.kt:124-141`). The rule around it is the same: punctuation between two
words stays with the first, so an opening mark ends the previous word ("tal? ¡",
"Bien!") [probe]. Word weights count code points, not user-perceived characters
(`C/Subtitles.kt:318-321` against `O/Subtitles.swift:79`). Chinese and Japanese are
split by different dictionaries. Effect: a different word count per caption in edge
cases.

### 17. Smaller internal differences — internal — [read]

- Regrouping guard: the original hashes every audio and video item with its playback
  map (`O/Subtitles.swift:183-189`); the port hashes id, source, start, range, rate
  and mute of each clip (`C/SubtitleEditing.kt:44-48`). Audio items are left out,
  consistently with finding 2.
- The original caps words at 200,000 per item (`O/TrackTimeline.swift:1193`); the
  port has no cap (`C/Project.kt:483-485`). Text length is counted in characters
  there and UTF-16 units here.
- Colours are truncated, not rounded, to 8 bits (`A/SubtitleRendering.kt:69`): at most
  one level off.
- The busy guard "A transcription is already running."
  (`O/SubtitleTranscriber.swift:28`) is a disabled button in the port.
- Progress: 5% reading, 8% loading (`O/SubtitleTranscriber.swift:34,38,68`) against
  10% reading (`A/SubtitleTranscriber.kt:233`); no "Subtitles ready" or "Audio changed.
  Restarting transcription…" in the port.
- The port also carries code for real word times from Whisper's attention
  (`S/SpeechRecognizer.kt:216-308`); the shipped model has no such output
  (`S/SpeechModel.kt:16-20`), so times are always estimated, as the footer says.

## What matches, in detail

### Timing — [read] and [probe]

| Rule | Original | Port |
|---|---|---|
| Loudness: RMS of 320 samples at 16 kHz (20 ms) | `O/SubtitleTranscriber.swift:113` | `A/SubtitleTranscriber.kt:60,172-178` |
| Gate: 20th percentile + 9 dB, kept within −52…−35 dB | `O/SubtitleSpeech.swift:14-16` | `C/SubtitleSpeech.kt:26-29` |
| Island: at least 3 loud slices, ends after 9 quiet, widened 80 ms before and 100 ms after | `:18-27` | `:36-52` |
| Long speech cut at the quietest 100 ms between 8 and 12 s | `:31-45` | `:56-74` |
| Islands joined when under 0.7 s apart (1.3 s after a piece under 3 s), up to 12 s | `:46-54` | `:78-89` |
| No overlap between pieces; words of pieces are appended in order | `O/SubtitleTranscriber.swift:76-77` | `A/SubtitleTranscriber.kt:133-137` |
| Word weight: letters ^ 0.65 | `O/Subtitles.swift:79` | `C/Subtitles.kt:320-321` |
| A word goes to the island holding its weighted middle, then shares that island by weight | `:84-105` | `:150-181` |
| New caption at max words, after a pause over 0.32 s, or after `.!?。！？` | `:106-113` | `:187-201` |
| Max words 1–16, default 6 | `:31,46,193` | `:44,86` |
| Shown caption breaks where words of one phrase are over 0.4 s apart, and is cut at the edges of what plays | `:160-177` | `:278-304` |
| Retyping: longest common subsequence, 128-word limit, 0.04 s and 45% borrowing | `:117-151` | `:211-266` |
| Text limit 4000 | `O/SubtitleEditor.swift:346` | `C/Subtitles.kt:28`, `C/SubtitleEditing.kt:123` |

There is no maximum of characters, lines or duration per caption and no minimum gap
on either side: only words, pauses and sentence ends.

### Cues through edits — [read]

Words are in source seconds and belong by value to what plays them
(`O/TrackTimeline.swift:184`; `C/Project.kt:159`). Split, trim, move, reorder and
delete carry them, hidden words return when a trim is undone, speed scales them,
and pause removal cuts captions at the kept parts (the original through its playback
runs, `O/Subtitles.swift:160-163`; the port because the kept parts are clips). Paste
gives fresh ids and takes the settings only if the project had none, with an empty
fingerprint (`O/TrackTimeline.swift:1563,1579`, `O/TimelinePaste.swift:105`;
`C/RangeEditing.kt:541-542,671,694`). Everything done in the tool is one Undo step
(`O/EditorView.swift:385-387`; `A/EditorScreen.kt:948`). Regrouping is refused once
the structure changed and allowed again after Undo
(`O/Subtitles.swift:190-196`; `C/SubtitleEditing.kt:51-52,106-113`); the port's tests
cover these (`core/src/test/.../SubtitleEditingTest.kt`, 29 tests, all passing).

### Drawing — [read], maths [probe]

| | Original (`O/SubtitleRenderer.swift`) | Port |
|---|---|---|
| Canvas with a 1080 short side | `:22-23` | `C/SubtitleEditing.kt:176-179` |
| Font size 0.052 of the short side at reference | `:9,132` | `:166`, `A/SubtitleRendering.kt:37` |
| Wrap at 84% of the width less padding; padding 0.35 em; centred | `:142-155` | `A/SubtitleRendering.kt:38,48-51` |
| Box = text + 2 × padding + 2 | `:156` | `:53` |
| Largest box over the series, two captions together, 12 units apart | `:32-41` | `C/SubtitleEditing.kt:186-199` |
| Size limit: 0.94 of the frame, at most 0.30; smallest 0.012 | `:14,51` | `:140-142` |
| Position kept 0.025 inside the frame | `:54-63` | `:144-150` |
| Stack centred on y, at most two | `:98-116` | `:157-163`, `:33-34` |
| Box plate: grey 0.02 at 82%, corner 0.7 × padding; highlight plate in the colour with near-black text | `:167-171` | `A/SubtitleRendering.kt:74-83` |
| Plain and bold: shadow 0.04 em down, 0.12 em blur, black at 90% | `:170` | `:81` |
| Line breaks fixed when resizing (layout once, then scaled) | `:5-7` | `A/SubtitleRendering.kt:26-30` |
| Subtitles drawn last, over titles and transitions | `O/VideoToolCompositor.swift:236` | `A/Exporter.kt:280-286`, `A/EditorScreen.kt:782-783` |
| Preview and export share one path | `:177-202`, `O/TrackRenderer.swift:145,162` | `A/SubtitleRendering.kt:106-112` |
| Defaults: box, white, 0.052, y 0.86, centred, system font | `O/Subtitles.swift:29-45` | `C/Subtitles.kt:42-56` |

The colours are the same six with the same values (`O/VideoTools.swift:160-168`;
`C/TitleDesign.kt:20-27`). There is no uppercase option, no per-word highlight, no
maximum number of lines, no alignment or margin setting for the old styles on either
side. Alpha: the original draws premultiplied and composites in Core Image
(`:162-164,84-91`); the port hands Media3 a bitmap with unmultiplied colours since
0.6 (`A/SubtitleRendering.kt:133-155`), which removed a darkening of the plate and
shadow in exports.

### Strings — [read]

The original is English only. The port's English follows it closely; all 12 language
names and their order match (`O/SubtitleEditor.swift:47`; `A/SubtitlesPanel.kt:88-91`).

| Original | Port (en) |
|---|---|
| "Download Qwen speech recognition once, then use it offline. About 713 MB." | "Download Whisper speech recognition once, then use it offline. About 161 MB." |
| "Reading selected audio…" | "Reading the sound…" |
| "This range has no audio." | "These clips have no sound to transcribe." |
| "No speech was detected in this range. Try a range with audible speech." | "No speech was detected. Try clips with audible speech." |
| "Speech stays on your iPhone. Word timing is estimated from pauses." | "Speech stays on your phone. Word timing is estimated from pauses." |
| "Replace subtitles in this range? Corrected text in the range will be replaced. You can Undo after applying." | "Replace the subtitles? Corrected text will be replaced. You can Undo after applying." |
| "No captions in this range." | "No subtitles in this movie." |
| "Following playback", "Pause in speech", "Drag subtitles to move · pinch to resize", "Re-transcribe whole project", "Resume transcription", "Cancel edit", "Save text" | absent (features absent) |
| — | "Tap a subtitle to go to it, tap it again to edit it.", Delete in the edit dialog, megabyte counter while downloading |

Identical: Transcribe, Re-transcribe, "Download speech model · N MB", "Loading speech
model…", "Transcribing section i of n", "about … left", "estimating time…",
"Stopping…", the no-words and verification messages, "Max words per subtitle: n",
the stale note, Copy full transcript / Transcript copied, Appearance, Size, Position,
"Reset position and size", "All subtitles · n", "Remove all subtitles" and its
prompt, "Discard subtitle work?" with its two buttons, and the three model-removal
texts. The Italian strings say the same as the English ones; nothing is missing in
`values-it`. Neither side has search, merge, split, retime or add for captions.

### Export of subtitles — [read]

The original has no SRT, VTT or other subtitle file: no such extension or writer
anywhere in its sources. Its only way out is "Copy full transcript"
(`O/SubtitleEditor.swift:256-274`), which the port has
(`A/SubtitlesPanel.kt:309-318`, `C/SubtitleEditing.kt:37`). Captions are otherwise
burned into the exported picture on both sides.

## Probes

Run on a desktop JVM (JDK 17), never committed.

1. The port's subtitle tests: `SubtitlesTest` 28, `SubtitleEditingTest` 29,
   `SubtitleSpeechTest` 9, `SubtitleSpeechAudioTest` 5, all passing at `v0.6`.
2. The original's `estimate`, `group`, `corrected`, `chunks` and the size and position
   limits were rewritten line by line in a script and fed the same inputs as the
   port's functions: the sentence and islands of `Tests/SubtitleTests.swift:8-18`, its
   three corrections, 100 s of continuous sound, 40 s of silence, a 51 s pattern of
   speech and pauses, and four caption boxes on 1920×1080, 1080×1920, 1080×1080 and
   3840×2160. Every number agreed to 1e-9: 12 words, the same caption boundaries at
   6 and at 2 words, 12 pieces of at most 12 s for continuous sound, none for
   silence, 7 pieces for the pattern, and the same size limits and clamped positions.
   Limit of this probe: the script is a transliteration, not the Swift itself, and
   splits words at spaces in place of `NLTokenizer`.
3. The same 51 s pattern at double speed (every second loudness value) gave 3 pieces
   in place of 7: the figure behind the "Speed" row of finding 8.
4. The port's tokenizer on a desktop JVM: `Don't`, `e-mail`, `3.5`, `o'clock!`,
   `L'acqua`, `U.S.A.` stay whole; an opening mark stays with the word before it.

## Not determined without a device or the model

- How well each model recognizes speech beyond the one Italian sample of finding 1;
  how often Whisper invents text on music or noise, and whether Qwen3-ASR does.
- Time and memory of a transcription on an iPhone, and on an Android phone with less
  than 8 GB of memory: whether Qwen3-ASR runs at all on one sold with 6 GB.
- What `NLTokenizer` returns for apostrophes, hyphens, numbers and unspaced scripts,
  so whether word counts per caption agree exactly.
- Line breaks, line height and the look of each font: Core Text against
  `StaticLayout`, and the shadow's softness.
- Whether the Android preview and export agree to the pixel (same code, different
  target sizes; the 0.6 alpha change was not rendered here).
- What Android does to a transcription or a download when the app is left.
- Whether the original's preview mix includes its audio enhancement.
- The download against the real server when it refuses a request or the network is
  metered (resume and redirects were seen, finding 14).
- Reading a project written by the other platform: schema numbers differ and were not
  compared here.
