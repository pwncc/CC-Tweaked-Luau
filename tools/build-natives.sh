#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
#
# SPDX-License-Identifier: MPL-2.0

# Builds the ccluau native library (Luau VM + JNI bridge) for Linux. The
# Windows counterpart is tools/build-natives.ps1.
#
# Requires g++ (or $CXX) and a JDK. Override the JDK with JAVA_HOME, and the
# output triple with CCLUAU_PLATFORM (defaults to linux-<uname -m>).
#
# The shipped library is built in a container against an old glibc so that it
# runs on distributions older than the build host -- see tools/build-natives-docker.sh.

set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
luau="$root/vendor/luau"
bridge="$root/projects/core/src/main/native/ccluau"

cxx=${CXX:-g++}

arch=$(uname -m)
case "$arch" in
    x86_64 | amd64) arch=x86_64 ;;
    aarch64 | arm64) arch=arm64 ;;
esac
platform=${CCLUAU_PLATFORM:-linux-$arch}

builddir="$root/build/natives/$platform"
outdir="$root/projects/core/src/main/resources/lib/ccluau/$platform"

# Locate a JDK for jni.h.
jdk=${JAVA_HOME:-}
if [ -z "$jdk" ]; then
    for candidate in /usr/lib/jvm/java-21-openjdk-* /usr/lib/jvm/java-17-openjdk-* /usr/lib/jvm/default-java; do
        if [ -f "$candidate/include/jni.h" ]; then jdk=$candidate; break; fi
    done
fi
[ -f "$jdk/include/jni.h" ] || { echo "Cannot find jni.h under '$jdk'; set JAVA_HOME" >&2; exit 1; }

mkdir -p "$builddir/obj" "$outdir"

includes=""
for dir in Common Ast Bytecode Inliner Compiler CodeGen VM; do
    includes="$includes -I$luau/$dir/include"
done
includes="$includes -I$luau/VM/src -I$jdk/include -I$jdk/include/linux"

# LUAI_MAXCSTACK is raised to match Cobalt's stack limits (CraftOS allows
# unpacking ~half a million values).
#
# -fvisibility=hidden keeps Luau's lua_* symbols out of the global dynamic
# symbol table, where they would otherwise be interposable and could collide
# with another natively-loaded Lua. JNIEXPORT is visibility("default"), so the
# JNI entry points are still exported.
cxxflags="-O2 -std=c++17 -fPIC -fno-math-errno -fvisibility=hidden -fvisibility-inlines-hidden \
    -DNDEBUG -DLUA_USE_LONGJMP=0 -DLUAI_MAXCSTACK=1048576 $includes"

# Unlike the Windows build we do not link -static: a fully static shared object
# is not usable on glibc. Statically linking just libstdc++/libgcc frees us from
# the host's C++ runtime while keeping the glibc dependency dynamic.
#
# The version script restricts the dynamic symbol table to the JNI entry points;
# see ccluau.map for why that matters.
ldflags="-shared -static-libgcc -static-libstdc++ -pthread \
    -Wl,--version-script=$bridge/ccluau.map -Wl,--as-needed"

sources=""
for dir in Common Ast Bytecode Compiler CodeGen VM; do
    sources="$sources $luau/$dir/src/*.cpp"
done
sources="$sources $bridge/*.cpp"

# shellcheck disable=SC2086
sources=$(ls $sources)

echo "Building $platform with $($cxx --version | head -1)"

# Basenames collide across Luau's subprojects, so qualify each object with its
# path relative to the repository root. Keeping the name root-relative (rather
# than deriving it from the absolute path) means the same object is reused
# whether the tree is built in place, in WSL or bind-mounted into a container.
objname() {
    echo "$builddir/obj/$(echo "${1#"$root/"}" | tr '/' '_' | sed 's/\.cpp$/.o/')"
}

compile_one() {
    $cxx $cxxflags -c "$1" -o "$(objname "$1")"
}
export -f compile_one objname
export cxx cxxflags builddir root

echo "$sources" | xargs -P "$(nproc)" -I{} bash -c 'compile_one "$@"' _ {}

# Link exactly the objects just built. Globbing the directory would pick up
# stale objects left by a build of a different revision or layout.
objects=""
for src in $sources; do objects="$objects $(objname "$src")"; done
# shellcheck disable=SC2086
$cxx $objects -o "$builddir/libccluau.so" $ldflags

cp "$builddir/libccluau.so" "$outdir/libccluau.so"

echo "Built $outdir/libccluau.so"
echo "  size:  $(stat -c %s "$outdir/libccluau.so") bytes"
echo "  glibc: $(objdump -T "$outdir/libccluau.so" | grep UND | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1)"
echo "  exports: $(objdump -T "$outdir/libccluau.so" | grep -c 'g *DF .text')"
