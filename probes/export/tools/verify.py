#!/usr/bin/env python3
"""Reads the movies the probe made, frame by frame and sample by sample, with ffmpeg.

    verify.py picture FILE --plan movie|two --fps F [--total US] [--first F --second F --cut US] [--offset FRAMES]
    verify.py sound FILE [--clips FOLDER] [--ref FILE] [--fps F] [--total US]
    verify.py meta FILE...

`picture` decodes every frame, reads the numbers the footage and the overlay carry (see
make-clips.sh and CounterOverlay) and compares them with what the plan of the probe puts at that
time. `sound` measures where each clip's sound lies in the movie against where the plan places it
(`--clips`), and compares the decoded sound with another movie's, sample by sample (`--ref`). Needs ffmpeg, ffprobe and numpy.
"""
import argparse
import json
import subprocess
import sys
from collections import Counter

import numpy as np

W, H = 640, 360
SECOND = 1_000_000


def probe(path, stream):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", stream, "-show_streams", "-show_packets", "-of", "json", path],
        capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def bits(strip, x0, width):
    """The 20 blocks of a row starting at x0, each `width` pixels wide, as 0 or 1."""
    return [1 if strip[:, int(x0 + b * width + width / 4):int(x0 + b * width + 3 * width / 4)].mean() > 110 else 0 for b in range(20)]


def footage(strip, x0):
    """(clip, frame) written on a half of the top rows, or None when there is no footage there."""
    b = bits(strip, x0, 16)
    if b[0] != 1 or b[1] != 0:
        return None
    return b[2] + 2 * b[3], sum(bit << i for i, bit in enumerate(b[4:]))


def counter(strip):
    b = bits(strip, 0, 32)
    if b[0] != 1 or b[1] != 0:
        return None
    return sum(bit << i for i, bit in enumerate(b[2:]))


def frames(path):
    """Every frame in presentation order: (left, right, overlay, mean brightness of the picture below the numbers)."""
    process = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", path, "-vf", f"scale={W}:{H}:flags=neighbor,format=gray", "-fps_mode", "passthrough", "-f", "rawvideo", "-"],
        stdout=subprocess.PIPE)
    result = []
    while True:
        data = process.stdout.read(W * H)
        if len(data) < W * H:
            break
        image = np.frombuffer(data, np.uint8).reshape(H, W)
        top = image[10:30]
        result.append((footage(top, 0), footage(top, 320), counter(image[H - 30:H - 10]), round(float(image[60:300].mean()), 1)))
    process.wait()
    return result


class Piece:
    def __init__(self, deck, fps, source, start, length):
        self.deck, self.fps, self.source, self.start, self.length = deck, fps, source, start, length
        self.end = start + length

    def expected(self, time):
        """The source frame showing at `time`: the last one that began by then."""
        return (self.source + time - self.start + 1) * self.fps // SECOND


def plan(args):
    if args.plan == "movie":
        total = args.total or 40 * SECOND + round(9e6 / args.fps)
        pieces = [Piece(0, args.fps, SECOND, 0, 13 * SECOND), Piece(1, args.fps, 2 * SECOND, 11 * SECOND, 19 * SECOND),
                  Piece(0, args.fps, 3 * SECOND, 29 * SECOND, total - 29 * SECOND)]
        # A movie cut short by --total ends some pieces early and leaves others out.
        return [Piece(p.deck, p.fps, p.source, p.start, min(p.end, total) - p.start) for p in pieces if p.start < total], total
    cut = args.cut
    return [Piece(0, args.first, SECOND, 0, cut), Piece(1, args.second, 2 * SECOND, 2 * SECOND, 7 * SECOND),
            Piece(0, args.first, 8 * SECOND, cut + 5 * SECOND, 3 * SECOND)], cut + 8 * SECOND


