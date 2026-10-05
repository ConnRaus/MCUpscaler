#!/bin/zsh
# Regenerates src/native/mac/fsr3_fg_shaders.h: AMD FidelityFX FSR 3.1.4 frame generation (optical flow + frame interpolation,
# MIT licence) GLSL passes -> SPIR-V -> MSL. Same tools and SDK checkout as build_fsr3_shaders.sh.
# Usage: build_fsr3_fg_shaders.sh <path to FidelityFX-SDK v1.1.4>
set -e
SDK=${1:?usage: build_fsr3_fg_shaders.sh <FidelityFX-SDK v1.1.4>}
HERE=${0:A:h}
WORK=$(mktemp -d)
trap 'rm -rf $WORK' EXIT
cp -R $SDK/sdk/include/FidelityFX/gpu $WORK/gpu
# Metal has 16 sampler slots: move the linear sampler from binding 1000 to 15.
sed -i '' 's/binding = 1000)/binding = 15)/' $WORK/gpu/frameinterpolation/ffx_frameinterpolation_callbacks_glsl.h
# Metal allows 8 read-write textures per kernel: SPD only reads back mip 5 of the inpainting pyramid, so the other 12 mips
# become write-only.
python3 - $WORK/gpu/frameinterpolation/ffx_frameinterpolation_callbacks_glsl.h <<'EOF'
import re, sys
path = sys.argv[1]
s = open(path).read()
s, n = re.subn(r'(rgba16f\))(\s+)(uniform image2D\s+rw_inpainting_pyramid(?!5;)\d+;)', r'\1 writeonly\2\3', s)
assert n == 12, n
s, n = re.subn(r'LOAD\((?!5\))\d+\);\n', '', s)
assert n == 12, n
open(path, 'w').write(s)
EOF
# Interpolation: one hardware bilinear sample (the linear clamp sampler) instead of four reads. The interpolation rect is
# the whole texture here, so clamping to the edge gives the colour of AMD's renormalised in-rect taps; the weight sum is
# worked out per axis. Measurably faster at 3024x1964.
python3 - $WORK/gpu/frameinterpolation/ffx_frameinterpolation.h <<'EOF'
import sys
path = sys.argv[1]
s = open(path).read()
old = s[s.index('    BilinearSamplingData bilinearInfo = GetBilinearSamplingData(fReprojectedUv, texSize);'):s.index('    result.fRaw               = fColor;')]
new = '''    FfxFloat32x2 fPxSample = fReprojectedUv * FfxFloat32x2(texSize) - FfxFloat32x2(0.5, 0.5);
    FfxFloat32x2 fBase = floor(fPxSample);
    FfxFloat32x2 fFrac = fPxSample - fBase;
    FfxFloat32x2 fLo = FfxFloat32x2(InterpolationRectBase());
    FfxFloat32x2 fHi = fLo + FfxFloat32x2(InterpolationRectSize());
    FfxFloat32x2 fW0 = (FfxFloat32x2(1.0, 1.0) - fFrac) * FfxFloat32x2(greaterThanEqual(fBase, fLo)) * FfxFloat32x2(lessThan(fBase, fHi));
    FfxFloat32x2 fW1 = fFrac * FfxFloat32x2(greaterThanEqual(fBase + 1.0, fLo)) * FfxFloat32x2(lessThan(fBase + 1.0, fHi));
    FfxFloat32 fWeightSum = (fW0.x + fW1.x) * (fW0.y + fW1.y);

    FfxFloat32x3 fColor = FfxFloat32x3(0.0, 0.0, 0.0);
    if (fWeightSum != 0.0f)
        fColor = isCurrent ? SampleCurrentBackbuffer(fReprojectedUv) : SamplePreviousBackbuffer(fReprojectedUv);

'''
s = s.replace(old, new)
open(path, 'w').write(s)
EOF
SRC=$SDK/sdk/src/backends/vk/shaders
# Permutation: LDR colour, render-size motion vectors without jitter, inverted (reversed-Z) depth; FP32 everywhere.
OF_DEFS=(-DFFX_GPU=1 -DFFX_GLSL=1 -DFFX_HALF=0 -DFFX_OPTICALFLOW_OPTION_HDR_COLOR_INPUT=0)
FI_DEFS=(-DFFX_GPU=1 -DFFX_GLSL=1 -DFFX_HALF=0
         -DFFX_FRAMEINTERPOLATION_OPTION_UPSAMPLE_SAMPLERS_USE_DATA_HALF=0 -DFFX_FRAMEINTERPOLATION_OPTION_ACCUMULATE_SAMPLERS_USE_DATA_HALF=0
         -DFFX_FRAMEINTERPOLATION_OPTION_REPROJECT_SAMPLERS_USE_DATA_HALF=0 -DFFX_FRAMEINTERPOLATION_OPTION_POSTPROCESSLOCKSTATUS_SAMPLERS_USE_DATA_HALF=0
         -DFFX_FRAMEINTERPOLATION_OPTION_UPSAMPLE_USE_LANCZOS_TYPE=2
         -DFFX_FRAMEINTERPOLATION_OPTION_LOW_RES_MOTION_VECTORS=1 -DFFX_FRAMEINTERPOLATION_OPTION_JITTER_MOTION_VECTORS=0
         -DFFX_FRAMEINTERPOLATION_OPTION_INVERTED_DEPTH=1)
