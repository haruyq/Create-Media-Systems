# CMS disc FFmpeg

The DVD reader batches consecutive sectors through libdvdnav's cache. DVD navigation and CSS authentication use the original drive path. The `dvdvideo` input option `-read_ahead 0` disables batching for comparison.

Build in an MSYS2 UCRT64 shell:

```sh
pacman -S --needed make diffutils patch curl tar mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-pkgconf mingw-w64-ucrt-x86_64-nasm mingw-w64-ucrt-x86_64-libdvdnav mingw-w64-ucrt-x86_64-libdvdread mingw-w64-ucrt-x86_64-libdvdcss mingw-w64-ucrt-x86_64-libx264 mingw-w64-ucrt-x86_64-libvpl mingw-w64-ucrt-x86_64-ffnvcodec-headers mingw-w64-ucrt-x86_64-amf-headers
bash tools/disc-ffmpeg/build.sh /c/build/cms-disc-ffmpeg
```

`build.sh` pins and verifies the FFmpeg source archive. The runtime includes its package manifest, patch and dependency licenses. The matching source archive is published alongside the Windows runtime in the [disc FFmpeg release](https://github.com/haruyq/Create-Media-Systems/releases/tag/disc-ffmpeg-9.0.2-cms-dvd1). It includes FFmpeg's original archive, this patch and build script, and the MSYS2 source packages for the bundled libraries.

Keep the generated directory's `bin` files together. NVENC, QSV, AMF and libx264 are enabled; the hardware encoders require their corresponding installed drivers.

For a slow-I/O comparison, apply `test-latency.patch` to libdvdcss 1.6.0 and build it with Meson in UCRT64. Copy the resulting DLL into a separate copy of the runtime, then set `CMS_TEST_IO_MS=3`. Each underlying read waits three milliseconds and reports its sector count to stderr. Compare `-f dvdvideo -title 1 -read_ahead 0` and `-read_ahead 1` on the same DVD or ISO. The test DLL must not be included in the distributed runtime.
