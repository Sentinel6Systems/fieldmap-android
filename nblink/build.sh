#!/usr/bin/env bash
# Builds the NetBird library of the app (app/libs/nblink.aar) from source.
#
# Everything comes from pinned sources: NetBird and all other Go packages at the versions in
# go.mod, checked against go.sum; gomobile at the version in go.mod; the Go toolchain at the
# version in go.mod. Nothing that is already built is downloaded. Gradle runs this script by
# itself when the library is missing or its sources changed (see app/build.gradle.kts).
#
# Needs:
#   - Go, exactly the version on the "toolchain" line of go.mod. The script looks in $FIELDMAP_GO
#     (a folder with bin/go), then in ~/sdk/<version>, then in PATH.
#   - The Android SDK ($ANDROID_HOME, or sdk.dir in local.properties) with the NDK
#     (side by side) at the version below, or $ANDROID_NDK_HOME.
#   - A Java compiler (the one that Gradle uses is good).
set -euo pipefail

NDK_VERSION="28.2.13676358"
# The ABIs that real devices have (64-bit and 32-bit ARM), and x86_64 for the emulator.
TARGETS="${NBLINK_TARGETS:-android/arm64,android/arm,android/amd64}"
MIN_API=26   # the app's minSdk
# client/internal/stdnet/discover_pion.go of NetBird at the version in go.mod (see _patch/).
PATCHED_SHA256="9a2b40b2ed4c8770d1601b04028c3ffa1efb15b86f45e24fc025f957bf0ae01c"

here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
out="${1:-$root/app/libs/nblink.aar}"

want="$(awk '$1 == "toolchain" { print $2 }' "$here/go.mod")"
for dir in "${FIELDMAP_GO:-}" "$HOME/sdk/$want"; do
    if [ -n "$dir" ] && [ -x "$dir/bin/go" ]; then PATH="$dir/bin:$PATH"; break; fi
done
command -v go >/dev/null || { echo "nblink: Go is not installed. Install $want (https://go.dev/dl/), for example into ~/sdk/$want." >&2; exit 1; }
export GOTOOLCHAIN=local
have="$(go env GOVERSION)"
[ "$have" = "$want" ] || { echo "nblink: found $have, but go.mod wants $want. Install $want into ~/sdk/$want or set FIELDMAP_GO." >&2; exit 1; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f "$root/local.properties" ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")"
fi
ndk="${ANDROID_NDK_HOME:-$sdk/ndk/$NDK_VERSION}"
[ -d "$ndk" ] || { echo "nblink: no Android NDK at $ndk. Install it: sdkmanager \"ndk;$NDK_VERSION\"" >&2; exit 1; }
export ANDROID_HOME="$sdk" ANDROID_NDK_HOME="$ndk"

# A fixed folder: Go writes the place of the changed copy of NetBird (below) into the library,
# so the same place gives the same library on every computer.
work="/tmp/fieldmap-nblink"
rm -rf "$work"
mkdir "$work"
trap 'rm -rf "$work"' EXIT
cd "$here"
# gomobile and gobind come from the module in go.mod, not from a copy on this computer.
GOFLAGS=-mod=mod go build -trimpath -o "$work/bin/" golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$work/bin:$PATH"

# One file of NetBird is replaced by _patch/discover_pion.go (the reason is in that file). Go
# does not permit a change to its module folder, so the build uses a copy of NetBird's source
# with that one file changed, and a copy of this module that points to it.
netbird="$(GOFLAGS=-mod=mod go list -m -f '{{.Dir}}' github.com/netbirdio/netbird)"
# The file that is replaced must be the one that the patch was made from.
echo "$PATCHED_SHA256  $netbird/client/internal/stdnet/discover_pion.go" | sha256sum -c --quiet - || {
    echo "nblink: NetBird's discover_pion.go is not the file that _patch/discover_pion.go was made from. Make the patch again from the new file, and put the new checksum into build.sh." >&2; exit 1; }
cp -r "$netbird" "$work/netbird"
chmod -R u+w "$work/netbird"
cp "$here/_patch/discover_pion.go" "$work/netbird/client/internal/stdnet/discover_pion.go"
mkdir "$work/nblink"
cp "$here"/*.go "$here/go.mod" "$here/go.sum" "$work/nblink/"
cd "$work/nblink"
go mod edit -replace "github.com/netbirdio/netbird=$work/netbird"

mkdir -p "$(dirname "$out")"
# -checklinkname=0: a package that NetBird uses (anet, the list of network interfaces on
# Android) links to a private name of Go's library. NetBird's own Android build does the same.
gomobile bind \
    -target="$TARGETS" -androidapi "$MIN_API" \
    -javapkg io.github.sentinel6systems.fieldmap \
    -trimpath -ldflags "-s -w -buildid= -checklinkname=0" \
    -o "$out" ./
rm -f "${out%.aar}-sources.jar"
echo "nblink: built $out with $have, NDK $(basename "$ndk"), NetBird $(cd "$here" && go list -m -f '{{.Version}}' github.com/netbirdio/netbird)"
