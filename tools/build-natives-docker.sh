#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
#
# SPDX-License-Identifier: MPL-2.0

# Builds the shipped Linux libccluau.so inside a Debian 11 (bullseye)
# container. Runs tools/build-natives.sh with an old toolchain so the result
# works on distributions older than the build host.
#
# glibc symbol versions are resolved at link time and cannot be lowered with
# compiler flags, so the only reliable way to support older distributions is to
# link against an older glibc. Bullseye's 2.31 covers Debian 11+, Ubuntu 20.04+,
# RHEL 9+ and every rolling distribution.
#
# Run from Linux, or from Git Bash / WSL on Windows with Docker Desktop running.

set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)

# Docker on Windows needs a native path (E:/...) rather than an MSYS one
# (/e/...), and MSYS must be told not to rewrite the in-container paths.
dockerroot=$(cd "$root" && { pwd -W 2>/dev/null || pwd; })
export MSYS_NO_PATHCONV=1

image=ccluau-build-linux-x86_64

# Bullseye is on archive.debian.org now that it has passed its regular support
# window, so the snapshot sources are pinned here rather than left to default.
docker build -t "$image" -f - "$dockerroot" <<'DOCKERFILE'
FROM debian:bullseye

# Bullseye's main archive has moved to archive.debian.org, but the security
# suite is still served from security.debian.org. Both are needed: the base
# image ships a security-updated libc6, so libc6-dev must come from there too.
RUN { printf 'deb http://archive.debian.org/debian bullseye main\n'; \
      printf 'deb http://archive.debian.org/debian bullseye-updates main\n'; \
      printf 'deb http://security.debian.org/debian-security bullseye-security main\n'; \
    } > /etc/apt/sources.list \
    && printf 'Acquire::Check-Valid-Until "false";\n' > /etc/apt/apt.conf.d/99no-check-valid \
    && apt-get update \
    && apt-get install -y --no-install-recommends g++ binutils openjdk-17-jdk-headless file \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
DOCKERFILE

# The build writes into build/ and projects/core/src/main/resources, so the
# repository is mounted read-write. --user keeps the outputs owned by the
# caller on native Linux; on Docker Desktop it is a no-op.
docker run --rm \
    -v "$dockerroot":/src \
    -w /src \
    -e CCLUAU_PLATFORM=linux-x86_64 \
    "$image" \
    bash tools/build-natives.sh

echo
echo "Verifying the shipped library:"
docker run --rm \
    -v "$dockerroot":/src \
    -w /src \
    "$image" \
    bash -c '
        so=projects/core/src/main/resources/lib/ccluau/linux-x86_64/libccluau.so
        file "$so"
        echo "max glibc: $(objdump -T "$so" | grep UND | grep -o "GLIBC_[0-9.]*" | sort -uV | tail -1)"
        echo "needed:    $(objdump -p "$so" | awk "/NEEDED/ {print \$2}" | tr "\n" " ")"
        echo "exports:   $(objdump -T "$so" | grep -c "g  *DF")"
    '
