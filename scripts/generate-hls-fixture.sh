#!/usr/bin/env bash
set -euo pipefail
# Original synthetic test audio, never a downloaded recording.
fixture=android/app/src/androidTest/assets/hls
mkdir -p "$fixture"
ffmpeg -hide_banner -loglevel error -y -f lavfi \
    -i 'sine=frequency=440:sample_rate=44100:duration=75' \
    -c:a aac -b:a 32k -f hls -hls_time 6 -hls_playlist_type vod \
    -hls_segment_filename "$fixture/segment%02d.ts" "$fixture/media.m3u8"
