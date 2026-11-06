$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '..\..'
$assets = Join-Path $root 'app\src\androidTest\assets'
$subtitles = Join-Path $PSScriptRoot 'filter-subtitles.srt'
$pattern = 'color=c=0x204060:s=320x180:r=24000/1001,drawbox=x=0:y=0:w=320:h=8:color=red:t=fill,drawbox=x=0:y=172:w=320:h=8:color=blue:t=fill'
& ffmpeg -y -hide_banner -loglevel error -f lavfi -i $pattern -i $subtitles -t 12 -c:v libx264 -preset ultrafast -crf 24 -pix_fmt yuv420p -c:s srt -disposition:s:0 default (Join-Path $assets 'filter-24.mkv')
if ($LASTEXITCODE -ne 0) { throw 'Fractional-rate fixture failed' }
& ffmpeg -y -hide_banner -loglevel error -f lavfi -i 'testsrc2=s=320x180:r=60' -t 8 -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p (Join-Path $assets 'filter-60.mp4')
if ($LASTEXITCODE -ne 0) { throw 'High-frame-rate fixture failed' }
& ffmpeg -y -hide_banner -loglevel error -display_rotation:v:0 90 -i (Join-Path $assets 'filter-60.mp4') -c copy (Join-Path $assets 'filter-rotated.mp4')
if ($LASTEXITCODE -ne 0) { throw 'Rotated fixture failed' }
& ffmpeg -y -hide_banner -loglevel error -display_rotation:v:0 90 -i (Join-Path $assets 'filter-24.mkv') -map '0:v:0' -c copy (Join-Path $assets 'filter-rotated-solid.mp4')
if ($LASTEXITCODE -ne 0) { throw 'Solid rotated fixture failed' }