# D5 — 백엔드 & 비용 결정 (공개판)

- 문서 ID: D5 / 상태: 초안 v1 (2026-09-27)
- 범위: 리더보드 백엔드 선택, 무료 티어 비용 추정·게이트, 서버 권한 모델, 부정 방지, 신고/숨김, pause 대응, 개인정보·삭제. **§MVP**와 **§Phase 2**로 나뉜다.
- 관련 문서: `docs/PRD.md`(D1), `docs/spec-rep-detection.md`(D2, rep 타임라인 스키마·T_floor/T_rep_min 값의 원본), `docs/validation-protocol.md`(D6, 임계값 확정 절차)
- 운영 절차(프로젝트 식별자, 키 보관 위치, 수동 복구, keepalive 워크플로 상세, 운영자 검토·숨김 절차)는 비공개 문서 `docs/private/backend-ops.md`에만 둔다. 이 문서는 그 파일을 이름으로만 참조한다.
- 표기: **VERIFIED** = 1차 출처에서 직접 확인(URL + 접근일) / **UNVERIFIED** = 확인 못함, 구현 전 재확인 필요. 모든 외부 사실의 접근일은 **2026-09-27**.

## 결정 요약

| 항목 | 결정 |
|------|------|
| 백엔드 | **Supabase Free**, 결제 수단 등록 안 함 |
| MVP 리더보드 | all-time 보드 1개, **엄격 모드 기록만**, 모든 기록은 `UNVERIFIED`("미검증" 라벨). 영상 업로드·Storage·검수 큐 없음 |
| MVP 비용 게이트 | **G0 충족**: 최악 DB ≈ 61 MB(≈12%), Egress ≈ 0.24 GB(≈5%), Storage 0 |
| rep 타임라인 하드 상한 | **16 KB/세션**(§M4.6a). D2 `compact` 형식(rep당 ≈17 B) 기준 약 947 rep까지 수용 — rep 수만으로는 제출을 거부하지 않는다 |
| 제출 경로 | 서버 함수(RPC) 1개로만 제출. RLS가 테이블 직접 쓰기를 막음 |
| 부정 방지(MVP) | 엄격 모드 리더보드 제출에만 레이트 리밋, Play Integrity, 2단계 rep 시간 검사, 물리 일관성 거부, 이상 FLAG(노출 유지), rep 타임라인, 신고/숨김. **고정 rep 수 상한은 두지 않는다** |
| Phase 2 진입 트리거 | **월 신고 건수 > 20건** (단일 조건, 2026-09-27 사용자 확정) |
| Phase 2 | 영상 증빙 검증 시스템(Verified/전체 2탭, G1, P1–P7, R1, 해시 바인딩). 착수 시 G1 재평가 |

---

## 0. 외부 사실과 출처 (접근일 2026-09-27)

### 0.1 Supabase Free

| # | 항목 | 값 | 상태 | 출처 |
|---|------|----|------|------|
| S1 | DB / Storage / Egress | 500 MB / 1 GB / 5 GB(+ cached 5 GB) | VERIFIED | https://supabase.com/pricing |
| S2 | Auth | 50,000 MAU | VERIFIED | https://supabase.com/pricing |
| S3 | 비활성 pause / 활성 프로젝트 수 | 1주 비활성 시 pause / 활성 2개 | VERIFIED | https://supabase.com/pricing |
| S4 | 무엇이 "활동"으로 인정되는지 (REST 조회가 pause를 막는지) | 공식 문서에서 정의를 찾지 못함 | **UNVERIFIED** | — |
| S5 | DB 크기 초과 시 동작 | "Free Plan projects enter read-only mode when your database size exceeds 500 MB." | VERIFIED | https://supabase.com/docs/guides/platform/database-size |
| S6 | 한도 초과 시 일반 동작 | 초과 시 알림 → 유예 기간(grace period) → 이후 Fair Use Policy 적용. 제한 수단: 프로젝트 pause, DB 읽기 전용, 새 프로젝트 생성 차단, API 요청에 402 응답. 유예 기간을 한 번 쓴 뒤 다시 초과하면 두 번째 유예 없음 | VERIFIED | https://supabase.com/docs/guides/platform/billing-faq |
| S7 | 유예 기간 길이 | 문서에 기간 명시 없음 | **UNVERIFIED** | https://supabase.com/docs/guides/platform/billing-faq |
| S8 | pause된 프로젝트 복구 가능 기간 | "users have a 1-year window to restore the project … from within Supabase Studio". 기간이 지나면 백업 파일과 Storage 객체를 대시보드에서 내려받아 새 프로젝트나 로컬에 복원 | VERIFIED | https://supabase.com/docs/guides/platform/upgrading |
| S9 | Edge Functions 무료 할당량 | Free 500,000 호출/월, 초과 과금 없음(Free) | VERIFIED | https://supabase.com/docs/guides/functions/pricing |
| S10 | Supabase Cron(pg_cron) | SQL·DB 함수 실행과 HTTP 요청(예: Edge Function 호출)을 예약 실행할 수 있음. **Free 플랜 사용 가능 여부는 문서에 명시 없음** | 기능 VERIFIED / Free 가용성 **UNVERIFIED** | https://supabase.com/docs/guides/cron |
| S11 | Database Webhooks | 트리거 + `pg_net` 기반 비동기 HTTP 요청(INSERT/UPDATE/DELETE 시 JSON 전송). **Free 플랜 사용 가능 여부는 문서에 명시 없음** | 기능 VERIFIED / Free 가용성 **UNVERIFIED** | https://supabase.com/docs/guides/database/webhooks |
| S12 | 업로드 파일 크기 (Phase 2) | 전역 50 MB 상한, Free에서 상향 불가 | VERIFIED | https://supabase.com/docs/guides/storage/uploads/file-limits |
| S13 | 버킷 단위 제한 (Phase 2) | 버킷별 최대 파일 크기·허용 MIME 타입 설정 가능 | VERIFIED | https://supabase.com/docs/guides/storage/buckets/fundamentals |
| S14 | Free 플랜 Spend Cap | Spend Cap은 Pro 전용. Free에서는 과금되지 않음 | VERIFIED | https://supabase.com/docs/guides/platform/cost-control |

S10·S11은 M4 착수 전 실제 Free 프로젝트에서 확인한다(비공개판 체크리스트). 둘 다 쓸 수 없을 때의 폴백은 각 절에 적어 두었다.

### 0.2 Play Integrity API

| # | 항목 | 값 | 상태 | 출처 |
|---|------|----|------|------|
| I1 | 기본 할당량 | 전체 설치 합산 10,000 요청/일, 상향 신청 가능 | VERIFIED | https://developer.android.com/google/play/integrity/overview |
| I2 | 요청 유형 | Standard(수백 ms, warm-up 필요, 자주 호출하는 용도) / Classic(수 초, nonce) | VERIFIED | https://developer.android.com/google/play/integrity/overview |
| I3 | 서버 측 해독 경로 | 앱과 연결된 Google Cloud 프로젝트에 **서비스 계정**을 만들고, `playintegrity` 범위 액세스 토큰으로 `playintegrity.googleapis.com/v1/PACKAGE_NAME:decodeIntegrityToken`을 호출해 평문 판정(JSON)을 받는다. 같은 토큰을 여러 번 해독하면 판정이 비워진다(재사용 방지) | VERIFIED | https://developer.android.com/google/play/integrity/standard |
| I4 | 과금 | 1차 문서에 요금 언급 없음. 제3자 글은 "할당량 내 무료"라고 설명하지만 1차 출처가 아님 | **UNVERIFIED** | https://developer.android.com/google/play/integrity/overview |
| I5 | Supabase에서 해독 호출 가능성 | Edge Function(S9)에서 서비스 계정 키로 I3 엔드포인트를 호출하면 된다. 호출량은 월 최대 2,000건으로 S9 할당량의 0.4% | 경로 VERIFIED(I3 + S9) / 실제 동작 **UNVERIFIED**(M4에서 PoC) | 위 두 출처 |

### 0.3 GitHub Actions (keepalive·알림용)

| # | 항목 | 값 | 상태 | 출처 |
|---|------|----|------|------|
| G-A1 | 예약 워크플로 자동 비활성화 | "In a public repository, scheduled workflows are automatically disabled when no repository activity has occurred in 60 days." 공개 저장소를 fork하면 예약 워크플로는 기본 비활성 | VERIFIED | https://docs.github.com/en/actions/using-workflows/disabling-and-enabling-a-workflow |
| G-A2 | 예약 실행 특성 | 최소 간격 5분, 부하가 높을 때(매시 정각 등) 지연 가능, 기본 브랜치에서만 실행 | VERIFIED | https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows |
| G-A3 | 실패 알림 수신자 | 예약 워크플로 알림은 cron 문법을 마지막으로 수정한 사용자(또는 재활성화한 사용자)에게 간다. 메일 수신 여부는 개인 알림 설정에 따른다 | VERIFIED | https://docs.github.com/en/actions/concepts/workflows-and-actions/notifications-for-workflow-runs |
| G-A4 | 비공개 저장소의 무료 Actions 분량 | 이번 세션에서 확인 안 함 | **UNVERIFIED** | — |

