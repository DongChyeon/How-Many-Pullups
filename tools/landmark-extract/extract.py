"""MediaPipe PoseLandmarker(VIDEO 모드)로 데이터셋 영상의 랜드마크를 JSON으로 추출한다."""
import json
import sys
import time
from pathlib import Path

import cv2
import mediapipe as mp
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

ROOT = Path(__file__).resolve().parents[2]
VIDEOS = ROOT / "docs/private/dataset/videos"
OUT = ROOT / "docs/private/dataset/landmarks"
MODELS = Path(__file__).resolve().parent / "models"


def r5(v):
    return round(float(v), 5)


def extract(video: Path, model: str):
    options = vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(
            model_asset_path=str(MODELS / f"pose_landmarker_{model}.task"),
            delegate=mp_python.BaseOptions.Delegate.CPU,
        ),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
    )
    cap = cv2.VideoCapture(str(video))
    fps = cap.get(cv2.CAP_PROP_FPS)
    frames, width, height, last_t = [], 0, 0, -1
    with vision.PoseLandmarker.create_from_options(options) as lm:
        idx = 0
        while True:
            ok, bgr = cap.read()
            if not ok:
                break
            height, width = bgr.shape[:2]
            pos = cap.get(cv2.CAP_PROP_POS_MSEC)  # 컨테이너 PTS 기반
            t = int(round(pos)) if pos > 0 or idx == 0 else int(round(idx * 1000 / fps))
            if t <= last_t:  # VIDEO 모드는 단조 증가 타임스탬프 필요
                t = last_t + 1
            last_t = t
            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            res = lm.detect_for_video(mp.Image(mp.ImageFormat.SRGB, rgb), t)
            if res.pose_landmarks:
                pts = [[r5(p.x), r5(p.y), r5(p.z), r5(p.visibility), r5(p.presence)]
                       for p in res.pose_landmarks[0]]
            else:
                pts = None
            if res.pose_world_landmarks:  # 미터, 엉덩이 중심 원점
                wpts = [[r5(p.x), r5(p.y), r5(p.z), r5(p.visibility), r5(p.presence)]
                        for p in res.pose_world_landmarks[0]]
            else:
                wpts = None
            frames.append({"t": t, "lm": pts, "wlm": wpts})
            idx += 1
    cap.release()
    return {
        "video": video.stem, "width": width, "height": height, "fps": r5(fps),
        "model": f"pose_landmarker_{model}", "mediapipe": mp.__version__, "frames": frames,
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    videos = sorted(VIDEOS.glob("*.mp4"))
    if len(sys.argv) > 1:
        videos = [v for v in videos if any(a in v.stem for a in sys.argv[1:])]
    for v in videos:
        for model in ("lite", "full"):
            t0 = time.time()
            data = extract(v, model)
            n = len(data["frames"])
            det = sum(f["lm"] is not None for f in data["frames"])
            (OUT / f"{v.stem}.{model}.json").write_text(json.dumps(data, separators=(",", ":")))
            print(f"{v.stem} {model}: {n} frames, detect {det/max(n,1):.1%}, {time.time()-t0:.1f}s", flush=True)


if __name__ == "__main__":
    main()
