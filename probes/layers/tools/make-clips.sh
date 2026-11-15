#!/bin/sh
# Makes the layers probe's footage with ffmpeg, 30 frames a second, a key frame every second
# (as a phone records) and no B-frames:
#
#   make-clips.sh <folder> [long]
#
# F1080-h264, F1080-hevc, F2160-h264, F2160-hevc: 20 s each. With "long", also L1080-h264:
# 10 minutes and 10 seconds, for the run that measures heat.
#
# A frame is ffmpeg's moving test pattern, never black. Its top twelfth is a row of 20 blocks
# across the width: block 0 white, blocks 1-3 black, blocks 4-19 the frame's number, lowest
# bit first (white is 1). The probe reads that row back from every layer of the composed
# frame. The number is also written large in the middle, for the eye. The sound is a tone.
set -eu
out=${1:?folder}
mkdir -p "$out"
font=$(fc-match -f '%{file}' sans)

clip() { # name width height seconds codec
    boxes="drawbox=x=0:y=0:w=iw:h=ih/12:color=black:t=fill,drawbox=x=0:y=0:w=iw/20:h=ih/12:color=white:t=fill"
    b=0
    while [ $b -lt 16 ]; do
        boxes="$boxes,drawbox=x=$((4 + b))*iw/20:y=0:w=iw/20:h=ih/12:color=white:t=fill:enable='eq(mod(floor(n/$((1 << b))),2),1)'"
        b=$((b + 1))
    done
    text="drawtext=fontfile=$font:text='%{n}':fontsize=h/5:fontcolor=white:box=1:boxcolor=black@0.6:x=(w-tw)/2:y=(h-th)/2"
    case $5 in
        h264) video="-c:v libx264 -preset veryfast -crf 20 -g 30 -bf 0" ;;
        hevc) video="-c:v libx265 -preset fast -crf 24 -tag:v hvc1 -x265-params keyint=30:min-keyint=30:bframes=0:log-level=error" ;;
    esac
    # shellcheck disable=SC2086
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i "testsrc2=s=$2x$3:r=30:d=$4,$boxes,$text,format=yuv420p" \
        -f lavfi -i "sine=frequency=440:sample_rate=48000:d=$4" \
        $video -pix_fmt yuv420p -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv \
        -c:a aac -b:a 128k -ac 2 -movflags +faststart "$out/$1.mp4"
}

clip F1080-h264 1920 1080 20 h264
clip F1080-hevc 1920 1080 20 hevc
clip F2160-h264 3840 2160 20 h264
clip F2160-hevc 3840 2160 20 hevc
[ "${2:-}" = long ] && clip L1080-h264 1920 1080 610 h264
ls -l "$out"