pass() { # name dir glsl defs...
  local name=$1 dir=$2 file=$3; shift 3
  glslangValidator -V --target-env vulkan1.2 -S comp -I$WORK/gpu -I$WORK/gpu/$dir "$@" $SRC/$dir/$file -o $WORK/$name.spv > $WORK/$name.log || { cat $WORK/$name.log; exit 1; }
  spirv-cross $WORK/$name.spv --msl --msl-version 30100 --msl-decoration-binding --rename-entry-point main fsr_$name comp --output $WORK/$name.metal
}
pass of_prepare_luma opticalflow ffx_opticalflow_prepare_luma_pass.glsl $OF_DEFS
pass of_luminance_pyramid opticalflow ffx_opticalflow_compute_luminance_pyramid_pass.glsl $OF_DEFS
pass of_scd_histogram opticalflow ffx_opticalflow_generate_scd_histogram_pass.glsl $OF_DEFS
pass of_scd_divergence opticalflow ffx_opticalflow_compute_scd_divergence_pass.glsl $OF_DEFS
pass of_search opticalflow ffx_opticalflow_compute_optical_flow_advanced_pass_v5.glsl $OF_DEFS
pass of_filter opticalflow ffx_opticalflow_filter_optical_flow_pass_v5.glsl $OF_DEFS
pass of_scale opticalflow ffx_opticalflow_scale_optical_flow_advanced_pass_v5.glsl $OF_DEFS
pass fi_reconstruct_and_dilate frameinterpolation ffx_frameinterpolation_reconstruct_and_dilate_pass.glsl $FI_DEFS
pass fi_setup frameinterpolation ffx_frameinterpolation_setup_pass.glsl $FI_DEFS
pass fi_reconstruct_previous_depth frameinterpolation ffx_frameinterpolation_reconstruct_previous_depth_pass.glsl $FI_DEFS
pass fi_game_motion_vector_field frameinterpolation ffx_frameinterpolation_game_motion_vector_field_pass.glsl $FI_DEFS
pass fi_game_vector_field_inpainting_pyramid frameinterpolation ffx_frameinterpolation_compute_game_vector_field_inpainting_pyramid_pass.glsl $FI_DEFS
pass fi_optical_flow_vector_field frameinterpolation ffx_frameinterpolation_optical_flow_vector_field_pass.glsl $FI_DEFS
pass fi_disocclusion_mask frameinterpolation ffx_frameinterpolation_disocclusion_mask_pass.glsl $FI_DEFS
pass fi_interpolation frameinterpolation ffx_frameinterpolation_pass.glsl $FI_DEFS
pass fi_inpainting_pyramid frameinterpolation ffx_frameinterpolation_compute_inpainting_pyramid_pass.glsl $FI_DEFS
pass fi_inpainting frameinterpolation ffx_frameinterpolation_inpainting_pass.glsl $FI_DEFS
FSR_HEADER_WHAT="frame generation passes (optical flow + frame interpolation)" python3 $HERE/gen_header.py $WORK $HERE/../../src/native/mac/fsr3_fg_shaders.h \
  of_prepare_luma of_luminance_pyramid of_scd_histogram of_scd_divergence of_search of_filter of_scale \
  fi_reconstruct_and_dilate fi_setup fi_reconstruct_previous_depth fi_game_motion_vector_field fi_game_vector_field_inpainting_pyramid \
  fi_optical_flow_vector_field fi_disocclusion_mask fi_interpolation fi_inpainting_pyramid fi_inpainting
mkdir -p ${KEEP_MSL:-/dev/null/x} 2>/dev/null && cp $WORK/*.metal $KEEP_MSL/ || true
