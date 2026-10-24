# Bad Apple!! in a JVM

The window is drawn from a lossless RGB pixel-delta stream. Each frame stores the XOR changes to its red channel (the source is grayscale) plus the exact RGB values for every pixel whose green or blue channel differs. Java reconstructs those decoded RGB pixels before painting, so `frames.rgbd` contains the actual pixel changes (not a video playback surface). FFmpeg reads the MP4's audio and sends PCM to Java Sound; the frame clock starts alongside that audio decoder.

## Play

Requirements: Java 11 or newer, FFmpeg, and the MP4 you are authorized to use.

Put all generated `frames-NNN.rgbd` parts beside `BadApplePlayer.java`, then run from this directory:

```powershell
java BadApplePlayer.java "C:\path\to\Bad Apple.mp4"
```

If FFmpeg is not on `PATH`, pass its executable as the second argument:

```powershell
java BadApplePlayer.java "C:\path\to\Bad Apple.mp4" "C:\path\to\ffmpeg.exe"
```

## Regenerate the pixel changes

The checked-in `frames.rgbd` was generated from the 480×360, 30 fps video in Downloads using FFmpeg and NumPy. Regenerate it for another source file with:

```powershell
python -m pip install numpy
python generate_frames.py "C:\path\to\Bad Apple.mp4"
```

This writes numbered `frames-NNN.rgbd` parts and `frames.json`. The frame stream is RGB24 and lossless after FFmpeg's RGB frame decode; it does not contain audio. For GitHub, keep the required MP4 outside the repository and provide it locally when running the player. Confirm you have the rights needed before redistributing video-derived assets.
