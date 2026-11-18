# ONNX Runtime C headers, 1.28.2

`onnxruntime_c_api.h`, `onnxruntime_ep_c_api.h` and `onnxruntime_error_code.h` from
`include/onnxruntime/core/session/` of ONNX Runtime v1.28.2 (commit
33ca9628233dc8f002435e868d4c2e9f82766ca1), unchanged, under the MIT licence in `LICENSE`.
They match the libonnxruntime.so 1.28.2 that sherpa-onnx brings (speech/), which
onnx_session.cpp opens at run time; nothing of ONNX Runtime is compiled from here.

SHA-256:

    b69a133143c0da61782b2b02cc8a620d21ac139231fd211719776d10385135c1  onnxruntime_c_api.h
    db86df0f846b8d3bdbf429a5c76c5805293196575ca3c475878612591cd18e51  onnxruntime_ep_c_api.h
    5ce3b054e798eced8d14f5b86e98692fd33470463f96194ce0700a2d53dd8721  onnxruntime_error_code.h

They were downloaded by the build until github.com/microsoft/onnxruntime stopped answering
(HTTP 404 on 2026-11-18) and are kept here so that a clean checkout builds.