### 0.4 기타

| 항목 | 값 | 상태 | 출처 |
|------|----|------|------|
| Google Play 계정 삭제 | 앱 안에서 계정을 만들 수 있으면 **인앱 삭제 경로 + 웹 삭제 요청 링크** 둘 다 필요, 연관 데이터 삭제 | VERIFIED | https://support.google.com/googleplay/android-developer/answer/13327111 |
| Data safety 세부 항목 | 수집 데이터 신고 의무. 세부 분류는 확인 안 함 | UNVERIFIED(세부) | https://support.google.com/googleplay/android-developer/answer/10787469 |
| Firebase Storage on Spark | 2026-02-03부터 Spark 프로젝트는 Storage 접근 불가 | VERIFIED | https://firebase.google.com/docs/storage/faqs-storage-changes-announced-sept-2024 |
| Firestore 무료 | 저장 1 GiB, 읽기 50,000/일, 쓰기 20,000/일 | VERIFIED | https://firebase.google.com/docs/firestore/quotas |

무료 티어 정책은 바뀔 수 있으므로 분기마다 이 표를 다시 확인하고 접근일을 갱신한다.

---

# §MVP

## M1. MVP 범위

| 구분 | MVP에 포함 | MVP에 없음 (§Phase 2) |
|------|-----------|------------------------|
| 보드 | all-time 1개, 엄격 모드 기록만, 모든 기록 `UNVERIFIED`("미검증" 라벨). 공유 카드·문구에도 "미검증" 표기 | Verified/전체 2탭, 공식 순위 |
| 서버 데이터 | 닉네임, 기록(개수·모드·일시), rep 메타데이터 타임라인(사용자당 현재 PB 1건), Play Integrity 판정 요약, 선택 텍스트 필드 `videoSha256` | 영상, 썸네일 |
| 영상 | **기기에만 보관**. 서버는 영상을 받지 않는다 | 증빙 업로드, 검수 큐, Storage, R1 |
| 연습 모드 | 로컬 저장만. 서버 제출 없음, 횟수 제한 없음 | — |

MVP는 **Supabase Storage를 쓰지 않고, 영상을 업로드하지 않으며, 검수 큐가 없다.** MVP에서 해시가 등장하는 곳은 제출 RPC의 선택 텍스트 필드 `videoSha256` 하나뿐이다(M4.7).

## M2. MVP 비용 추정 (MAU 1,000, Supabase Free, Storage 0)

### M2.1 가정 파라미터

| 기호 | 의미 | 설계값 | 최악값 |
|------|------|--------|--------|
| U | MAU | 1,000 | 1,000 |
| L | 리더보드 로그인 비율 | 50% → 500명 | 100% → 1,000명 |
| f | 엄격 PB 제출 빈도(인/월) | 1.5 | 2 |
| Nsub = U·L·f | 월 제출 건수 | **750** | **2,000** |
| r | PB 이력 행 크기(`videoSha256` 64 B, Integrity 판정 요약 포함) | 300 B | 300 B |
| t | 타임라인 JSON(서버 하드 상한, §M4.6a) | 16 KB | 16 KB |
| m | 누적 기간 | 24개월 | 24개월 |
| k | 인덱스·오버헤드 배수 | ×2 | ×2 |
| V | 보드 조회 수(월) | 12,000 | 24,000 (2배) |
| p | 조회 1회 응답(50행 × 200 B) | 10 KB | 10 KB |
| O | 운영자 타임라인 열람(신고·FLAG) | ≤ 100건 × 16 KB | ≤ 100건 × 16 KB |

- 타임라인은 **사용자별 현재 PB 1건만** 보존한다(PB 갱신 시 이전 타임라인 삭제). PB 이력 행은 보존한다.
- **타임라인 하드 상한을 16 KB로 둔다(§M4.6a).** D2(`docs/spec-rep-detection.md` §12.3)의 `compact` 형식(헤더 약 260 B + rep당 약 17 B)을 쓰면 16 KB 안에 약 947 rep이 들어간다 — 40+ rep 강자는 물론 500 rep까지도 여유 있게 보장한다. 이 절의 DB 예산은 사용자당 현재 PB 타임라인 행이 최악의 경우 이 하드 상한(16 KB)에 도달한다고 가정한 **보수적** 값이다.
- Play Integrity 토큰 원문은 검증이 끝나면 지운다(판정 요약만 행에 남김). 해독 경로를 못 쓰는 폴백에서는 원문을 **최근 30일분만** 보존한다(M4.2). 토큰 크기는 UNVERIFIED이므로 2 KB로 가정하면 2,000 × 2 KB = 4 MB(×2 = 8 MB)가 추가로 붙는다 → 최악 ≈ 69 MB(14%), G0 결론은 그대로다.
- 신고 행(≤ 수백 건 × 약 200 B)과 레이트 리밋 카운트(제출 행에서 계산)는 1 MB 미만이라 생략한다.

### M2.2 산식과 결과

- DB = k × (타임라인 + PB 이력) = k × (U·L·t + Nsub·r·m)
  - 설계: 2 × (500 × 16 KB + 750 × 300 B × 24) = 2 × (8 MB + 5.4 MB) = **26.8 MB ≈ 27 MB**
  - 최악: 2 × (1,000 × 16 KB + 2,000 × 300 B × 24) = 2 × (16 MB + 14.4 MB) = **60.8 MB ≈ 61 MB**
- Egress = V·p + O
  - 설계: 12,000 × 10 KB + 1.6 MB ≈ **0.12 GB**
  - 최악: 24,000 × 10 KB + 1.6 MB ≈ **0.24 GB**

| 자원 | 설계 | 최악(24개월 누적) | 한도 | 최악 비율 |
|------|------|-------------------|------|-----------|
| DB: 타임라인(사용자당 1건, 16 KB 하드 상한 가정) | 8 MB | 16 MB | — | — |
| DB: PB 이력 행 | 5.4 MB | 14.4 MB | — | — |
| **DB 합계(×2)** | ≈ 26.8 MB | **≈ 61 MB** | 500 MB | **≈ 12.2%** |
| Storage | 0 | 0 | 1 GB | 0% |
| Egress: 보드 조회 | 120 MB | 240 MB | — | — |
| Egress: 운영자 타임라인 열람 | < 2 MB | < 2 MB | — | — |
| **Egress 합계** | ≈ 0.12 GB | **≈ 0.24 GB** | 5 GB | **≈ 4.8%** |
| Auth MAU | 500 | 1,000 | 50,000 | 2% |
| Play Integrity | 750/30 ≈ 25/일 | 2,000/30 ≈ 67/일 | 10,000/일 | 0.67% |
| Edge Functions(Integrity 해독) | 750/월 | 2,000/월 | 500,000/월 | 0.4% |

검산 메모: 계획서의 "Egress ≈ 0.25 GB, 5%"는 0.24 GB(4.8%)를 올림한 값이다. 결론은 같다. 타임라인 하드 상한을 4 KB → 16 KB로 올리면서 DB 최악 비율은 ≈7.4% → ≈12.2%로 올라가지만 G0(c) 70% 한도 안에 그대로 든다.

### M2.3 규칙 G0 (MVP 리더보드 게이트)

> **G0:** (a) Supabase Free, 결제 수단 미등록. (b) Storage 미사용. (c) 최악 시나리오에서 DB·Egress가 각각 한도의 70% 이하. (d) RPC 전용 제출 + RLS로 직접 INSERT 차단 + `verificationStatus` 기본값 `UNVERIFIED`.

**결론: 최악 DB ≈ 12%, Egress ≈ 5%, Storage 0이므로 G0를 충족하며, 미검증 all-time 리더보드를 MVP(M4, 기능 플래그로 끌 수 있음)에 포함한다.**

G0가 깨지면(예: 정책 변경으로 한도 축소) 기능 플래그를 끄고 리더보드 없는 MVP(B3)로 출시한다. 로컬 기능은 영향이 없다.

### M2.4 한도 초과 대응 (S5·S6 기반)

- DB가 500 MB를 넘으면 읽기 전용이 된다(S5). 최악 추정이 61 MB라 여유가 크지만, 운영 대시보드에서 DB 크기를 월 1회 확인하고 **350 MB(70%)** 에 도달하면 오래된 PB 이력 행을 정리(사용자별 최근 N건만 보존)한다.
- 조직 한도 초과 시 유예 기간 뒤 402 응답·pause 등의 제한이 올 수 있고, 두 번째 유예는 없다(S6). 유예 기간 길이는 UNVERIFIED(S7). 초과 알림을 받으면 즉시 보드 기능 플래그를 끄고(원격 설정), 원인을 줄인 뒤 다시 켠다.
- 앱은 서버가 402/5xx를 반환하거나 연결되지 않아도 로컬 기능(카운팅·리포트·히스토리)이 그대로 동작해야 한다. 보드 화면만 "일시적으로 사용할 수 없음"을 보여 준다.

