#!/usr/bin/env python3
"""Rewrites MediaPipe's selfie segmenter so that plain LiteRT runs it.

The model's last layer is MediaPipe's custom operator Convolution2DTransposeBias, which LiteRT
knows only inside MediaPipe. It is a transposed convolution followed by a bias, which LiteRT's
builtin TRANSPOSE_CONV does in one operator since its version 3. This swaps one for the other:
the same weights and bias (taken from the float16 constants the model dequantizes them from, as
float32 constants), the same padding and strides (from the custom options: padding, stride
width, stride height, as int32), and an int32 constant for the output shape. Nothing else in the
model changes.

    standard-selfie.py <schema_py_generated.py folder> <selfie_segmenter.tflite> <out.tflite>

schema_py_generated.py is LiteRT's flatbuffer schema with its object API; it ships in the
ai-edge-litert wheel (ai_edge_litert/schema_py_generated.py). Needs flatbuffers and numpy.
"""
import struct
import sys

import numpy as np

sys.path.insert(0, sys.argv[1])
import schema_py_generated as s  # noqa: E402

source, target = sys.argv[2], sys.argv[3]
model = s.ModelT.InitFromObj(s.Model.GetRootAsModel(open(source, "rb").read(), 0))
graph = model.subgraphs[0]
codes = model.operatorCodes


def code_of(op):
    return codes[op.opcodeIndex]


def constant(index):
    """The float32 values of a tensor that is a constant, or the output of DEQUANTIZE of one."""
    tensor = graph.tensors[index]
    data = model.buffers[tensor.buffer].data
    if data is not None and len(data):
        return np.frombuffer(bytes(data), dtype=np.float32)
    for op in graph.operators:
        if list(op.outputs) == [index] and code_of(op).builtinCode == s.BuiltinOperator.DEQUANTIZE:
            quantized = graph.tensors[op.inputs[0]]
            assert quantized.type == s.TensorType.FLOAT16, quantized.type
            return np.frombuffer(bytes(model.buffers[quantized.buffer].data), dtype=np.float16).astype(np.float32)
    raise ValueError(f"tensor {tensor.name} is not a constant")


def add_constant(name, values, kind, numpy_type):
    buffer = s.BufferT()
    buffer.data = np.frombuffer(np.asarray(values, dtype=numpy_type).tobytes(), dtype=np.uint8)
    model.buffers.append(buffer)
    tensor = s.TensorT()
    tensor.name = name
    tensor.shape = np.array(np.asarray(values).shape, dtype=np.int32)
    tensor.type = kind
    tensor.buffer = len(model.buffers) - 1
    graph.tensors.append(tensor)
    return len(graph.tensors) - 1


transpose = s.OperatorCodeT()
transpose.builtinCode = s.BuiltinOperator.TRANSPOSE_CONV
transpose.deprecatedBuiltinCode = s.BuiltinOperator.TRANSPOSE_CONV
transpose.version = 3  # the version that takes a bias
codes.append(transpose)

swapped = 0
for op in graph.operators:
    if code_of(op).customCode != b"Convolution2DTransposeBias":
        continue
    padding, stride_w, stride_h = struct.unpack("<3i", bytes(op.customOptions))
    data, weights, bias = op.inputs
    output = op.outputs[0]
    kernel = graph.tensors[weights]
    weight_values = constant(weights).reshape(kernel.shape)
    bias_values = constant(bias)
    shape = add_constant(graph.tensors[output].name + b"/output_shape", graph.tensors[output].shape, s.TensorType.INT32, np.int32)
    weight_tensor = add_constant(kernel.name + b"/float32", weight_values, s.TensorType.FLOAT32, np.float32)
    bias_tensor = add_constant(graph.tensors[bias].name + b"/float32", bias_values, s.TensorType.FLOAT32, np.float32)
    op.opcodeIndex = len(codes) - 1
    op.inputs = np.array([shape, weight_tensor, data, bias_tensor], dtype=np.int32)
    op.customOptions = None
    op.builtinOptionsType = s.BuiltinOptions.TransposeConvOptions
    options = s.TransposeConvOptionsT()
    # The custom options hold TfLitePadding (1 same, 2 valid); the schema's Padding is 0 same, 1 valid.
    options.padding = {1: s.Padding.SAME, 2: s.Padding.VALID}[padding]
    options.strideW = stride_w
    options.strideH = stride_h
    options.fusedActivationFunction = s.ActivationFunctionType.NONE
    op.builtinOptions = options
    swapped += 1

assert swapped == 1, swapped
# The DEQUANTIZE ops that fed only the swapped operator are left in place: they cost one tiny
# op each and removing them would renumber the graph.
builder = s.flatbuffers.Builder(1024) if hasattr(s, "flatbuffers") else __import__("flatbuffers").Builder(1024)
builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
open(target, "wb").write(builder.Output())
print(f"swapped {swapped} operator, wrote {target}")
