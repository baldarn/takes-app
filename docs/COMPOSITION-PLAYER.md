# Media3 CompositionPlayer for the editor preview

Research notes from 2026-11-15, for Media3 1.11.1. Based on reading source,
release notes, documentation and the issue tracker; nothing was run on a
device.

## Decision

Keep the two-deck ExoPlayer preview for now. Re-evaluate when Media3 1.12 is
stable.

## Why

1. With more than one video sequence, on the video graph that matches the
   exporter (`MultipleInputVideoGraph`), seeking is unsupported. Maintainers
   say so in issue #2439 and reports describe crashes or a stuck player. An
   editor preview is mostly paused seeking.
2. The newer pipeline where multi-sequence seeking works needs
   `media3-effect-ndk`, which is not published on Google Maven for 1.11.1 nor
   for 1.12.0-beta01.
3. `setComposition` releases and recreates one player per sequence on every
   call, so every edit re-initializes the decoders.

What is officially supported is one video sequence plus audio-only sequences.
That would cover music and voice-over, but not the cross dissolves the
two-deck preview already does.

## Facts worth keeping

- `androidx.media3.transformer.CompositionPlayer`, annotated `@ExperimentalApi`
  (an opt-in error, not a warning).
- Every `EditedMediaItem` must carry `setDurationUs` with the full source
  duration. The exporter does not set it today.
- Output must be a `SurfaceView`; `setVideoTextureView` throws.
- Gaps are supported: silence, and a black frame for video sequences.
- Open multi-video bugs: #2742, #2866 (overlapped sequences with gaps, which
  is the dissolve layout), #3533.
- The corruption we saw when an `OverlayEffect` shares a composition with two
  blended sequences appears unreported upstream. The same graph would be used
  for preview, so expect it there too. A possible workaround, untested: put
  titles in their own top sequence of transparent images instead of an
  overlay effect.
- Maintainers say `MultipleInputVideoGraph` will likely be deprecated.

## What to test first when revisiting

1. The dissolve composition (two video sequences with gaps), paused, seeking
   to many positions including inside the overlap and inside gaps.
2. The same with a composition-level overlay.
3. Time from `setComposition` to the first frame, for one and three sequences,
   on a mid-range phone.
4. Whether the positions an audio processor sees after a seek match timeline
   time.