## M3. 서버 권한 모델 (검증 대비 day 1)

### M3.1 원칙

1. 클라이언트는 어떤 테이블에도 직접 INSERT/UPDATE/DELETE할 수 없다(RLS가 거부).
2. 제출은 `security definer` 서버 함수(RPC) **`submit_leaderboard_entry` 하나로만** 한다.
3. `verification_status` 컬럼은 day 1부터 존재하며 기본값은 `UNVERIFIED`다. RPC는 클라이언트가 보낸 상태 값을 **무시**한다(인자로 받지 않는다).
4. 공개 보드는 뷰로만 읽는다. 뷰는 `hidden = true`인 기록을 뺀다. 모든 기록에 "미검증" 라벨이 붙는다.
5. 운영자 작업(숨김/해제)은 `service_role`로만 수행한다. 서비스 키는 앱에 포함하지 않는다.

### M3.2 데이터 모델 (개략)

| 테이블/뷰 | 주요 컬럼 | 비고 |
|-----------|-----------|------|
| `profiles` | `user_id`(auth.uid), `nickname`, `created_at` | 닉네임 변경도 RPC로만 |
| `leaderboard_entries` | `id`, `user_id`, `reps`, `mode = 'STRICT'`, `session_started_at`, `session_duration_ms`, `app_version`, `pose_engine`, `verification_status`(기본 `UNVERIFIED`), `flagged`, `flag_reasons[]`, `hidden`, `hidden_reason`, `integrity_status`, `integrity_summary`, `video_sha256`(nullable, 64자 hex), `is_current_pb`, `created_at` | PB 이력 행. 약 300 B |
| `rep_timelines` | `user_id`(PK), `entry_id`, `timeline jsonb`(≤ 16 KB, §M4.6a) | 사용자당 1행(현재 PB) |
| `reports` | `reporter_id`, `entry_id`, `reason`, `created_at`, UNIQUE(`reporter_id`, `entry_id`) | 같은 기록은 사용자당 1회 |
| `integrity_tokens` | `entry_id`, `token`, `request_hash`, `created_at` | 검증 후 삭제. 폴백 시 30일 보존 |
| `public_leaderboard` (뷰) | 닉네임, reps, 일시, 라벨 | `hidden = false`만, 사용자별 현재 PB 1건, reps 내림차순 |

`rep_timelines.timeline`의 스키마는 D2(`docs/spec-rep-detection.md`)의 "rep 메타데이터 타임라인" 정의를 따른다: rep별 {시작/TOP/종료 타임스탬프(ms, 세션 상대), ROM 요약, 템포, 유효/노카운트 사유}, 세션 길이, 앱 버전, 포즈 엔진·모델.

### M3.3 RLS·RPC 예시 (설명용, 마이그레이션 파일 아님)

```sql
alter table leaderboard_entries enable row level security;
alter table rep_timelines      enable row level security;
alter table reports            enable row level security;

-- 쓰기 정책을 만들지 않는다 → anon/authenticated의 INSERT/UPDATE/DELETE는 모두 거부.
-- 읽기: 본인 행만(숨김 여부 확인용). 공개 보드는 뷰로 읽는다.
create policy entries_select_own on leaderboard_entries
  for select to authenticated using (user_id = auth.uid());

revoke insert, update, delete on leaderboard_entries, rep_timelines, reports
  from anon, authenticated;

create view public_leaderboard as
  select p.nickname, e.reps, e.created_at, 'UNVERIFIED' as label
  from leaderboard_entries e join profiles p using (user_id)
  where e.is_current_pb and not e.hidden
  order by e.reps desc, e.created_at asc;

-- 유일한 제출 경로. verification_status는 인자에 없다.
create function submit_leaderboard_entry(
  p_reps int, p_session_started_at timestamptz, p_session_duration_ms int,
  p_timeline jsonb, p_app_version text, p_pose_engine text,
  p_integrity_token text, p_video_sha256 text default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  -- 1) auth.uid() 확인 2) 레이트 리밋 3) 입력 형식 4) 물리 일관성 검사
  -- 5) PB 여부 6) INSERT(verification_status는 컬럼 기본값 'UNVERIFIED')
  -- 7) 이상 FLAG 판정 8) 이전 타임라인 교체 9) {status, reason_code} 반환
  ...
end $$;
revoke all on function submit_leaderboard_entry from public, anon;
grant execute on function submit_leaderboard_entry to authenticated;
```

### M3.4 RPC 처리 순서와 거부 사유 코드

| 순서 | 검사 | 실패 시 |
|------|------|---------|
| 1 | 로그인 사용자(`auth.uid()` 존재) | `AUTH_REQUIRED` |
| 2 | 레이트 리밋(M4.1) | `RATE_LIMITED` |
| 3 | 형식: reps ≥ 1, 타임라인 JSON ≤ 16 KB(§M4.6a), 필수 키 존재, `videoSha256`는 null 또는 `^[0-9a-f]{64}$`, Integrity 토큰 존재 | `INVALID_PAYLOAD`, `INTEGRITY_TOKEN_MISSING` |
| 4 | 물리 일관성(M4.3·M4.4) | `REP_TOO_FAST`, `DURATION_INCONSISTENT`, `TIMESTAMP_NON_MONOTONIC`, `REP_COUNT_MISMATCH`, `OUT_OF_PHYSICAL_RANGE` |
| 5 | 본인 현재 PB보다 큰지 | `NOT_PERSONAL_BEST` |
| 6 | 저장 + FLAG 판정(M4.5) + Integrity 검증 예약(M4.2) | — (`{status: "ACCEPTED", flagged: bool}` 반환) |

## M4. 부정 방지 (MVP)

### M4.1 레이트 리밋 — 엄격 모드 리더보드 제출에만

- 대상: `submit_leaderboard_entry` 호출 = **엄격 모드 리더보드 제출**뿐이다.
- 기본값: **사용자당 3건/일**(UTC 자정 리셋, 초기값). 형식 검사를 통과해 처리된 시도는 거부되더라도 1건으로 센다(검사 임계값을 반복 탐색하지 못하게). `AUTH_REQUIRED`·`RATE_LIMITED` 자체는 세지 않는다.
- 구현: 그날 해당 사용자의 제출 시도 수를 `leaderboard_entries`와 거부 기록(카운트용 경량 행)에서 센다.
- **연습 모드와 엄격 모드가 아닌 모든 세션에는 횟수 제한이 없다.** 연습 세션은 기기 로컬 DB에만 저장되고 서버를 호출하지 않으므로, 저장·재측정 횟수는 무제한이다(2026-09-27 사용자 결정). 엄격 모드 세션 자체(로컬 녹화·카운트·리포트)도 무제한이며, 제한은 서버 제출에만 걸린다.

### M4.2 Play Integrity

- 클라이언트는 제출 직전 **Standard 요청**으로 토큰을 받는다. `requestHash` = 제출 페이로드(정규화 JSON)의 SHA-256. 토큰을 RPC 인자로 보낸다.
- 검증 흐름(기본안): RPC가 토큰을 `integrity_tokens`에 저장 → DB 웹훅(S11) 또는 pg_cron(S10)이 Edge Function `verify-integrity`를 호출 → Edge Function이 서비스 계정으로 `decodeIntegrityToken`(I3) 호출 → 판정 요약을 `integrity_summary`에 기록하고 토큰 원문 삭제.
- 판정 처리:
  - 앱 인식 판정이 `PLAY_RECOGNIZED`가 아님, 또는 `requestHash` 불일치(조작·재사용 증거) → `hidden = true`, `hidden_reason = INTEGRITY_FAILED`, 본인에게 "숨김됨" 표시. 운영자가 검토 후 해제할 수 있다.
  - 기기 무결성 판정만 미달(루팅·커스텀 롬 등, 강자일 수도 있음) → `flagged = true`(노출 유지).
  - 해독 실패·서비스 장애 → `integrity_status = UNCHECKED`로 두고 재시도. 기록은 노출 유지.
- 폴백(S10·S11을 Free에서 못 쓰거나 I5 PoC 실패 시): **토큰 수집·기록만** 한다. 원문을 30일 보존하고, 운영자가 신고·FLAG 조사 때 수동으로 해독한다(절차는 비공개판).
- 과금은 UNVERIFIED(I4). 사용량은 최악 67/일로 할당량의 0.67%다.

### M4.3 2단계 rep 시간 검사

엄격 rep 소요 시간 = 타임라인의 rep 종료 − 시작(ms). 임계값은 D2와 **같은 값**을 공유하고, D6 데이터로 확정한다.

