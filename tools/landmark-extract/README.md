# landmark-extract

데이터셋 영상(`docs/private/dataset/videos/*.mp4`)에서 MediaPipe PoseLandmarker(lite/full)로
랜드마크를 추출해 `docs/private/dataset/landmarks/<videoId>.<model>.json`으로 저장한다.

## 사용법
```
cd tools/landmark-extract
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
mkdir -p models   # pose_landmarker_{lite,full}.task 를 models/ 에 다운로드
.venv/bin/python extract.py            # 전체
.venv/bin/python extract.py front_fast # 이름 일부로 필터
```

모델 URL: `https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_{lite,full}/float16/latest/pose_landmarker_{lite,full}.task`

## 참고
- mediapipe 1.0.1은 macOS에서 `graph_service Check failed`로 크래시하므로 0.10.35로 고정.
- 타임스탬프(`t`, ms)는 컨테이너 PTS 기준(가변 fps 대응). 포즈 미검출 프레임은 `lm: null`.
- `lm`은 33개 `[x, y, z, visibility, presence]`, 소수 5자리 반올림.
