#!/bin/bash
# Builds libidevice_ffi.a (+ idevice.h) from upstream idevice plus our patch
# (idevice-patch/: CoreDevice screenshot/touch/keys for the agent) and copies
# them into iPhoneUse/idevice/. Needs rustup with the aarch64-apple-ios target.
# `build-idevice.sh sim` builds the simulator lib into iPhoneUse/idevice/sim/ instead
# (needs the aarch64-apple-ios-sim target); see "Simulator" in the README.
set -euo pipefail
TARGET=aarch64-apple-ios SDK=iphoneos OUT=iPhoneUse/idevice
if [ "${1:-}" = sim ]; then TARGET=aarch64-apple-ios-sim SDK=iphonesimulator OUT=iPhoneUse/idevice/sim; fi
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=${IDEVICE_SRC:-$ROOT/.build/idevice}
COMMIT=3854a5d  # jkcoxson/idevice, "fix(ffi): preserve usbmuxd connection ownership (#138)"

if [ ! -d "$SRC" ]; then
  git clone https://github.com/jkcoxson/idevice.git "$SRC"
  git -C "$SRC" checkout -q $COMMIT
  git -C "$SRC" apply "$ROOT/idevice-patch/idevice.patch"
fi
cp "$ROOT/idevice-patch/probe.rs" "$SRC/ffi/src/core_device/probe.rs" # every run, so probe.rs edits land

cd "$SRC"
export PATH="$(dirname "$(rustup which cargo)"):$PATH"
export BINDGEN_EXTRA_CLANG_ARGS="--sysroot=$(xcrun --sdk $SDK --show-sdk-path)"
export IPHONEOS_DEPLOYMENT_TARGET=17.0
# idevice's release profile has lto = true, which embeds ~140 MB of LLVM bitcode
# in the .a (204 MB total). Without LTO/bitcode: 74 MB, same app behaviour.
# Don't `strip -S` the result: it corrupts aws-lc's assembly objects (link fails).
export CARGO_PROFILE_RELEASE_LTO=false RUSTFLAGS="-C embed-bitcode=no"
# Only what the app calls (tunnel, apps list, DDI mount, heartbeat, CoreDevice probe): 45 MB instead of 74 MB.
FEATURES="ring,obfuscate,core_device,tunnel_tcp_stack,tcp,installation_proxy,mobile_image_mounter,cryptexd,tss,heartbeat"
cargo build --release -p idevice-ffi --target $TARGET --no-default-features --features "$FEATURES"
mkdir -p "$ROOT/$OUT"
cp target/$TARGET/release/libidevice_ffi.a "$ROOT/$OUT/"
cp ffi/idevice.h "$ROOT/iPhoneUse/idevice/"
