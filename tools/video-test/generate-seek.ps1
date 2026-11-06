$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '..\..'
$output = Join-Path $root 'app\src\androidTest\assets\seek-frames.mp4'
# Eight vertical binary bars encode the zero-based frame number; 24 fps, four-second GOP, B frames.
& ffmpeg -nostdin -n -hide_banner -loglevel error -f lavfi -i "nullsrc=s=320x180:r=24,geq=lum='32+192*mod(floor(N/pow(2,floor(X/40))),2)':cb=128:cr=128" -t 10 -c:v libx264 -preset medium -crf 12 -g 96 -keyint_min 96 -sc_threshold 0 -bf 3 -pix_fmt yuv420p -movflags +faststart $output
if ($LASTEXITCODE -ne 0) { throw 'Seek frame fixture failed' }