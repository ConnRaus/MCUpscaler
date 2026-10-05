#!/bin/sh
# Downloads the SDKs the native bridges are built against into _deps/ (gitignored; none of them are redistributed
# with the source). Folders that already exist are left alone. On a Mac only the FidelityFX SDK is needed, and only to
# regenerate the Metal FSR shaders (tools/fsr3/).
set -e
cd "$(dirname "$0")/.."
mkdir -p _deps
fetch() { # folder tag url
	if [ -e "_deps/$1" ]; then
		echo "_deps/$1 already there"
	else
		git -c advice.detachedHead=false clone --depth 1 --branch "$2" "$3" "_deps/$1"
	fi
}
fetch dlss-sdk v310.9.1 https://github.com/NVIDIA/DLSS.git
fetch ffx-sdk v1.1.4 https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK.git
fetch vulkan-headers v1.4.365 https://github.com/KhronosGroup/Vulkan-Headers.git