| 구간 | 조건(초기값) | 처리 |
|------|--------------|------|
| 거부 | 어느 rep이든 소요 시간 < **T_floor = 0.5초** (UNVERIFIED 초기값) | `REP_TOO_FAST`로 거부 |
| FLAG | T_floor ≤ 소요 시간 < **T_rep_min = 0.8초** (초기값) | `flagged = true`, `flag_reasons += FAST_REP`, **거부·숨김 없음, 정상 노출** |
| 정상 | ≥ T_rep_min | 통과 |

T_floor 근거: 엄격 rep은 완전 신전 → 턱이 바 위 → 완전 신전으로 약 0.5–0.6 m를 오르내리므로, 0.5초면 평균 속도가 2 m/s를 넘고 방향 전환이 두 번 들어간다. 사람이 반복할 수 있는 범위 밖으로 보고 초기값으로만 둔다. D6에서 측정한 최단 정상 rep보다 확실히 낮은지(거부 오탐 0건) 확인한 뒤 확정한다.

### M4.4 그 밖의 물리 일관성 거부

거부 대상은 센서 이상과 위조 서명뿐이다. **rep 수가 많다는 이유만으로 거부하거나 숨기지 않는다.**

| 검사 | 거부 조건 | 사유 코드 |
|------|-----------|-----------|
| 세션 길이 | reps × T_floor > 세션 길이 | `DURATION_INCONSISTENT` |
| 단조성 | rep 타임스탬프(시작 < TOP < 종료, 다음 rep 시작 ≥ 이전 종료)가 비단조. `NO_END` rep(§M4.6b)은 종료 시각이 없으므로 이 검사에서 제외한다 | `TIMESTAMP_NON_MONOTONIC` |
| 개수 일치 | 타임라인의 유효 rep 수(사유 코드 `COUNTED`이고 `NO_END` 플래그가 없는 항목 수, §M4.6b) ≠ 제출 `reps` | `REP_COUNT_MISMATCH` |
| 물리 범위 | ROM·템포 값이 D2가 정의한 물리 범위 밖(예: 음수 시간, ROM 비율 > 1.5). `NO_END` rep은 `durationMs`가 없으므로(D2 §12.3 `dE = −1`) 이 검사에서 제외한다 | `OUT_OF_PHYSICAL_RANGE` |

`NO_END` rep(§M4.6b)은 위 4개 검사 모두에서 제외 대상이다: 세션 길이·개수 일치 검사는 제출 `reps`에 애초에 포함되지 않은 rep을 보는 것이므로 자동으로 제외되고, 단조성·물리 범위 검사는 명시적으로 건너뛴다.

### M4.5 이상 FLAG (숨김 아님)

| 조건 | 처리 |
|------|------|
| 사용자의 **첫 업로드가 Top 10**에 진입 | `flagged = true`, `flag_reasons += FIRST_UPLOAD_TOP10` |
| **PB 급등**: 새 reps > 직전 PB + max(5, 0.3 × 직전 PB) (2026-09-27 사용자 확정) | `flagged = true`, `flag_reasons += PB_JUMP` |
| rep 시간 FLAG 구간(M4.3) | `flagged = true`, `flag_reasons += FAST_REP` |
| Integrity 기기 판정만 미달(M4.2) | `flagged = true`, `flag_reasons += DEVICE_INTEGRITY` |

- 예: 직전 PB 10 → 급등 기준 10 + max(5, 3) = 15 초과(16회부터 FLAG). 직전 PB 30 → 30 + max(5, 9) = 39 초과.
- **FLAG된 기록은 운영자가 숨기기 전까지 계속 노출된다.**
- 운영자 알림: 기본안은 **일 1회 GitHub Actions 점검 작업**이 운영 뷰(`ops_flagged_unreviewed`)의 미검토 건수를 조회하고, 1건 이상이면 작업을 실패 처리해 GitHub 실패 알림 메일(G-A3)을 받는 방식이다(무료, 추가 서비스 불필요). DB 웹훅 → Edge Function → 외부 메일 발송은 메일 서비스의 무료 가용성이 UNVERIFIED라 채택하지 않는다. 최후 폴백: 운영자가 대시보드의 flagged 뷰를 일 1회 확인.

### M4.6 rep 메타데이터 타임라인 저장

- 제출마다 타임라인 JSON(≤ 16 KB, §M4.6a)을 받아 검사에 쓰고, 통과하면 `rep_timelines`의 사용자 행을 **교체**한다(사용자당 현재 PB 1건만 보존).
- 운영자는 영상 없이 타임라인(rep별 시각, 템포 분포, 세션 길이, 앱 버전, 포즈 엔진)으로 개연성을 판단한다.

### M4.6a 타임라인 크기 상한 (D2 §12.3·§16 이슈 4 확정)

- **서버 하드 상한 = 16 KB.** D2가 열어 둔 이슈("서버 하드 상한은 D5가 정한다")를 이 값으로 확정한다.
- 근거(D2 §12.3의 rep당 크기): `compact` 형식(헤더 약 260 B + rep당 약 17 B)을 기준으로 16 KB ≈ (16,384 − 260) / 17 ≈ **947 rep**까지 담을 수 있다. D2가 이미 정의한 `full → compact → gz` 자동 선택 규칙(4 KB 상한 기준으로 설계됨)은 상한이 16 KB로 늘어도 그대로 유효하며, `gz`까지 가지 않고 `compact`만으로 40+ rep은 물론 500 rep 이상까지 여유 있게 수용한다.
- **rep 수만으로는 제출을 거부하지 않는다(강자 불이익 금지 원칙, D1·D2·D5 공통).** 40회 이상 반복하는 진짜 강자의 세션이 타임라인 크기 때문에 거부되는 일은 없어야 한다는 요구를 16 KB 상한이 충족한다.
- 16 KB를 넘는 경우(예: `gz` 압축률이 예상보다 낮아 947 rep을 넘는 극단적 세션)는 `INVALID_PAYLOAD`로 거부하지만, D6/M4 실측에서 이 상한이 실제로 걸리는 사례가 나오면(부록 A) 상한을 재검토한다.
- DB 비용 영향은 M2.1·M2.2에서 재계산했다: 사용자당 현재 PB 타임라인 행이 최악 16 KB라고 가정해도 DB 최악 비율은 ≈12%로 G0(c) 70% 한도 안에 든다.

### M4.6b `NO_END` rep 처리 (D2 §9·§16 이슈 2 확정)

세션이 마지막 rep의 신전 확정 전에 종료되면(예: 사용자가 도중에 바를 놓고 세션을 끝냄) 그 rep은 `endMs = null`, D2 타임라인에서 `dE = −1`과 `NO_END` 플래그(§12.1 비트 128)로 기록된다. D2가 열어 둔 이슈를 다음과 같이 확정한다.

- **`NO_END` 플래그가 있는 rep은 제출 `reps`(리더보드에 오르는 반복 수)에 포함하지 않는다.** 클라이언트는 이 rep을 제외하고 `reps`를 계산해 제출한다.
- **물리 일관성 검사(M4.4)에서도 제외한다**: 단조성·물리 범위 검사는 이 rep을 건너뛰고, 세션 길이·개수 일치 검사는 애초에 `reps`에 포함되지 않으므로 자연히 제외된다.
- 타임라인 자체에는 `NO_END` rep을 그대로 남긴다(운영자가 세션 종료 정황을 볼 수 있도록). `REP_COUNT_MISMATCH`의 "유효 rep 수"는 사유 코드 `COUNTED`이고 `NO_END` 플래그가 없는 항목만 센다(M4.4 갱신).
- 이 규칙은 강자 불이익 금지 원칙과 충돌하지 않는다: `NO_END` rep은 카운트되지 않은 반복이 아니라 "판정을 끝까지 못 낸 반복"이므로 애초에 리더보드 점수에서 제외하는 것이 안전한 기본값이다.

### M4.7 선택 필드 `videoSha256`

- 엄격 세션 영상을 로컬에 보관하는 경우, 앱은 세션 종료 직후 **원본 파일**의 SHA-256을 로컬 DB에 sessionId와 함께 기록하고, 제출 시 `videoSha256`(64자 hex 텍스트)으로 보낸다. **영상 자체는 업로드하지 않는다.**
- 서버는 형식만 검사해 저장한다(행당 64 B, 비용 영향 없음). 이 값은 Phase 2에서 과거 PB 증빙의 강한 바인딩에 쓰인다(§Phase 2 P7).

## M5. 신고·숨김

- 보드 항목마다 신고 버튼. `report_entry(entry_id, reason)` RPC로만 제출하며, 같은 기록은 사용자당 1회(UNIQUE). 본인 기록은 신고 불가.
- 신고 사유 enum(초안): `IMPOSSIBLE_RECORD`, `OFFENSIVE_NICKNAME`, `OTHER`.
- 운영자는 기록을 숨기거나 해제하고, 숨길 때 사유(`hidden_reason`)와 시각을 기록한다.
- 숨김 기록은 공개 뷰에서 빠지고, 본인에게는 "숨김됨"과 사유 범주가 표시된다.
- 운영자 검토·숨김의 구체 절차는 비공개판에 둔다.

