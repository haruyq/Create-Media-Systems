#!/usr/bin/env bash
set -euo pipefail
[[ ${MSYSTEM:-} == UCRT64 ]] || { echo 'Run in an MSYS2 UCRT64 shell.' >&2; exit 1; }
here=$(cd -- "$(dirname -- "$0")" && pwd)
work=${1:?Pass a build directory}
mkdir -p "$work"
work=$(cd -- "$work" && pwd)
cd "$work"
archive=ffmpeg-n9.0.2.tar.gz
if [[ ! -f $archive ]]; then
    curl --fail --location --retry 3 "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n9.0.2" -o "$archive"
fi
echo "6e374ed621e48faa40639307dff48ba6fe574a509977956d2cce9669b7cc27e9  $archive" | sha256sum --check
if [[ ! -d FFmpeg-n9.0.2 ]]; then tar -xf "$archive"; fi
if ! grep -q opt_read_ahead FFmpeg-n9.0.2/libavformat/dvdvideodec.c; then
    patch --directory=FFmpeg-n9.0.2 -p1 < "$here/dvd-read-ahead.patch"
fi
mkdir -p ffmpeg-build
cd ffmpeg-build
../FFmpeg-n9.0.2/configure --target-os=mingw32 --arch=x86_64 --cc=gcc --cxx=g++ \
    --disable-autodetect --disable-shared --enable-static --disable-doc --disable-debug --disable-ffplay \
    --enable-gpl --enable-version3 --enable-libdvdnav --enable-libdvdread --enable-libx264 --enable-libvpl \
    --enable-ffnvcodec --enable-nvenc --enable-amf --enable-d3d11va --enable-dxva2 --enable-w32threads \
    --extra-version=cms-dvd1
make -j "${JOBS:-8}" ffmpeg.exe ffprobe.exe
package="$work/ffmpeg-9.0.2-cms-dvd1"
mkdir -p "$package/bin"
cp ffmpeg.exe ffprobe.exe "$package/bin/"
cp /ucrt64/bin/libdvdcss-2.dll "$package/bin/"
queue=("$package"/bin/*)
for ((i=0; i<${#queue[@]}; i++)); do
    while read -r dll; do
        if [[ -f /ucrt64/bin/$dll && ! -f $package/bin/$dll ]]; then
            cp "/ucrt64/bin/$dll" "$package/bin/"
            queue+=("$package/bin/$dll")
        fi
    done < <(objdump -p "${queue[i]}" | awk '/DLL Name:/{print $3}')
done
cp "$work/FFmpeg-n9.0.2/COPYING.GPLv3" "$package/LICENSE"
for lib in libdvdnav libdvdread libdvdcss libvpl libgcc 'libstdc++' libwinpthread winpthreads crt headers ffnvcodec-headers amf-headers; do
    if [[ -d /ucrt64/share/licenses/$lib ]]; then
        for license in /ucrt64/share/licenses/"$lib"/*; do
            [[ -f $license ]] || continue
            printf '\n\n%s\n' "$lib / ${license##*/}" >> "$package/LICENSE"
            cat "$license" >> "$package/LICENSE"
        done
    fi
done
pacman -Q > "$package/packages.txt"
cp "$here/README.md" "$package/README.txt"
cp "$here/dvd-read-ahead.patch" "$package/"
echo "Built $package"
