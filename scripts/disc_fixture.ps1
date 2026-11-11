param(
    [string]$Output = 'build/disc-fixtures',
    [string]$Ffmpeg = 'ffmpeg',
    [string]$TsMuxer = 'tsMuxeR',
    [string]$DvdAuthor = 'dvdauthor'
)

$ErrorActionPreference = 'Stop'
$destination = [IO.Path]::GetFullPath($Output)
if ((Test-Path -LiteralPath $destination) -or $destination -match '[\r\n]') {
    throw 'Choose a new output directory. Existing fixtures are never overwritten.'
}
New-Item -ItemType Directory -Path $destination | Out-Null

function Invoke-FixtureTool([string]$Tool, [string[]]$Arguments) {
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed with exit code $LASTEXITCODE" }
}

$short = Join-Path $destination 'short.m2ts'
Invoke-FixtureTool $Ffmpeg @('-v', 'error', '-nostdin', '-f', 'lavfi', '-i', 'testsrc2=size=1920x1080:rate=60',
    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '32', '-c:v', 'libx264', '-preset', 'veryfast',
    '-threads', '4', '-b:v', '8M', '-g', '120', '-pix_fmt', 'yuv420p', '-c:a', 'ac3', '-b:a', '192k', $short)
$long = Join-Path $destination 'long.m2ts'
Invoke-FixtureTool $Ffmpeg @('-v', 'error', '-nostdin', '-stream_loop', '1', '-i', $short, '-map', '0', '-c', 'copy', '-t', '64', $long)
foreach ($kind in @('short', 'long')) {
    $inputFile = Join-Path $destination "$kind.m2ts"
    $meta = Join-Path $destination "$kind.meta"
    @"
MUXOPT --blu-ray --vbr --vbv-len=500
V_MPEG4/ISO/AVC, "$inputFile", fps=60, track=4113
A_AC3, "$inputFile", track=4352
"@ | ForEach-Object { [IO.File]::WriteAllText($meta, $_) }
    Invoke-FixtureTool $TsMuxer @($meta, (Join-Path $destination "bluray-$kind"))
}

# A Blu-ray with two sound streams, the second a louder tone, and a subtitle stream: to choose between them.
$tone = Join-Path $destination 'tone.ac3'
Invoke-FixtureTool $Ffmpeg @('-v', 'error', '-nostdin', '-f', 'lavfi', '-i', 'sine=frequency=1000:sample_rate=48000,volume=6',
    '-t', '32', '-c:a', 'ac3', '-b:a', '192k', '-ac', '2', $tone)
$subtitles = Join-Path $destination 'subtitles.srt'
"1`n00:00:09,000 --> 00:00:19,000`nSUBTITLE`n`n" | ForEach-Object { [IO.File]::WriteAllText($subtitles, $_) }
$meta = Join-Path $destination 'tracks.meta'
@"
MUXOPT --blu-ray --vbr --vbv-len=500
V_MPEG4/ISO/AVC, "$short", fps=60, track=4113
A_AC3, "$short", track=4352, lang=jpn
A_AC3, "$tone", lang=eng
S_TEXT/UTF8, "$subtitles", font-name="Arial", font-size=120, font-color=0xffffffff, bottom-offset=24, font-border=5, text-align=center, video-width=1920, video-height=1080, fps=60, lang=eng
"@ | ForEach-Object { [IO.File]::WriteAllText($meta, $_) }
Invoke-FixtureTool $TsMuxer @($meta, (Join-Path $destination 'bluray-tracks'))

$dvdVideo = Join-Path $destination 'dvd.mpg'
Invoke-FixtureTool $Ffmpeg @('-v', 'error', '-nostdin', '-f', 'lavfi', '-i', 'testsrc2=size=720x480:rate=30000/1001',
    '-f', 'lavfi', '-i', 'sine=frequency=880:sample_rate=48000', '-t', '16', '-target', 'ntsc-dvd', '-aspect', '16:9', '-ac', '2', $dvdVideo)
$previousFormat = $env:VIDEO_FORMAT
try {
    $env:VIDEO_FORMAT = 'NTSC'
    Invoke-FixtureTool $DvdAuthor @('-o', (Join-Path $destination 'dvd'), '-t', $dvdVideo)
    Invoke-FixtureTool $DvdAuthor @('-o', (Join-Path $destination 'dvd'), '-T')
} finally {
    $env:VIDEO_FORMAT = $previousFormat
}
Write-Output "Fixtures created in $destination"
