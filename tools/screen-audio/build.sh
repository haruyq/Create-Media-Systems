#!/usr/bin/env bash
set -euo pipefail
[[ ${MSYSTEM:-} == UCRT64 ]] || { echo 'Run in an MSYS2 UCRT64 shell.' >&2; exit 1; }
here=$(cd -- "$(dirname -- "$0")" && pwd)
work=${1:?Pass an output directory}
mkdir -p "$work"
g++ -std=c++17 -O2 -s -static -municode -Wall -o "$work/cms-screen-audio.exe" "$here/screen_audio.cpp" \
    -lmmdevapi -lole32 -luuid
# Static linking must leave only system DLLs.
if objdump -p "$work/cms-screen-audio.exe" | awk '/DLL Name:/{print $3}' | grep -iE '^lib|gcc|stdc\+\+|winpthread'; then
    echo 'cms-screen-audio.exe depends on a MinGW DLL.' >&2
    exit 1
fi
echo "Built $work/cms-screen-audio.exe"