## M6. Supabase pause 대응

- 위험: Free 프로젝트는 1주 비활성 시 pause된다(S3). pause 중에는 보드만 멈추고 로컬 기능은 영향 없다.
- 대응: **GitHub Actions 예약 워크플로가 ≤ 3일마다**(기본안: 매일 1회) 경량 REST 조회(공개 보드 뷰 1행 SELECT)로 프로젝트를 깨운다. 실패하면 워크플로가 실패로 끝나 GitHub 알림 메일(G-A3)이 간다.
- REST 조회가 Supabase의 "활동"으로 인정되는지는 UNVERIFIED(S4) → 운영자가 **월 1회** 대시보드에서 프로젝트 상태를 직접 확인한다.
- 공개 저장소의 예약 워크플로는 저장소 활동이 60일 없으면 자동 비활성화된다(G-A1). 따라서 keepalive 워크플로는 **비공개 운영 저장소**에 두는 것을 기본안으로 한다(비공개 저장소는 G-A1 문구의 대상이 아님. 무료 분량은 G-A4 UNVERIFIED — 매일 1분 미만 작업이면 월 약 30분). 공개 저장소에 둘 경우 60일 안에 커밋이 있어야 하며, 월 1회 수동 확인에 워크플로 활성 상태 확인을 넣는다.
- pause되었을 때: Studio에서 1년 안에 버튼 한 번으로 복구할 수 있다(S8). 1년이 지나면 백업 파일을 내려받아 새 프로젝트에 복원한다. 상세 절차와 식별자는 비공개판에 둔다.

## M7. 개인정보·계정 삭제 (MVP)

### M7.1 계정 삭제 경로

인앱(설정 → 계정 삭제) + 로그인 없이 접근 가능한 웹 삭제 요청 페이지, 둘 다 제공한다(Google Play 정책, VERIFIED). 삭제는 `delete_my_account()` RPC(인앱) 또는 운영자 처리(웹 요청, 본인 확인 후)로 수행한다.

### M7.2 계정 삭제 시 삭제 대상 — MVP

| 데이터 | 위치 | 처리 |
|--------|------|------|
| 인증 계정(로그인 제공자 식별자) | Supabase Auth | 삭제 |
| 프로필(닉네임) | `profiles` | 삭제 |
| 리더보드 기록 전부(PB 이력 포함, `videoSha256`·Integrity 판정 요약 포함) | `leaderboard_entries` | 삭제 |
| rep 메타데이터 타임라인 | `rep_timelines` | 삭제 |
| 본인이 한 신고 이력 | `reports`(reporter_id) | 삭제 |
| 본인 기록에 대한 타인의 신고 | `reports`(entry_id) | 기록 삭제와 함께 연쇄 삭제 |
| Integrity 토큰 원문(남아 있다면) | `integrity_tokens` | 삭제 |
| 레이트 리밋 카운트용 행 | 카운트 테이블 | 삭제 |
| 기기 로컬 데이터(세션, 영상, 해시) | 기기 | 서버 삭제 대상 아님. 앱 삭제 또는 인앱 "로컬 데이터 전체 삭제"로 사용자가 지움 — 삭제 화면에서 안내 |

- 삭제 후 Phase 2 진입 트리거용 월 신고 집계는 숫자만(개인 식별 없이) 남길 수 있다.
- 백업에 남은 데이터의 보존 기간은 Supabase 백업 정책을 따른다(Free 백업 보존 기간은 UNVERIFIED → 개인정보 처리방침에 "백업에서는 최대 N일 후 삭제"로 적기 전에 확인).

### M7.3 Data safety 신고 매핑 (MVP 초안)

| 데이터 유형 | 수집 | 목적 | 공개 여부 |
|-------------|------|------|-----------|
| 계정 식별자(로그인 제공자 ID) | 예 | 계정 관리, 부정 방지 | 비공개 |
| 닉네임 | 예 | 리더보드 표시 | 공개 |
| 리더보드 기록(개수·모드·일시) | 예 | 리더보드 | 공개 |
| rep 메타데이터 타임라인 | 예 | 부정 방지(운영자 판단) | 비공개 |
| 기기 무결성 판정(Play Integrity) | 예 | 부정 방지 | 비공개 |
| 영상 SHA-256 해시(선택) | 선택 | Phase 2 증빙 바인딩 | 비공개 |
| 영상 | **아니요** (MVP) | — | — |

세부 분류명은 UNVERIFIED — 제출 전 Play Console 양식과 대조한다.

## M8. Phase 2 진입 트리거 모니터링

- **트리거: 월 신고 건수 > 20건** (UTC 달력 월, `reports.created_at` 기준, 중복 신고는 UNIQUE로 이미 제거). 단일 조건이다(2026-09-27 사용자 확정).
- 방법: 운영 뷰 `ops_monthly_reports`(월별 신고 수)를 일 1회 점검 작업(M4.5와 같은 GitHub Actions 작업)이 조회하고, 이번 달 누적이 20건을 넘으면 실패 알림으로 운영자에게 알린다. 운영자는 월말에 결과를 기록한다.
- 트리거가 충족되면 §Phase 2 착수를 검토하고, 그 시점 수치로 G1을 재평가한다.

```sql
create view ops_monthly_reports as
  select date_trunc('month', created_at) as month, count(*) as reports
  from reports group by 1 order by 1 desc;
-- service_role 전용 (anon/authenticated에 select 권한 없음)
```

## M9. 부정 테스트 (MVP)

각 테스트는 M4 구현 시 서버 통합 테스트로 만든다. "노출"은 `public_leaderboard` 뷰에 나타남을 뜻한다.

| # | 시나리오 | 기대 결과 |
|---|----------|-----------|
| N1 | anon 키로 `leaderboard_entries`에 직접 INSERT | RLS/권한 오류로 거부, 행 0개 추가 |
| N1b | authenticated 사용자 JWT로 직접 INSERT/UPDATE(`hidden = false` 등) | 거부 |
| N2 | RPC 페이로드에 `verificationStatus = "APPROVED"`를 추가해 전송 | 추가 필드는 무시(또는 `INVALID_PAYLOAD`), 저장된 행은 `UNVERIFIED` |
| N3 | 한 rep의 소요 시간이 0.4초(< T_floor)인 타임라인 | `REP_TOO_FAST`로 거부, 행 없음 |
| N3b | 모든 rep이 0.55–0.79초(T_floor 이상 T_rep_min 미만)인 빠른 엄격 세트, 그리고 0.8–0.85초(T_rep_min 부근)인 세트 | **거부되지 않음**, 정상 노출, 전자는 `flagged = true`(FAST_REP), 후자는 FLAG 없음 |
| N4 | rep 타임스탬프가 비단조(3번째 rep 시작 < 2번째 rep 종료) | `TIMESTAMP_NON_MONOTONIC`로 거부 |
| N4b | reps = 30, 세션 길이 10초(30 × 0.5초 > 10초) | `DURATION_INCONSISTENT`로 거부 |
| N4c | 타임라인 유효 rep 25개, 제출 reps 30 | `REP_COUNT_MISMATCH`로 거부 |
| N5 | **45회**, 모든 rep 1.5–2.5초, 단조, 세션 길이 일치하는 고반복 정상 타임라인 | **거부·숨김되지 않고 정상 노출**(강자 불이익 없음). 첫 업로드 Top 10이면 FLAG만 붙고 노출 유지 |
| N6 | 신규 사용자의 첫 업로드가 Top 10 진입 | `flagged = true`(FIRST_UPLOAD_TOP10), **노출 유지** |
| N6b | 직전 PB 10 → 새 기록 16 | `flagged = true`(PB_JUMP), 노출 유지. 15는 FLAG 없음 |
| N7 | 같은 사용자가 같은 UTC 날에 엄격 모드 리더보드 제출 4번째 시도 | `RATE_LIMITED`로 거부 |
| N8 | 같은 날 연습 모드 세션 50회 저장·재측정(로컬), 엄격 모드 로컬 세션 10회 | 모두 저장됨. 서버 호출 없음, 레이트 리밋 미적용 |
| N9 | 같은 사용자가 같은 기록을 두 번 신고 | 두 번째는 거부(UNIQUE), 신고 수 1 |
| N10 | 운영자가 숨긴 기록 | 공개 뷰에서 빠짐, 본인 조회 시 "숨김됨" |
| N11 | Integrity 토큰 없이 제출 | `INTEGRITY_TOKEN_MISSING`로 거부 |
| N12 | `videoSha256 = "xyz"`(형식 오류) | `INVALID_PAYLOAD`로 거부. null이면 정상 |
| N13 | 본인 현재 PB 이하 기록 제출 | `NOT_PERSONAL_BEST`로 거부 |
| N14 | 타임라인에 `NO_END` 플래그 rep 1개(세션 종료 시 미완료) + 정상 유효 rep 29개, 제출 `reps = 29`(`NO_END` rep 제외하고 계산) | 거부되지 않음, 정상 노출. `reps`는 29로 저장 |
| N15 | N14와 같은 타임라인이지만 제출 `reps = 30`(`NO_END` rep까지 포함해 계산) | `REP_COUNT_MISMATCH`로 거부 |
| N16 | `NO_END` rep의 `durationMs`/`dE`가 비정상 값(예: 세션 길이보다 긴 값)이어도 나머지 rep은 모두 정상 | `OUT_OF_PHYSICAL_RANGE`·`TIMESTAMP_NON_MONOTONIC` 모두 이 rep에는 적용되지 않아 거부되지 않음, 정상 노출 |
| N17 | 타임라인 JSON 크기 16 KB 이하(예: `compact` 형식 900 rep 상당) | 거부되지 않음(rep 수만으로 거부하지 않는다는 원칙 확인) |
| N18 | 타임라인 JSON 크기 > 16 KB | `INVALID_PAYLOAD`로 거부 |