def picture(args):
    info = probe(args.file, "v:0")
    stream = info["streams"][0]
    num, den = (int(x) for x in stream["time_base"].split("/"))
    packets = info["packets"]
    times = sorted(int(p["pts"]) * SECOND * num // den for p in packets)
    order = [int(p["pts"]) for p in packets]
    shown = frames(args.file)
    pieces, total = plan(args)
    print(f"{args.file}: {len(packets)} packets, {len(shown)} decoded frames, key frames at packets {[i for i, p in enumerate(packets) if 'K' in p['flags']][:12]}")
    print(f"  stream: {stream['codec_name']} {stream.get('profile')} level {stream.get('level')} {stream['width']}x{stream['height']} "
          f"time base {stream['time_base']} r_frame_rate {stream['r_frame_rate']} avg {stream['avg_frame_rate']} nb_frames {stream.get('nb_frames')} "
          f"duration {stream.get('duration')} start {stream.get('start_time')} has_b_frames {stream.get('has_b_frames')} "
          f"color {stream.get('color_range')}/{stream.get('color_space')}/{stream.get('color_transfer')}/{stream.get('color_primaries')} "
          f"decode order = presentation order: {order == sorted(order)}")
    deltas = Counter(b - a for a, b in zip(times, times[1:]))
    print(f"  first time {times[0]} us, last {times[-1]} us; gaps between frames (us: count): {dict(sorted(deltas.items()))}")
    ticks = Counter(int(p.get('duration', 0)) for p in packets)
    print(f"  packet durations in time base units: {dict(sorted(ticks.items()))}")
    if len(shown) != len(times):
        print("  DECODED FRAME COUNT DIFFERS FROM PACKET COUNT")
    if args.fps and args.plan == "movie" or args.grid:
        fps = args.fps
        off = [k for k, t in enumerate(times) if abs(t - round((k) * 1e6 / fps)) > 1]
        print(f"  frames off the {fps} grid by more than 1 us: {len(off)}" + (f" first at {off[:5]}" if off else ""))
    # Stretches of the plan: which decks play.
    edges = sorted({0, total} | {p.start for p in pieces} | {p.end for p in pieces})
    wrong_overlay = []
    for a, b in zip(edges, edges[1:]):
        playing = [p for p in pieces if p.start <= a and p.end >= b]
        inside = [k for k, t in enumerate(times) if a <= t + 1 < b]
        line = f"  [{a / 1e6:.3f}, {b / 1e6:.3f}) s: {len(inside)} frames = {len(inside) * 1e6 / (b - a):.2f} per second"
        for p in playing:
            got = [(shown[k][p.deck], p.expected(times[k])) for k in inside if k < len(shown)]
            black = sum(1 for g, _ in got if g is None)
            numbers = [g[1] for g, _ in got if g is not None]
            late = Counter(g[1] - e for g, e in got if g is not None)
            repeats = sum(1 for x, y in zip(numbers, numbers[1:]) if x == y)
            first, last = p.expected(max(a, p.start)), p.expected(min(b, p.end) - 2)
            have = set(numbers)
            missing = [n for n in range(first, last + 1) if n not in have]
            line += (f"\n      deck {p.deck} ({p.fps} fps footage, frames {first}..{last}): shown {len(have)} of {last - first + 1}, "
                     f"missing {len(missing)}{(' ' + str(missing[:6])) if missing and len(missing) <= 6 else ''}"
                     f"{' (last frame of the stretch missing)' if last in missing else ''}, "
                     f"shown twice in a row {repeats}, black {black}, shown minus due {dict(sorted(late.items()))}")
        print(line)
    if args.plan == "movie" or args.grid:
        offset = args.offset
        wrong_overlay = [(k, s[2]) for k, s in enumerate(shown) if s[2] != k + offset]
        print(f"  overlay number differs from the frame's own number: {len(wrong_overlay)}" + (f" first {wrong_overlay[:5]}" if wrong_overlay else ""))
    if args.dump:
        for k, s in enumerate(shown):
            print(k, times[k] if k < len(times) else None, *s)
    else:
        print(f"  first frame {shown[0]}, last frame {shown[-1]}")


def pcm(path):
    data = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-vn", "-ac", "1", "-ar", "48000", "-f", "f32le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(data, np.float32).astype(np.float64)


def components(args, length):
    """The sounds the probe's movie mixes, each alone where the plan places it: (name, samples, seconds to look at)."""
    total = args.total or 40 * SECOND + round(9e6 / args.fps)
    end = total / 1e6

    def lay(name, source, start):
        samples = pcm(f"{args.clips}/{name}")
        out = np.zeros(length)
        first = round(start * 48000)
        piece = samples[round(source * 48000):][:length - first]
        out[first:first + len(piece)] = piece
        return out

    result = [("first clip", lay(f"L{args.fps}.mp4", 1.0, 0.0), (3, 6, 9)), ("second clip", lay(f"R{args.fps}.mp4", 2.0, 11.0), (15, 18, 22))]
    if end > 33:
        result.append(("third clip", lay(f"L{args.fps}.mp4", 3.0, 29.0), (32, 35, 38)))
    if not args.nomusic:
        result.append(("music", lay("music.m4a", 0.0, 0.0), (5, 13, 21)))
    return result


def lag(a, b, at, window=16384, reach=4000):
    """How many samples `a` is behind `b` around sample `at` (the shift at which they are most alike), what is
    left of a after b is taken away, in dB below a, and the gain of a over b."""
    lo = at - window // 2
    if lo - reach < 0 or lo + window + reach > min(len(a), len(b)):
        return None
    x = a[lo:lo + window]
    wide = b[lo - reach:lo + window + reach]
    products = np.correlate(wide, x, "valid")  # products[j] = x . b[lo - reach + j : ...]
    squares = np.concatenate(([0.0], np.cumsum(wide * wide)))
    energy = squares[window:] - squares[:-window]
    alike = products / np.sqrt(np.maximum(energy, 1e-12))
    j = int(np.argmax(alike))
    y = wide[j:j + window]
    gain = np.dot(x, y) / max(np.dot(y, y), 1e-12)
    rest = x - gain * y
    return reach - j, 10 * np.log10(max(np.dot(rest, rest), 1e-20) / max(np.dot(x, x), 1e-20)), gain


def sound(args):
    info = probe(args.file, "a:0")
    stream = info["streams"][0]
    packets = info["packets"]
    durations = Counter(int(p["duration"]) for p in packets)
    print(f"{args.file}: {stream['codec_name']} {stream['sample_rate']} Hz {stream['channels']} ch, {len(packets)} packets, time base {stream['time_base']}, "
          f"start {stream.get('start_time')}, duration {stream.get('duration')} ({stream.get('duration_ts')} units), first pts {packets[0]['pts']}, "
          f"packet durations {dict(durations)}")
    steps = Counter(int(b["pts"]) - int(a["pts"]) for a, b in zip(packets, packets[1:]))
    print(f"  steps between packet times: {dict(sorted(steps.items()))}")
    a = pcm(args.file)
    total = args.total or 40 * SECOND + round(9e6 / args.fps)
    print(f"  decoded {len(a)} samples = {len(a) / 48000:.6f} s; the movie is {total / 1e6:.6f} s = {total * 48 // 1000} samples")
    if args.clips:
        # Each sound alone against the movie: the others are in the way but do not move the best fit.
        for name, alone, seconds in components(args, len(a) + 48000):
            found = [lag(a, alone, second * 48000, window=65536, reach=6000) for second in seconds]
            print(f"  the {name} is heard {[f[0] for f in found if f]} samples after its place in the plan (at {list(seconds)} s)")
    if args.ref:
        b = pcm(args.ref)
        print(f"  against {args.ref}: {len(b)} samples")
        rows = []
        for second in np.arange(1.0, min(len(a), len(b)) / 48000 - 0.5, 0.25):
            found = lag(a, b, int(second * 48000))
            if found:
                rows.append((second, *found))
        lags = Counter(r[1] for r in rows)
        print(f"    lag in samples at {len(rows)} places a quarter second apart: {dict(sorted(lags.items()))}")
        worst = sorted(rows, key=lambda r: -r[2])[:4]
        print("    least alike (second, lag, remainder dB): " + ", ".join(f"({r[0]:.2f}, {r[1]}, {r[2]:.1f})" for r in worst))
        for join in (12.0, 24.0, 36.0):
            near = [r for r in rows if abs(r[0] - join) < 0.01]
            if near:
                print(f"    at {join:.0f} s: lag {near[0][1]}, remainder {near[0][2]:.1f} dB, gain {near[0][3]:.3f}")
        same = min(len(a), len(b))
        differ = np.nonzero(a[:same] != b[:same])[0]
        print(f"    samples that differ: {len(differ)} of {same}" + (f", the first at {differ[0] / 48000:.4f} s" if len(differ) else ""))
    # A click is a jump from one sample to the next far larger than the signal's own.
    jump = np.abs(np.diff(a))
    print(f"  largest jump between neighbouring samples: {jump.max():.4f} at {jump.argmax() / 48000:.4f} s; 99.99th percentile {np.percentile(jump, 99.99):.4f}")
    for join in (12.0, 24.0, 36.0):
        lo = int(join * 48000) - 2400
        if lo + 4800 < len(a):
            print(f"    within 50 ms of {join:.0f} s: largest jump {jump[lo:lo + 4800].max():.4f}, RMS {np.sqrt(np.mean(a[lo:lo + 4800] ** 2)):.4f}")


def meta(args):
    for path in args.files:
        for kind in ("v:0", "a:0"):
            info = probe(path, kind)
            for s in info["streams"]:
                keys = ("codec_name", "profile", "level", "width", "height", "pix_fmt", "color_range", "color_space", "color_transfer", "color_primaries",
                        "has_b_frames", "time_base", "r_frame_rate", "avg_frame_rate", "nb_frames", "start_time", "duration", "sample_rate", "channels", "bit_rate")
                print(path, kind, {k: s[k] for k in keys if k in s}, "rotation", [d.get("rotation") for d in s.get("side_data_list", [])])


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("picture")
    p.add_argument("file")
    p.add_argument("--plan", default="movie")
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--total", type=int)
    p.add_argument("--first", type=int, default=30)
    p.add_argument("--second", type=int, default=60)
    p.add_argument("--cut", type=int, default=3 * SECOND)
    p.add_argument("--offset", type=int, default=0)
    p.add_argument("--grid", action="store_true")
    p.add_argument("--dump", action="store_true")
    p.set_defaults(run=picture)
    s = sub.add_parser("sound")
    s.add_argument("file")
    s.add_argument("--ref")
    s.add_argument("--fps", type=int, default=30)
    s.add_argument("--total", type=int)
    s.add_argument("--nomusic", action="store_true")
    s.add_argument("--clips", help="the folder of make-clips.sh, to compare with the sound the plan describes")
    s.set_defaults(run=sound)
    m = sub.add_parser("meta")
    m.add_argument("files", nargs="+")
    m.set_defaults(run=meta)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    sys.exit(main())
