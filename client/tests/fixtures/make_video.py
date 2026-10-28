"""Create a one-second MP4 with real video/audio tracks for upload regression."""
from pathlib import Path
import sys
import av
import numpy as np

path = Path(sys.argv[1])
path.parent.mkdir(parents=True, exist_ok=True)
with av.open(str(path), "w") as output:
    video = output.add_stream("mpeg4", rate=10)
    video.width, video.height, video.pix_fmt = 160, 120, "yuv420p"
    audio = output.add_stream("aac", rate=16000)
    audio.layout = "mono"
    for index in range(10):
        frame = av.VideoFrame.from_ndarray(np.full((120, 160, 3), index * 20, dtype=np.uint8), format="rgb24")
        for packet in video.encode(frame): output.mux(packet)
    for packet in video.encode(): output.mux(packet)
    samples = av.AudioFrame.from_ndarray(np.zeros((1, 16000), dtype=np.float32), format="fltp", layout="mono")
    samples.sample_rate = 16000
    for packet in audio.encode(samples): output.mux(packet)
    for packet in audio.encode(): output.mux(packet)
print(f"Created real MP4 fixture: {path.name}")
