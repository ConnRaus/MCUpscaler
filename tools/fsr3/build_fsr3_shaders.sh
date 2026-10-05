#!/bin/zsh
# Regenerates src/native/mac/fsr3_shaders.h: AMD FidelityFX FSR 3.1.4 upscaler (MIT licence) GLSL passes -> SPIR-V -> MSL.
# Needs glslang + spirv-cross (brew install glslang spirv-cross) and a FidelityFX SDK v1.1.4 checkout:
#   git clone --depth 1 --branch v1.1.4 https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK.git
# Usage: build_fsr3_shaders.sh <path to FidelityFX-SDK v1.1.4>
set -e
SDK=${1:?usage: build_fsr3_shaders.sh <FidelityFX-SDK v1.1.4>}
HERE=${0:A:h}
WORK=$(mktemp -d)
trap 'rm -rf $WORK' EXIT
cp -R $SDK/sdk/include/FidelityFX/gpu $WORK/gpu
# Metal has 16 sampler slots: move the two samplers from bindings 1000/1001 to 14/15.
sed -i '' 's/binding = 1000)/binding = 14)/; s/binding = 1001)/binding = 15)/' $WORK/gpu/fsr3upscaler/ffx_fsr3upscaler_callbacks_glsl.h
# Sharpness above 1 (up to 1.5 = 2x RCAS strength): clamp the scaled lobe so 4 * lobe + 1 stays positive.
perl -0pi -e 's/(FfxFloat32 lobe\s*=\s*)(max\(FfxFloat32\(-FSR_RCAS_LIMIT\).*?\(con\.x\))/$1max(FfxFloat32(-0.24), $2)/s' $WORK/gpu/fsr1/ffx_fsr1.h
SRC=$SDK/sdk/src/backends/vk/shaders/fsr3upscaler
# Permutation: LDR input, render-resolution motion vectors without jitter, inverted (reversed-Z) depth.
DEFS=(-DFFX_GPU=1 -DFFX_GLSL=1 -DFFX_FSR3UPSCALER_OPTION_HDR_COLOR_INPUT=0
      -DFFX_FSR3UPSCALER_OPTION_LOW_RESOLUTION_MOTION_VECTORS=1 -DFFX_FSR3UPSCALER_OPTION_JITTERED_MOTION_VECTORS=0
      -DFFX_FSR3UPSCALER_OPTION_INVERTED_DEPTH=1)
pass() { # name glsl half extra-defines...
  local name=$1 file=$2 half=$3; shift 3
  glslangValidator -V --target-env vulkan1.2 -S comp -I$WORK/gpu $DEFS -DFFX_HALF=$half "$@" $SRC/$file -o $WORK/$name.spv > $WORK/$name.log
  spirv-cross $WORK/$name.spv --msl --msl-version 30100 --msl-decoration-binding --rename-entry-point main fsr_$name comp --output $WORK/$name.metal
}
# FP16 everywhere except RCAS and the two SPD pyramids (their FP16 variants are stubs), as in the SDK.
pass prepare_inputs ffx_fsr3upscaler_prepare_inputs_pass.glsl 1
pass luma_pyramid ffx_fsr3upscaler_luma_pyramid_pass.glsl 0
pass shading_change_pyramid ffx_fsr3upscaler_shading_change_pyramid_pass.glsl 0
pass shading_change ffx_fsr3upscaler_shading_change_pass.glsl 1
pass prepare_reactivity ffx_fsr3upscaler_prepare_reactivity_pass.glsl 1
pass luma_instability ffx_fsr3upscaler_luma_instability_pass.glsl 1
pass accumulate ffx_fsr3upscaler_accumulate_pass.glsl 1
pass accumulate_sharpen ffx_fsr3upscaler_accumulate_pass.glsl 1 -DFFX_FSR3UPSCALER_OPTION_APPLY_SHARPENING=1
pass rcas ffx_fsr3upscaler_rcas_pass.glsl 0
python3 $HERE/gen_header.py $WORK $HERE/../../src/native/mac/fsr3_shaders.h \
  prepare_inputs luma_pyramid shading_change_pyramid shading_change prepare_reactivity luma_instability accumulate accumulate_sharpen rcas
