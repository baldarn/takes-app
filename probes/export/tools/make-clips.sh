#!/bin/sh
# Makes the probe's footage with ffmpeg: clips at 24, 25, 30 and 60 frames a second whose
# every frame carries its own number, so a frame of an exported movie can be traced back.
#
#   make-clips.sh <folder>
#
# A frame is 640 by 360. Its top 40 lines hold a row of 20 blocks, 16 pixels wide, on the
# left half (clips L*, played on the first deck) or the right half (clips R*, second deck):
# block 0 white, block 1 black, blocks 2-3 the clip's number, blocks 4-19 the frame's
# number, lowest bit first. The rest is a moving pattern, never black, so the black of an
# empty sequence cannot be taken for footage. The sound is a slow sweep, so a shift of a
# few samples shows when two movies are compared.
set -eu
out=${1:?folder}
mkdir -p "$out"

clip() { # name id x0 fps seconds sound
    b="floor((X-$3)/16)"
    bit="if(eq($b,0),1,if(eq($b,1),0,if(lt($b,4),mod(floor($2/pow(2,$b-2)),2),mod(floor(N/pow(2,$b-4)),2))))"
    lum="if(lt(Y,40)*gte(X,$3)*lt(X,$3+320),255*$bit,96+48*sin((X+4*N)/37)+32*sin(Y/23))"
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i "color=c=black:s=640x360:r=$4:d=$5,format=yuv420p,geq=lum='$lum':cb=128:cr=128" \
        -f lavfi -i "aevalsrc=$6:s=48000:d=$5" \
        -c:v libx264 -preset veryfast -crf 18 -bf 0 -g $(($4 * 2)) -pix_fmt yuv420p \
        -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv \
        -c:a aac -b:a 128k -ar 48000 -ac 2 -movflags +faststart "$out/$1.mp4"
}

for fps in 24 25 30 60; do
    clip "L$fps" 1 0 "$fps" 16 "0.15*sin(2*PI*(200+10*t)*t)+0.1*sin(2*PI*997*t)"
    clip "R$fps" 2 320 "$fps" 22 "0.15*sin(2*PI*(1500-12*t)*t)+0.1*sin(2*PI*311*t)"
done
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "aevalsrc=0.12*sin(2*PI*(600+5*t)*t):s=48000:d=46" \
    -c:a aac -b:a 128k -ar 48000 -ac 2 "$out/music.m4a"
ls -l "$out"