## M10. 백엔드 선택 근거

| 옵션 | 장점 | 단점 | 판단 |
|------|------|------|------|
| **B1. Supabase Free** | Postgres RPC(security definer)·RLS·트리거로 서버 권한 검사(순위 기반 FLAG 포함)를 무료로 구현. 최악 DB ≈ 12%, Egress ≈ 5%. Phase 2에서 결제 수단 없이 Storage 1 GB 사용 가능 → 백엔드 이전 없이 검증 시스템 추가 | 1주 비활성 pause(M6), pg_cron·웹훅 Free 가용성 UNVERIFIED | **채택** |
| B2. Firebase Spark | Firestore/Auth 한도 넉넉, GitLive SDK stable, Play Integrity와 같은 생태계 | 순위 기반 FLAG 같은 복합 서버 검사는 Security Rules로 한계, Cloud Functions는 Blaze 필요로 알려짐(UNVERIFIED). **Phase 2 Storage는 Spark에서 불가**(2026-02-03부터, VERIFIED) → Phase 2에서 결제 수단 등록이나 백엔드 이전이 강제됨 | 기각 |
| B3. 리더보드 없음 | 비용·운영 부담 0 | 사용자 승인 MVP 범위와 불일치 | G0 불충족 시 폴백 |

---

# §Phase 2 — 검증 시스템

Phase 2는 **진입 트리거(월 신고 > 20건)** 가 충족되면 착수를 검토한다. 아래 설계는 합의된 iteration 3 설계를 그대로 옮긴 것이며, 착수 시 그 시점 수치로 G1을 재평가한다.

## P0. 보드 구조 — Verified / 전체 2탭

| 탭 | 내용 |
|----|------|
| **Verified (기본 탭)** | `APPROVED` 기록만, ✓ 뱃지와 순위 번호. **공식 순위는 이 탭뿐**이며, 공유 카드·푸시의 "순위" 문구도 이 탭 기준 |
| 전체 | 승인 여부와 무관한 모든 기록(숨김 제외). 미승인 기록은 "미검증" 라벨 |

- 증빙 요청 대상(P4)의 "Top N"은 Verified 탭 기준이다: 해당 기록이 승인된다고 가정할 때 Verified 탭 순위가 N 이내.
- 신고·숨김·물리 일관성 검사·이상 FLAG는 MVP 그대로 두 탭 모두에 적용한다.
- 조정 메모: 원래 P7은 단일 보드에서 "순위 > N 구간은 `NOT_REQUIRED`도 노출"을 허용했다. 사용자 결정(Verified 탭 = `APPROVED`만)에 따라 이 부분은 **전체 탭에서 "미검증" 라벨로 노출**하는 것으로 대체한다. D1과 표현을 맞춘다.

## P1. 설계 전제 (G1의 일부로 강제)

- **P1 하드 업로드 상한 10 MB/클립 (Phase 2 신규 세션).** 클라이언트가 `비트레이트 = min(1.5 Mbps, 10 MB × 8 / 길이)`로 재인코딩한다(60초 → 1.33 Mbps, 120초 → 0.67 Mbps). 120초를 넘는 세트는 증빙 불가(수동 문의). 계산된 비트레이트가 1 Mbps 미만(= 80초 초과)이면 480p로 낮춰 인코딩한다. 서버는 증빙 버킷의 file size limit = 10 MB, MIME = `video/mp4`로 이중 강제한다(S13).
- **P1-H 해시 대상·시점.** 엄격 세션은 종료 직후 "증빙 파일"을 기기에 확정하고, 그 파일의 SHA-256을 sessionId와 함께 로컬 DB에 기록한다. 증빙 호환 프리셋(720p/30fps, 1.0 Mbps)으로 녹화한 원본이 10 MB 이하(≈ 80초 이하)면 원본이 곧 증빙 파일이고, 넘으면 P1 규칙으로 한 번 재인코딩한 파일이 증빙 파일이다. 서버·검수 도구는 **업로드 파일 해시 = 제출 해시**만 대조한다. 원본·업로드 이중 해시(옵션 b)는 원본 삭제 시 검증 가치가 없고 매칭 규칙이 복잡해 기각.
- **P2 Phase 2 보드 = all-time 1개**(Verified 탭 기준). 주간 보드는 매주 Top N 전원이 증빙 대상이 되어 제외(반례 3).
- **P3 N은 모집단 비례:** `N = min(50, ceil(0.1 × 최근 30일 업로더 수))`. 업로더 300명 → N = 30. N은 주 1회만 재계산하고 1회 증가폭 ≤ +5.
- **P4 증빙 대상:** "Top N 신규 진입 **또는** Top N 내 사용자의 PB 갱신"(2026-09-27 사용자 승인). 전 사용자 PB를 증빙 대상으로 하면 G1이 깨진다(반례 2).
- **P5 검수 유입 구조 상한:** (i) 주간 신규 접수 ≤ 25건(재제출 포함, 월요일 00:00 UTC 리셋), (ii) 동시 대기(`PENDING`) ≤ 25건. 초과 시 서버가 업로드를 거부하고 기록은 `PROOF_QUEUED`("증빙 대기 / 순위 비노출")로 남는다. 증빙 파일은 기기에 보관된 채 다음 접수 창에서 선착순(`EXPIRED_OPERATOR` 재제출 우선) 업로드한다. 검수 부하는 수요와 무관하게 ≤ 25건/주.
- **P6 보관정책 R1:** 판정 즉시 원본 삭제, 최대 대기 7일. 영구 보존은 SHA-256 해시 + 썸네일(320px JPEG ≤ 50 KB, 검수자 전용, 비공개).
- **P7 순위 표류 차단:** Verified 탭은 **조회 시점에** 현재 순위·현재 N으로 계산하고 `APPROVED`만 노출한다. 제출 당시 `NOT_REQUIRED`였던 기록이 N 증가나 상위 기록 삭제로 Top N에 들어오면, 트리거 또는 주간 배치가 `PROOF_REQUESTED`로 바꾸고 사용자에게 증빙을 요청한다. 승인 전에는 Verified 탭에 나오지 않는다.

## P2. 비용 추정 (MAU 1,000, Supabase Free)

### P2.1 가정 파라미터

- A1: MAU 1,000, 월 4세션/인 → 4,000세션(로컬, 서버 비용 0).
- A2: 리더보드 로그인 30% = 300명, 엄격 PB 월 1.5회/인 → **업로드 450건/월**(≈ 104건/주). 상한 시나리오 1,000건/월. (iteration 3 값 그대로. MVP 추정은 50%를 쓰므로 차이는 P4.3 민감도에서 다룬다.)
- A3: 증빙 **수요**(P2·P3·P4 적용, all-time Top 30).
  - 정상상태: 신규 진입 ≈ 10 + Top 30 내 PB ≈ 30 = **40건/월**, 재제출 20% 포함 **48건/월**(≈ 11건/주).
  - 콜드스타트(첫 달): 무작위 순서로 도착하는 n개 기록 중 Top k 진입 기대 횟수 ≈ k × (1 + ln(n/k)). n ≈ 300, k = 30 → 30 × (1 + ln 10) = 30 × 3.303 ≈ **99건** + Top 30 내 PB 갱신 ≈ 45건 = **144건**, 재제출 20% 포함 **≈ 173건/월**(≈ 40건/주).
  - N 증가(P3, +5/주) 시 추가 진입 ≤ 5건/주.
- A4: 보드 조회 12,000회/월 × 50행 × 200 B ≈ 10 KB/회.

### P2.2 처리량과 적체

- P5 적용 처리량: 25건/주 × 4.33주 ≈ **108건/월**(수요와 무관한 상한).
- 콜드스타트 초과분: 173 − 108 ≈ **65건**이 `PROOF_QUEUED`로 이월. 정상상태 여유 108 − 48 = 60건/월이므로 **약 1–2개월 안에 소진**. 이월 중 해당 기록은 Verified 탭에 나오지 않는다(P7). PRD에 대기 지연 고지 항목으로 둔다.

