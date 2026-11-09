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
"@ | Set-Content -LiteralPath $meta -Encoding utf8
    Invoke-FixtureTool $TsMuxer @($meta, (Join-Path $destination "bluray-$kind"))
}

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
