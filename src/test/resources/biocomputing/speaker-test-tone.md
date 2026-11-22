# MP3 decoder fixture

`speaker-test-tone.mp3` is a generated 0.15-second stereo test signal, not third-party music.
The left channel is 440 Hz and the right is 660 Hz.

```text
ffmpeg -nostdin -f lavfi -i "aevalsrc=0.15*sin(2*PI*440*t)|0.15*sin(2*PI*660*t):s=44100:d=0.15" -c:a libmp3lame -b:a 80k -write_xing 0 -id3v2_version 0 speaker-test-tone.mp3
```
