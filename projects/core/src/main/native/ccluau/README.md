<!--
SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers

SPDX-License-Identifier: MPL-2.0
-->

# ccluau

`ccluau` is the native library behind the Luau runtime: the vendored Luau VM and
compiler (`vendor/luau`) plus the JNI bridge in `ccluau.cpp`. `LuauNative` loads
it from `lib/ccluau/<os>-<arch>/` on the classpath, extracting it to a
content-addressed cache first because `System.load` needs an on-disk path.

Prebuilt libraries are committed, so a normal build needs no C++ toolchain.

## Rebuilding

| Platform       | Command                                       |
| -------------- | --------------------------------------------- |
| `windows-x86_64` | `tools/build-natives.ps1`                   |
| `linux-x86_64`   | `tools/build-natives-docker.sh`             |

`tools/build-natives.sh` is the underlying Linux build and can be run directly
against the host toolchain when iterating. Use the Docker wrapper for anything
that gets committed — see the glibc note below.

Both scripts write to `projects/core/src/main/resources/lib/ccluau/<platform>/`.
The result is a committed binary, so rebuild it deliberately and mention the
rebuild in the commit message.

## Why the Linux build runs in a container

glibc resolves symbols to the versions present at *link* time, and there is no
compiler flag that lowers them. Building on a current distribution produces a
library that refuses to load on anything older — a host build on glibc 2.42
required glibc 2.38, which rules out Debian 12 and Ubuntu 22.04.

`tools/build-natives-docker.sh` therefore links inside Debian 11, which pins the
requirement at glibc 2.29 and covers every distribution likely to be running
Minecraft. Check it after any rebuild:

```bash
objdump -T libccluau.so | grep UND | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1
```

## Linking notes

`libstdc++` and `libgcc` are linked statically so the library does not depend on
the host's C++ runtime, matching the Windows build. That pulls in their objects
with default visibility, which would re-export `__cxa_*` and friends and let
them interpose on the JVM's own C++ runtime. `ccluau.map` restricts the dynamic
symbol table to the JNI entry points; `-fvisibility=hidden` does the same for
Luau's `lua_*` symbols. A correct build exports exactly the native methods
declared in `LuauNative`, and links against nothing beyond libc, libm, libpthread:

```bash
objdump -T libccluau.so | grep -c 'g  *DF'   # == the native methods in LuauNative
objdump -p libccluau.so | grep NEEDED
```

Unlike the Windows build, the Linux one must not pass `-static`: a fully static
shared object is not usable under glibc. It also needs `-fPIC`.
