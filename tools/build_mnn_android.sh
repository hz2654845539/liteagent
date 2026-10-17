#!/bin/bash
# 编译 MNN for Android arm64-v8a（Windows + Git Bash 版本）
#
# 为什么不用官方 project/android/build_64.sh：
#   官方脚本用 unix `make`，Windows 上没有；这里改用 Ninja，其余参数保持一致。
# 与原脚本的差异（每一项都有理由，不是随手改）：
#   -DMNN_BUILD_DIFFUSION=OFF  不做图像生成，省编译时间和体积
#   -DMNN_BUILD_OPENCV=OFF     同上
#   -DMNN_IMGCODECS=OFF        同上
#   -DMNN_BUILD_TEST=OFF       不编测试
#   -DMNN_BUILD_BENCHMARK=OFF  不编 benchmark 可执行文件
# 必须保留：
#   -DMNN_LOW_MEMORY=ON              手机端降峰值内存
#   -DMNN_OPENCL=ON                  GPU 加速（MNN 的 GPU 走 OpenCL，不是 Vulkan）
#   -DMNN_SUPPORT_TRANSFORMER_FUSE=ON LLM 依赖的算子融合
#   -DMNN_SEP_BUILD=OFF              产出单个 libMNN.so
#
# 用法：bash tools/build_mnn_android.sh
set -euo pipefail

SDK=${ANDROID_SDK_ROOT:-D:/as/SDK}
NDK=${ANDROID_NDK:-$SDK/ndk/28.0.12433566}
CMAKE_BIN=${CMAKE_BIN:-$SDK/cmake/3.30.5/bin/cmake.exe}
NINJA_DIR=${NINJA_DIR:-$SDK/cmake/3.30.5/bin}
NINJA_BIN=${NINJA_BIN:-$NINJA_DIR/ninja.exe}
MNN_ROOT=${MNN_ROOT:-$(cd "$(dirname "$0")/../.refs/MNN" && pwd)}
BUILD_DIR="$MNN_ROOT/project/android/build_64"

[ -d "$NDK" ] || { echo "NDK 不存在: $NDK"; exit 1; }
[ -f "$CMAKE_BIN" ] || { echo "cmake 不存在: $CMAKE_BIN"; exit 1; }
[ -f "$NINJA_BIN" ] || { echo "ninja 不存在: $NINJA_BIN"; exit 1; }
[ -d "$MNN_ROOT" ] || { echo "MNN 源码不存在: $MNN_ROOT"; exit 1; }

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

echo "==================== configure ===================="
"$CMAKE_BIN" -G Ninja "$MNN_ROOT" \
  -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_STL=c++_static \
  -DANDROID_NATIVE_API_LEVEL=android-21 \
  -DMNN_USE_LOGCAT=false \
  -DMNN_BUILD_FOR_ANDROID_COMMAND=true \
  -DMNN_LOW_MEMORY=ON \
  -DMNN_OPENCL=ON \
  -DMNN_SEP_BUILD=OFF \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
  -DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON \
  -DMNN_BUILD_LLM=ON \
  -DMNN_LLM_BUILD_DEMO=OFF \
  -DLLM_SUPPORT_HTTP_RESOURCE=OFF \
  -DLLM_LOG_TO_STRING=ON \
  -DMNN_BUILD_DIFFUSION=OFF \
  -DMNN_BUILD_OPENCV=OFF \
  -DMNN_IMGCODECS=OFF \
  -DMNN_BUILD_TEST=OFF \
  -DMNN_BUILD_BENCHMARK=OFF \
  -DNATIVE_LIBRARY_OUTPUT=. \
  -DNATIVE_INCLUDE_OUTPUT=.

echo "==================== build ===================="
"$CMAKE_BIN" --build . -j "${JOBS:-4}"

echo "==================== artifacts ===================="
ls -lh "$BUILD_DIR"/lib*.so 2>/dev/null || echo "未找到 .so，检查编译输出"