### P2.3 계산 (P5 상한 기준 최악)

| 자원 | 정상상태 | 최악(상한 포화) | 한도 | 최악 비율 |
|------|----------|-----------------|------|-----------|
| DB | 450 × 1 KB ≈ 0.5 MB/월 | 1 MB/월 | 500 MB | < 1% |
| Storage: 대기 영상 | 48 × 7/30 × 10 MB ≈ 112 MB | 대기 상한 25 × 10 MB = 250 MB | — | — |
| Storage: 썸네일(24개월 누적) | 48 × 24 × 50 KB ≈ 58 MB | 108 × 24 × 50 KB ≈ 130 MB | — | — |
| **Storage 합계** | ≈ 170 MB | **≈ 380 MB** | 1 GB | **38%** |
| Egress: 검수 시청(재제출 포함) | 48 × 10 MB = 0.48 GB | 108 × 10 MB ≈ 1.08 GB | — | — |
| Egress: 보드 조회 | 120 MB | 240 MB(조회 2배) | — | — |
| **Egress 합계** | ≈ 0.6 GB | **≈ 1.32 GB** | 5 GB | **26%** |
| 검수 부하 | ≈ 11건/주 | ≤ 25건/주(구조적 상한) | G1(f) 25건/주 | 100%(상한 = 게이트) |
| Auth MAU | ≤ 300 | 1,000 | 50,000 | 2% |

**과거 PB 16 MB 시나리오**(대기 25건 전부 16 MB 가정, 2.2B): Storage 25 × 16 MB + 130 MB = **530 MB(53%)**, Egress 108 × 16 MB + 240 MB = 1.73 + 0.24 ≈ **1.97 GB(39%)**. 여전히 70% 이하.

검산 메모: 38%·26%·53%·39% 모두 재계산으로 일치(1.32/5 = 26.4%, 1.968/5 = 39.4%).

## P3. 반례 3개 (G1이 깨지는 경우)

1. **P1 없음**: 최대 클립 90초 × 1.5 Mbps ≈ 16.9 MB, iteration 1 최악 250건/월, 대기 7일 → 대기 Storage 250 × 7/30 × 16.9 MB ≈ 985 MB(**≈ 98%**), Egress 250 × 16.9 MB + 0.24 GB ≈ 4.46 GB(**≈ 89%**) → **불합격**.
2. **P4 원문 해석**(전 사용자 PB 증빙): 수요 450건/월(재제출 포함 ≈ 540) vs 처리량 108건/월 → 적체가 월 ≈ 430건씩 **무한 증가**, 상위권 기록이 사실상 영구 비노출 → G1(i) 위반, **불합격**. (P5가 없다면 104건/주로 G1(f)의 약 4배, 대기 Storage 450 × 7/30 × 10 MB ≈ 1.05 GB로 한도 초과.)
3. **주간 보드 포함**(증빙 필수): Top 30 × 4.33주 ≈ 130건/월 추가 → 정상상태 수요 ≈ 178건/월 > 처리량 108 → G1(i) 위반, **불합격**.

## P4. 규칙 G1 (Phase 2 착수 게이트)

> **G1:** 검증 시스템은 다음을 **모두** 만족할 때 착수한다.
> (a) 백엔드 = Supabase Free, 결제 수단 미등록.
> (b) 증빙 영상·썸네일은 비공개(검수자만 열람).
> (c) 보관정책 R1(판정 즉시 원본 삭제, 최대 대기 7일, 해시 + 썸네일만 영구).
> (d) 하드 업로드 상한 10 MB/클립, 클라이언트 재인코딩 + 버킷 file size limit으로 이중 강제(P1), 주간 신규 접수 ≤ 25 + 동시 대기 ≤ 25(P5), 480p 강등 규칙, 해시 대상 = 업로드 증빙 파일(P1-H); MVP 과거 PB는 원본 해시 대조·16 MB 상한(P7절).
> (e) 최악 시나리오에서 Storage·Egress 각 한도의 70% 이하.
> (f) 검수 부하 ≤ 25건/주(1인 운영) — P5로 구조적으로 강제.
> (g) Phase 2 보드는 all-time 1개, N = min(50, ceil(0.1 × 30일 업로더))(P2, P3).
> (h) 증빙 대상 = "Top N 진입 또는 Top N 내 PB 갱신"(P4) — 2026-09-27 사용자 승인.
> (i) 정상상태 증빙 수요 ≤ 처리량(108건/월)의 70%. 현 추정 48/108 = 44%.
> (j) 공개 보드는 조회 시점 계산, Verified 탭 Top N에는 `APPROVED`만 노출(P7).
> 하나라도 깨지면 검증 시스템은 보류하고 MVP 미검증 보드 + 신고/숨김 체제를 유지한다.

### P4.1 결론 (iteration 3 계산 기준)

최악 Storage ≈ 380 MB(38%, 과거 PB 16 MB 시나리오 53%), Egress ≈ 1.32 GB(26%, 같은 시나리오 39%), 검수 ≤ 25건/주(구조적), 정상상태 수요/처리량 44% → **G1 충족 가능**. 대가는 콜드스타트 기간(첫 1–2개월)의 증빙 대기 지연이다.

### P4.2 재평가 트리거

주간 접수 상한(25) 4주 연속 포화, `PROOF_QUEUED` 적체 > 100건, Storage 또는 Egress 70% 도달, 30일 업로더 > 500명.

### P4.3 민감도 메모 (검산 중 발견)

N은 30일 업로더 수에 비례한다. MVP 추정처럼 로그인 비율 50%(업로더 약 500명)면 N = min(50, 50) = **50**이 된다. 수요가 N에 비례한다고 단순 가정하면 신규 진입 ≈ 17 + Top 50 내 PB ≈ 50 = 67건, 재제출 포함 ≈ 80건/월 → 80/108 ≈ **74%로 G1(i)(70%)을 넘는다**. 따라서 Phase 2 착수 시 실제 30일 업로더 수로 N과 A3를 다시 계산하고, 70%를 넘으면 (1) N 상한을 낮추거나(예: min(40, …)), (2) P4의 "Top N 내 PB 갱신"을 "순위가 오른 PB 갱신"으로 좁히는 안을 사용자에게 올린다. Storage·Egress는 P5 상한 때문에 N과 무관하다.

## P5. 증빙 상태와 전이

| 상태 | 뜻 | Verified 탭 | 전체 탭 |
|------|----|-------------|---------|
| `UNVERIFIED` | MVP 시기 기록(Phase 2 이전) | 비노출 | "미검증" |
| `NOT_REQUIRED` | 증빙 대상 아님(Top N 밖) | 비노출 | "미검증" |
| `PROOF_REQUESTED` | 증빙 요청됨(P4/P7) | 비노출 | "증빙 대기" |
| `PROOF_QUEUED` | P5 상한으로 접수 대기 | 비노출 | "증빙 대기" |
| `PENDING` | 업로드 완료, 검수 대기(≤ 7일) | 비노출 | "검수 중" |
| `APPROVED` | 승인 | ✓ 순위 | ✓ |
| `REJECTED` | 반려 | 비노출 | "미검증" |
| `EXPIRED_OPERATOR` | 7일 내 운영자 미판정 | 비노출 | "증빙 대기" |
| `EXPIRED_USER` | 사용자 미업로드·무응답 | 비노출 | "미검증" |

전이 규칙:
- 모든 전이는 서버 함수·트리거·배치만 수행한다. 클라이언트는 상태를 정하지 못한다.
- Phase 2 개시 시 기존 `UNVERIFIED` 기록은 증빙 제출로만 Verified 탭에 편입된다. Top N에 해당하면 `PROOF_REQUESTED`로 전환.
- 새 제출: Top N 해당 → `PROOF_REQUESTED`, 아니면 `NOT_REQUIRED`.
- `PROOF_REQUESTED` → 업로드 수락 시 `PENDING`, P5 상한 초과 시 `PROOF_QUEUED`.
- `PENDING` → 판정 시 `APPROVED` / `REJECTED`, 7일 미판정 시 `EXPIRED_OPERATOR`.
- `PROOF_REQUESTED`/`PROOF_QUEUED`에서 **30일 무업로드 → `EXPIRED_USER`**, 순위 제외.
- 큐에는 **사용자당 최신 PB 1건만** 둔다. 새 PB가 들어오면 이전 대기 건은 큐에서 빠진다. Top N에서 이탈한 기록도 큐에서 제외한다.

재제출 규칙:
- `EXPIRED_OPERATOR`(운영자 SLA 미이행): 14일 안에 1회 **무페널티** 재제출(기기 보관 파일 사용, 레이트 리밋 비차감, 큐 우선순위 상향).
- `EXPIRED_USER`(업로드 미완료/무응답): 일반 재제출(레이트 리밋 적용).
- `REJECTED`: 같은 기록 재제출 불가, 새 엄격 세션으로 다시 제출.
- 기기에서 영상을 지웠다면 재제출 불가 — PRD에 사전 고지.

