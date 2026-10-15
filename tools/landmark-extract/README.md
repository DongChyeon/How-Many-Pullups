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
- `lm`은 33개 `[x, y, z, visibility, presence]`, 소수 5자리 반올림. 정규화 이미지 좌표.
- `wlm`은 같은 형식의 **월드 랜드마크**(미터, 골반 중심 원점). 미검출 프레임은 `wlm: null`. D2는 몸통 요 ψ와 V-z 뷰 신호를 이 값으로 계산한다(`docs/spec-rep-detection.md` §4.3).
- `wlm`은 선택 필드다. 평가 CLI(`./gradlew :shared:core:evalLandmarks`)는 `wlm`이 없는 구 형식 덤프도 읽지만, 이때 ψ는 정규화 z로 대체 계산되어 측면 판정이 경고로만 낮아진다.
- 메타(`docs/private/dataset/meta/<videoId>.json`)에 `"excludeFromEval": true`가 있으면 평가 CLI는 그 영상을 건너뛰고 제외 목록(`excludeReason`)에만 표시한다.
