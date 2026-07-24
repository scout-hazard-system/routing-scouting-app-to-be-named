# Audio Scanner Workflow (scrcpy-isolated capture)
This workflow captures scanner audio from a connected Android phone, isolates that audio from desktop/system audio, and transcribes in chunks on CPU.

## What this setup does
- Uses `scrcpy` with `--no-video` to stream phone audio.
- Auto-routes scrcpy's sink input to a dedicated PulseAudio null sink (`scanner_sink`).
- Captures only `scanner_sink.monitor` using `ffmpeg`.
- Runs Whisper transcription on CPU (`int8`).
- Skips near-silence or heavily clipped chunks to reduce false positives.

## Prerequisites
- Phone connected by USB with USB debugging enabled and authorized (`adb devices` shows `device`).
- `adb`, `ffmpeg`, `pactl` installed.
- scrcpy 2.x+ (this setup uses local `scrcpy 4.1` at `~/.local/bin/scrcpy-4`).

## Run command
```bash
/home/gibi/Desktop/cop_pipeline/bin/python /home/gibi/Desktop/pipeline.py \
  --mode scrcpy \
  --start-scrcpy \
  --scrcpy-bin /home/gibi/.local/bin/scrcpy-4 \
  --serial ZT42269G82 \
  --duration 8
```

## Expected startup output
- `Starting scrcpy: ...`
- `Routed scrcpy sink-input #... to isolated sink 'scanner_sink'.`
- `Listening in mode: scrcpy`
- `Input source node: scanner_sink.monitor`

## Interpreting chunk output
- `[Captured Chatter]: ...` means non-silent audio was captured and transcribed.
- `[Skipped]: near-silence chunk` means silence/noise floor was filtered out.
- `[Skipped]: heavily clipped chunk ...` means invalid/overdriven chunk was filtered out.

## Stop behavior
- `Ctrl-C` performs graceful shutdown and prints `Stopping pipeline.`

## Troubleshooting
- If you see `unauthorized` in `adb devices`, unlock phone and accept USB debugging prompt.
- If scrcpy audio stream is not detected, verify you are using scrcpy 2.x+ (`scrcpy --version`).
- If no transcriptions appear, verify scanner audio is actively playing on phone.
- If false positives appear, reduce clipping at source and keep scanner volume moderate.
