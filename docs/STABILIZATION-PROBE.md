# Stabilisation: measurements

Made on 2026-11-17 on an Android 13 and an Android 12 emulator (x86_64, SwiftShader, on
a host shared with other emulators), with synthetic shaky footage. No phone was used.

## The footage

A textured scene (2 500 grey discs on grey, slightly blurred, the kind of picture the
original's own estimator test draws) seen by a camera that pans at 40 pixels a second
across and 10 down, and shakes: sines at 3.7, 7.3, 2.9 and 6.1 Hz of 3 to 9 pixels, a
random walk, and a turn of up to 0.009 radians at 2.3 and 5.9 Hz. 1920 × 1080, 30 frames a
second, 8 seconds, H.264. A second file is the same stream marked to be turned a quarter
(`-display_rotation 90`), shown 1080 × 1920.

## How the shake is measured

Each movie is decoded by ffmpeg upright and scaled to 480 pixels wide; the translation
between consecutive frames is found by phase correlation with a sub-pixel peak, and the
camera path is their sum, in the movie's pixels. Shake is the RMS of the path's second
difference: a pan at constant speed has none. The turn is not measured.

## Results

`StabilizationRunner` (app/src/androidTest) imports the clip, measures it, makes each level's
plan and exports it at one level.

| Movie | Shake | RMS step | Mean step (x, y) |
|---|---|---|---|
| Shaky footage | 10.34 px | 8.95 px | 1.31, −0.34 |
| Exported at Standard | 0.12 px | 1.45 px | 1.17, −0.21 |
| Shaky footage, turned a quarter | 10.35 px | 8.96 px | −0.32, −1.35 |
| Exported at Strong, turned a quarter | 0.08 px | 1.69 px | −0.25, −1.59 |

The pan is kept (its speed slightly changed by the zoom of the crop) and the shake is gone.
The turned movie shows that the correction measured on the encoded frame is carried through
its turn.

All 240 frames tracked, one segment. The crop zoom of each level: Light 1.039×, Standard
1.051×, Strong 1.057× (none limited by the level's bound). Two pieces of a split use the one
path measured for the whole take: one cached file, the same crop.

Measuring 8 seconds of 1080p took 16.4 s on the emulator with the host otherwise quiet
(about 68 ms a frame, decoding included) and 31 to 89 s with the host loaded. The original's
own estimator test runs a 640 × 360 pair in 8 to 12 ms on the host computer. A phone's
time is not measured.

## The preview

The frame each deck's view shows is the one ExoPlayer last sent to it: its
`VideoFrameMetadataListener` gives the frame's source time (checked on the emulator: a clip
trimmed to start at 3.875 s sends 4.17, 4.33… the recording's own seconds). The playhead lags
that frame by up to half a second on the emulator, so the correction is chosen by the frame
sent, and by the playhead only for a held frame or a frame from another clip. Whether the
view shows that frame at the same moment its correction is drawn is not measured: on a
busy device the correction may be a frame off now and then.
