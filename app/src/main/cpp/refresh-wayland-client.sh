#!/usr/bin/env bash
# Refresh the checked-in source subset and generated headers with a Linux Android NDK.
set -euo pipefail

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the NDK configured in app/build.gradle.kts}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$toolchain/clang"
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
cd "$scratch"

curl --fail --location --silent --show-error -o wayland.tar.xz \
    https://gitlab.freedesktop.org/wayland/wayland/-/releases/1.24.0/downloads/wayland-1.24.0.tar.xz
curl --fail --location --silent --show-error -o libffi.tar.gz \
    https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz
sha256sum --check <<'CHECKSUMS'
82892487a01ad67b334eca83b54317a7c86a03a89cfadacfef5211f11a5d0536  wayland.tar.xz
b0dea9df23c863a7a50e825440f3ebffabd65df1497108e5d437747843895a4e  libffi.tar.gz
CHECKSUMS
tar -xf wayland.tar.xz
tar -xf libffi.tar.gz
wayland="$scratch/wayland-1.24.0"
ffi="$scratch/libffi-3.4.6"
stage="$scratch/output"
mkdir -p "$stage"/{wayland/src,wayland/generated,libffi/src/{arm,aarch64,x86},libffi/include}

meson setup scanner "$wayland" -Dlibraries=false -Dscanner=true \
    -Dtests=false -Ddocumentation=false -Ddtd_validation=false
meson compile -C scanner wayland-scanner
scanner="$scratch/scanner/src/wayland-scanner"
cp "$scratch/scanner/src/wayland-version.h" "$stage/wayland/generated/"

cp "$wayland/COPYING" "$stage/wayland/"
cp "$wayland/src/"{connection.c,timespec-util.h,wayland-client.c,wayland-client.h,wayland-client-core.h,wayland-os.c,wayland-os.h,wayland-private.h,wayland-util.c,wayland-util.h} "$stage/wayland/src/"
# Android API 25 and newer provides accept4 and Linux MSG_CMSG_CLOEXEC.
cat >"$stage/wayland/config.h" <<'CONFIG'
#define HAVE_ACCEPT4 1
#define HAVE_BROKEN_MSG_CMSG_CLOEXEC 0
#define HAVE_XUCRED_CR_PID 0
CONFIG

xml="$root/wayland-protocol"
generate_protocol() {
    local protocol="$1" header="$2" source="$3" code_mode="$4"
    "$scanner" -s client-header "$xml/$protocol.xml" "$stage/wayland/generated/$header.h"
    "$scanner" -s "$code_mode" "$xml/$protocol.xml" "$stage/wayland/generated/$source.c"
}
generate_protocol wayland wayland-client-protocol wayland-protocol public-code
generate_protocol xdg-shell xdg-shell-client xdg-shell-protocol private-code
generate_protocol pointer-constraints-unstable-v1 pointer-constraints-client pointer-constraints-protocol private-code
generate_protocol relative-pointer-unstable-v1 relative-pointer-client relative-pointer-protocol private-code

cp "$ffi/LICENSE" "$stage/libffi/"
cp "$ffi/include/"{ffi_cfi.h,ffi_common.h,tramp.h} "$stage/libffi/include/"
cp "$ffi/src/"{closures.c,dlmalloc.c,java_raw_api.c,prep_cif.c,raw_api.c,tramp.c,types.c} "$stage/libffi/src/"
cp "$ffi/src/arm/"{ffi.c,internal.h,sysv.S} "$stage/libffi/src/arm/"
cp "$ffi/src/aarch64/"{ffi.c,internal.h,sysv.S} "$stage/libffi/src/aarch64/"
cp "$ffi/src/x86/"{asmnames.h,ffi.c,ffi64.c,ffiw64.c,internal.h,internal64.h,sysv.S,unix64.S,win64.S} "$stage/libffi/src/x86/"

configure_ffi() {
    local abi="$1" host="$2" clang_target="$3"
    mkdir -p "$scratch/ffi-$abi" "$stage/libffi/config/$abi"
    (
        cd "$scratch/ffi-$abi"
        "$ffi/configure" --host="$host" --build="$(bash "$ffi/config.guess")" \
            --disable-shared --disable-exec-static-tramp \
            CC="$toolchain/${clang_target}25-clang" \
            AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib"
        cp fficonfig.h include/{ffi.h,ffitarget.h} "$stage/libffi/config/$abi/"
    )
}
configure_ffi armeabi-v7a arm-linux-androideabi armv7a-linux-androideabi
configure_ffi arm64-v8a aarch64-linux-android aarch64-linux-android
configure_ffi x86 i686-linux-android i686-linux-android
configure_ffi x86_64 x86_64-linux-android x86_64-linux-android

mkdir -p "$root/third-party"
cp -R "$stage/wayland" "$stage/libffi" "$root/third-party/"