## P6. 증빙 워크플로

1. 서버가 `PROOF_REQUESTED` 전환 → 앱 알림("이 기록을 Verified 순위에 올리려면 증빙 영상을 제출하세요").
2. 사용자가 영상 동의(P9) 후 증빙 파일 업로드 → Storage 비공개 버킷(10 MB 또는 과거 PB 16 MB 버킷, MIME `video/mp4`).
3. 서버가 업로드 파일 SHA-256을 계산해 제출 해시와 대조(P7) → `PENDING`.
4. 검수자가 영상 + 타임라인을 보고 판정 → `APPROVED`/`REJECTED`, 썸네일 생성.
5. R1: 판정 즉시 원본 삭제.

## P7. 해시 바인딩

### P7.1 Phase 2 신규 세션

P1-H: 업로드 파일 해시 = 세션 종료 직후 기록한 증빙 파일 해시일 때만 수락.

### P7.2 MVP 과거 PB (2.2B)

**결정: 서버가 원본 해시를 대조한다. 재인코딩하지 않는다.** 결정적 재인코딩(같은 원본 → 같은 바이트)은 하드웨어 인코더·OS 버전에 따라 보장되지 않아 기각.

- MVP부터 엄격 세션을 증빙 호환 프리셋(720p/30fps, 목표 1.0 Mbps)으로 녹화해, 원본 자체가 업로드 가능한 크기가 되게 한다: 80초 세트 ≈ 10 MB, 128초 세트 ≈ 16 MB.
- 과거 PB 증빙 버킷 상한 **16 MB**(Phase 2 신규 세션은 10 MB 유지). 128초를 넘는 과거 PB 세트는 제출 불가(PRD 고지).
- 폴백: CameraX `Recorder`의 목표 비트레이트 지정이 불가하면(UNVERIFIED, M0에서 확인) MVP에서 세션 종료 직후 WorkManager로 한 번 재인코딩하고 **그 결과 파일**을 보관·해시한다. 이 경우 MVP 제출의 `videoSha256`도 그 결과 파일의 해시다.

수락 규칙:
- **강한 바인딩:** 업로드 파일 SHA-256 = 저장된 `videoSha256` → 일반 검수 절차. 불일치면 업로드 거부(`HASH_MISMATCH`).
- **약한 바인딩**(해시 없음 — 영상 미보관, 기능 도입 전 기록 등):
  1. 영상 제출은 허용하되, 검수자가 영상과 타임라인을 교차 확인한다: 모든 rep의 TOP 시각이 타임라인과 **±0.5초 이내**, rep 수 일치.
  2. 통과 시 `APPROVED` + 내부 플래그 **`bindingStrength = WEAK`** 기록(공개 표시는 같은 ✓).
  3. 조금이라도 의심되면 반려하고 **새 엄격 세션 녹화를 요청**한다.
  4. 약한 바인딩 제출은 P5 주간 접수 상한 안에서 강한 바인딩보다 뒤 순위.

## P8. 보관정책 R1 구현

- 판정 함수가 원본 객체 삭제를 수행(또는 삭제 큐에 넣음). 7일 초과 `PENDING`은 `EXPIRED_OPERATOR`로 전환하고 원본 삭제.
- 주기 작업: pg_cron(S10)으로 매시간 만료 처리. Free에서 pg_cron을 못 쓰면 GitHub Actions 일 1회 작업이 service_role로 Edge Function(S9, 호출량 무시 가능)을 불러 처리한다(최대 대기 7일 + 1일 지연 허용으로 문서화).
- 영구 보존: SHA-256, 썸네일(검수자 전용 비공개 버킷), 판정 결과·시각·검수자 메모.

## P9. 영상 동의·철회

- 첫 증빙 업로드 전 별도 동의 화면: (a) 용도(순위 진입/PB 갱신 기록의 부정 여부 수동 검수), (b) 보관(판정 즉시 원본 삭제, 최대 7일, 이후 해시 + 검수자 전용 썸네일만), (c) 열람 권한(검수자만, 비공개).
- 동의 철회: 서버의 해당 사용자 영상·썸네일 **즉시 삭제** + 관련 기록 Verified 탭 노출 즉시 중단(상태는 `NOT_REQUIRED` 또는 `PROOF_REQUESTED`로 되돌림).
- Data safety에 "영상" 항목 추가.

## P10. 대안 — 외부 일부공개(unlisted) 링크 증빙

사용자가 YouTube 등 일부공개 링크를 제출하는 방식.

| 장점 | 단점 |
|------|------|
| Storage/Egress 0, 검수 큐 상한 불필요 | 해시↔세션 바인딩 불가(재업로드·편집 영상 검증이 약함), 링크 삭제·비공개 전환(link rot), 외부 계정이 필요해 UX 마찰 |

→ 기본안(R1)의 폴백 또는 이후 옵션으로만 기록한다.

## P11. 부정 테스트 (Phase 2)

| # | 시나리오 | 기대 결과 |
|---|----------|-----------|
| Q1 | 제출 시 `NOT_REQUIRED`였던 기록이 N 증가·상위 기록 삭제로 Top N 진입 | Verified 탭 비노출, 상태 `PROOF_REQUESTED`로 전환, 사용자 알림 |
| Q2 | 한 주의 26번째 증빙 업로드 | 서버 거부, 기록은 `PROOF_QUEUED` |
| Q3 | 동시 `PENDING` 25건 상태에서 업로드 | 거부, `PROOF_QUEUED` |
| Q4 | 업로드 파일 해시 ≠ 저장된 `videoSha256` | `HASH_MISMATCH`로 거부 |
| Q5 | 10 MB 초과 또는 MIME ≠ `video/mp4` 업로드(신규 세션 버킷) | 버킷 제한으로 거부 |
| Q6 | 클라이언트가 상태를 `APPROVED`로 직접 UPDATE | RLS 거부 |
| Q7 | 비검수자가 증빙 영상·썸네일 URL 접근 | 거부(비공개 버킷) |
| Q8 | `PENDING` 7일 경과 | `EXPIRED_OPERATOR` 전환, 원본 삭제, 14일 무페널티 재제출 허용 |
| Q9 | `PROOF_REQUESTED` 30일 무업로드 | `EXPIRED_USER`, 순위 제외 |
| Q10 | 동의 철회 | 영상·썸네일 즉시 삭제, Verified 탭 즉시 비노출 |

## P12. 계정 삭제 시 삭제 대상 — Phase 2 (MVP 목록 M7.2에 추가)

| 데이터 | 위치 | 처리 |
|--------|------|------|
| M7.2의 모든 항목 | — | 삭제 |
| 대기 중 증빙 영상 원본 | Storage 비공개 버킷 | 즉시 삭제 |
| 검수자 전용 썸네일 | Storage 비공개 버킷 | 삭제 |
| 증빙 해시·판정 기록(상태, `bindingStrength`, 판정 시각, 검수자 메모) | DB | 삭제 |
| 증빙 큐 항목 | DB | 삭제 |
| 영상 동의 기록 | DB | 삭제(동의 이력 보존 의무 여부는 UNVERIFIED — 법률 확인 전까지 삭제) |

---

## 부록 A. 미해결(UNVERIFIED) 목록과 확인 계획

| 항목 | 현재 상태 | 확인 시점·방법 | 폴백 |
|------|-----------|----------------|------|
| S4 무엇이 Supabase "활동"인지 | UNVERIFIED | M4 전, keepalive만 돌린 상태로 8일 이상 관찰 | 월 1회 수동 확인 |
| S7 유예 기간 길이 | UNVERIFIED | 한도 초과 알림 수신 시 기록 | 즉시 기능 플래그 off |
| S10 pg_cron Free 가용성 | UNVERIFIED | M4 전 Free 프로젝트에서 확인 | GitHub Actions 일 1회 작업 |
| S11 DB 웹훅 Free 가용성 | UNVERIFIED | M4 전 확인 | pg_cron 스위퍼 또는 토큰 기록만 |
| I4 Play Integrity 과금 | UNVERIFIED | M4 전 Play Console·Cloud 콘솔 확인 | 할당량 내 사용, 과금 시 기록만 |
| I5 Edge Function 해독 PoC | UNVERIFIED | M4 PoC | 토큰 기록만(30일) |
| 토큰 크기 | UNVERIFIED | M4 PoC | 2 KB 가정(최악 DB 9%) |
| G-A4 비공개 저장소 Actions 무료 분량 | UNVERIFIED | 저장소 생성 시 | 공개 저장소 + 60일 내 커밋 |
| Free 백업 보존 기간 | UNVERIFIED | 개인정보 처리방침 작성 전 | 문구에 기간 미기재 |
| T_floor 0.5초 / T_rep_min 0.8초 | 초기값 | D6 데이터로 확정 | — |
| CameraX 목표 비트레이트 지정 | UNVERIFIED | M0 | MVP 재인코딩 폴백(P7.2) |
