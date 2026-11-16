#!/bin/sh
# Pushes the models and a clip to the segmentation probe on the device named by $SERIAL, runs one
# scenario and prints its report (the probe must be installed: ./gradlew -p probes/segmentation :probe:installDebug):
#
#   SERIAL=<serial> run.sh <models folder> <clip> compare -e models modnet:cpu:1024,selfie:gpu -e frames 120
#
# The clip is pushed once, under its own name; the saved sheets are pulled to ./out-<serial>/.
set -eu
models=${1:?models folder}
clip=${2:?clip}
scenario=${3:?scenario}
shift 3
adb=${ADB:-adb}
serial=${SERIAL:?serial}
files=/data/user/0/app.takes.probe.segmentation/files
stage=/data/local/tmp/takes-segmentation
"$adb" -s "$serial" shell mkdir -p "$stage/models"
for model in "$models"/*; do "$adb" -s "$serial" push -q "$model" "$stage/models/" >/dev/null; done
case $clip in
    /sdcard/*) "$adb" -s "$serial" shell cp "$clip" "$stage/" ;;
    *) "$adb" -s "$serial" push -q "$clip" "$stage/" >/dev/null ;;
esac
name=$(basename "$clip")
"$adb" -s "$serial" shell chmod -R a+rX "$stage"
"$adb" -s "$serial" shell "run-as app.takes.probe.segmentation sh -c 'mkdir -p $files/in/models && cp $stage/models/* $files/in/models/ && cp $stage/$name $files/in/ && rm -f $files/out/*.png'"
"$adb" -s "$serial" shell am instrument -w -e scenario "$scenario" -e clip "$name" "$@" app.takes.probe.segmentation/.SegmentationProbe
mkdir -p "out-$serial"
for png in $("$adb" -s "$serial" shell run-as app.takes.probe.segmentation ls files/out | grep png || true); do
    "$adb" -s "$serial" exec-out run-as app.takes.probe.segmentation cat "files/out/$png" > "out-$serial/$png"
done
