#!/usr/bin/env python3
"""Encode decoded RGB frames as a zlib-compressed stream of XOR deltas."""
import argparse
import json
import subprocess
import sys
import zlib
from pathlib import Path

import numpy as np

WIDTH, HEIGHT, FPS = 480, 360, 30
FRAME_BYTES = WIDTH * HEIGHT * 3
PIXELS = WIDTH * HEIGHT
FRAMES_PER_PART = 2200


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path, help="Bad Apple MP4 to encode")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--output", type=Path, default=Path("frames.rgbd"))
    parser.add_argument("--metadata", type=Path, default=Path("frames.json"))
    args = parser.parse_args()

    proc = subprocess.Popen([
        args.ffmpeg, "-v", "error", "-i", str(args.video), "-map", "0:v:0",
        "-vf", f"fps={FPS},scale={WIDTH}:{HEIGHT}:flags=neighbor",
        "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1",
    ], stdout=subprocess.PIPE)
    compressor = zlib.compressobj(level=9)
    previous = np.zeros(PIXELS, dtype=np.uint8)
    count = 0
    part = 0
    out = None
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def part_path(index):
        return args.output.with_name(f"{args.output.stem}-{index:03d}{args.output.suffix}")

    try:
        while True:
            if count and count % FRAMES_PER_PART == 0:
                out.write(compressor.flush())
                out.close()
                out = None
                part += 1
                compressor = zlib.compressobj(level=9)
            frame = proc.stdout.read(FRAME_BYTES)
            if not frame:
                break
            if len(frame) != FRAME_BYTES:
                raise RuntimeError(f"Truncated frame {count}: {len(frame)} bytes")
            if out is None:
                out = part_path(part).open("wb")
            current = np.frombuffer(frame, dtype=np.uint8).reshape(PIXELS, 3)
            gray = current[:, 0]
            exceptions = np.flatnonzero((current[:, 1] != gray) | (current[:, 2] != gray))
            packed = bytearray(np.bitwise_xor(gray, previous).tobytes())
            packed.extend(len(exceptions).to_bytes(2, "big"))
            for pixel in exceptions:
                packed.extend(int(pixel).to_bytes(4, "big"))
                packed.extend(current[pixel].tobytes())
            out.write(compressor.compress(packed))
            previous = gray
            count += 1
            if count % 300 == 0:
                print(f"Encoded {count} frames", file=sys.stderr)
        if out is not None:
            out.write(compressor.flush())
            out.close()
        rc = proc.wait()
        if rc:
            raise RuntimeError(f"ffmpeg exited with status {rc}")
    finally:
        if out is not None and not out.closed:
            out.close()
        if proc.poll() is None:
            proc.kill()
    args.metadata.write_text(json.dumps({
        "width": WIDTH, "height": HEIGHT, "fps": FPS, "frames": count,
        "pixelFormat": "RGB24", "encoding": "zlib stream of grayscale XOR deltas plus exact RGB exceptions",
        "source": args.video.name,
    }, indent=2) + "\n", encoding="utf-8")
    parts = [part_path(i) for i in range(part + 1)]
    print(f"Wrote {count} frames across {len(parts)} parts: " + ", ".join(f"{p.name} ({p.stat().st_size:,} bytes)" for p in parts))


if __name__ == "__main__":
    main()
