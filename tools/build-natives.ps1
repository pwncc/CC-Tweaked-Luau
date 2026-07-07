# SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
#
# SPDX-License-Identifier: MPL-2.0

# Builds the ccluau native library (Luau VM + JNI bridge) for Windows x64
# using the portable MinGW-w64 toolchain in vendor/tools. Generates a Ninja
# build file and runs it.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$luau = "$root\vendor\luau"
$bridge = "$root\projects\core\src\main\native\ccluau"
$buildDir = "$root\build\natives\windows-x86_64"
$outDir = "$root\projects\core\src\main\resources\lib\ccluau\windows-x86_64"

$gcc = "$root\vendor\tools\gcc\mingw64\bin\g++.exe"
$ninja = "$root\vendor\tools\ninja\ninja.exe"

$jdk = $env:JAVA_HOME
if (-not $jdk) { $jdk = "C:\Program Files\Java\jdk-21" }
if (-not (Test-Path "$jdk\include\jni.h")) { throw "Cannot find jni.h under $jdk" }

New-Item -ItemType Directory -Force $buildDir | Out-Null
New-Item -ItemType Directory -Force $outDir | Out-Null

$includes = @(
    "$luau\Common\include",
    "$luau\Ast\include",
    "$luau\Bytecode\include",
    "$luau\Inliner\include",
    "$luau\Compiler\include",
    "$luau\CodeGen\include",
    "$luau\VM\include",
    "$luau\VM\src",
    "$jdk\include",
    "$jdk\include\win32"
) | ForEach-Object { "-I`"$_`"" }

# LUAI_MAXCSTACK is raised to match Cobalt's stack limits (CraftOS allows
# unpacking ~half a million values).
$cxxflags = "-O2 -std=c++17 -fno-math-errno -DNDEBUG -DLUA_USE_LONGJMP=0 -DLUAI_MAXCSTACK=1048576 " + ($includes -join ' ')
$ldflags = "-shared -static-libgcc -static-libstdc++ -static -lpthread"

$sources = @()
$sources += Get-ChildItem "$luau\Common\src\*.cpp"
$sources += Get-ChildItem "$luau\Ast\src\*.cpp"
$sources += Get-ChildItem "$luau\Bytecode\src\*.cpp"
$sources += Get-ChildItem "$luau\Compiler\src\*.cpp"
$sources += Get-ChildItem "$luau\CodeGen\src\*.cpp"
$sources += Get-ChildItem "$luau\VM\src\*.cpp"
$sources += Get-ChildItem "$bridge\*.cpp"

$ninjaFile = @()
$ninjaFile += "cxx = $($gcc -replace '\\', '/')"
$ninjaFile += "cxxflags = $cxxflags"
$ninjaFile += "ldflags = $ldflags"
$ninjaFile += ""
$ninjaFile += "rule cc"
$ninjaFile += "  command = `$cxx `$cxxflags -MMD -MF `$out.d -c `$in -o `$out"
$ninjaFile += "  depfile = `$out.d"
$ninjaFile += "  deps = gcc"
$ninjaFile += ""
$ninjaFile += "rule link"
$ninjaFile += "  command = `$cxx `$in -o `$out `$ldflags"
$ninjaFile += ""

$objects = @()
foreach ($src in $sources) {
    $obj = "obj/" + $src.BaseName + "_" + ($src.FullName.GetHashCode() -band 0xFFFF).ToString('x4') + ".o"
    $objects += $obj
    $srcPath = ($src.FullName -replace '\\', '/') -replace ':', '$:'
    $ninjaFile += "build $($obj): cc $srcPath"
}
$ninjaFile += ""
$ninjaFile += "build ccluau.dll: link $($objects -join ' ')"
$ninjaFile += "default ccluau.dll"

Set-Content -Path "$buildDir\build.ninja" -Value ($ninjaFile -join "`n") -Encoding ascii

& $ninja -C $buildDir
if ($LASTEXITCODE -ne 0) { throw "ninja build failed" }

Copy-Item "$buildDir\ccluau.dll" "$outDir\ccluau.dll" -Force

$srcHash = (Get-FileHash "$buildDir\ccluau.dll").Hash
$dstHash = (Get-FileHash "$outDir\ccluau.dll").Hash
if ($srcHash -ne $dstHash) { throw "Copy verification failed: $srcHash != $dstHash" }
Write-Host "Built $outDir\ccluau.dll ($($srcHash.Substring(0, 16)))"
