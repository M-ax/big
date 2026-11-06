#!/bin/sh
set -eu
mkdir -p '/shares/media/People & Topics/Empty' /shares/public
cp /seed.mp4 '/shares/media/People & Topics/Clip #1 + 100% café.mp4'
cp /seed.mp4 '/shares/media/Buffer-test.mp4'
truncate -s 33554432 '/shares/media/Buffer-test.mp4'
for sample in aspect-ratio-failing-example.mkv real-world-sample.mp4; do
    if [ -f "/samples/$sample" ]; then
        cp "/samples/$sample" "/shares/media/$sample"
    fi
done
printf 'Not a media file\n' > /shares/media/notes.txt
printf 'Guest access works\n' > /shares/public/welcome.txt
chmod -R a+rX /shares
exec smbd --foreground --no-process-group --debug-stdout