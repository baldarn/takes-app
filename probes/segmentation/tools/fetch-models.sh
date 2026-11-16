#!/bin/sh
# Downloads the models the segmentation probe compares into <folder>, checked against the
# SHA-256 recorded on 2026-11-16 (docs/SEGMENTATION-PROBE.md says where each comes from and its licence):
#
#   fetch-models.sh <folder>
set -eu
out=${1:?folder}
mkdir -p "$out"
fetch() { # name url sha256
    if [ ! -f "$out/$1" ]; then
        curl -fL --retry 3 -o "$out/$1.part" "$2"
        mv "$out/$1.part" "$out/$1"
    fi
    echo "$3  $out/$1" | sha256sum -c -
}
fetch selfie_segmenter.tflite https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/1/selfie_segmenter.tflite 191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b
fetch selfie_multiclass_256x256.tflite https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0
fetch modnet.onnx https://huggingface.co/Xenova/modnet/resolve/fa2fa546052fba4c08921230a26cc69a333fca12/onnx/model.onnx 07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9
fetch modnet_quantized.onnx https://huggingface.co/Xenova/modnet/resolve/fa2fa546052fba4c08921230a26cc69a333fca12/onnx/model_quantized.onnx 92e49898c3e05a6d7a944fc67a8cb87c4aad754ffb6ebd949528c7d1105fee3a
# The selfie segmenter with LiteRT's builtin transposed convolution in place of MediaPipe's custom
# one (tools/standard-selfie.py, which needs LiteRT's Python schema: see the script).
if [ -n "${SCHEMA:-}" ]; then
    python3 "$(dirname "$0")/standard-selfie.py" "$SCHEMA" "$out/selfie_segmenter.tflite" "$out/selfie_standard.tflite"
fi
