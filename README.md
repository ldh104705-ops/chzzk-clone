# 치지직 클론

## 1. 프로젝트 소개

치지직(CHZZK) 스타일의 라이브 스트리밍 사이트를 4단계에 걸쳐 만드는 과제 프로젝트입니다. 1단계는 로그인 없이 녹화된 영상을 등록하고, 목록·상세 화면에서 재생하는 기능이고, 2단계는 휴대폰 카메라·마이크로 하는 라이브 송출(WebRTC)과 시청입니다. 이후 단계에서 인증·인가와 댓글, 도네이션(토스페이먼츠 테스트 모드)을 같은 코드베이스에 이어서 추가합니다.

모노레포 구성:

| 디렉터리 | 내용 |
| --- | --- |
| `backend/` | Spring Boot 4 (Java 25) REST API, PostgreSQL, Flyway |
| `frontend/` | React + Vite + TypeScript SPA (react-router) |
| `docs/` | 과제 문서 |

## 2. 실행 방법

### 준비물

- JDK 25
- Node 24 (npm 포함)
- Docker (PostgreSQL 컨테이너, 그리고 백엔드 통합 테스트의 Testcontainers에 필요)

### 실행

```bash
# 1) DB 실행 (루트에서)
docker compose up -d

# 2) 백엔드 (http://localhost:8080)
cd backend
./gradlew bootRun          # Windows PowerShell/cmd: .\gradlew.bat bootRun

# 3) 프론트엔드 (http://localhost:5173) - 새 터미널에서
cd frontend
npm install
npm run dev
```

브라우저에서 http://localhost:5173 을 엽니다. 개발 서버가 `/api`와 `/ws`(라이브 시그널링 WebSocket) 요청을 `http://localhost:8080`으로 프록시합니다. 휴대폰으로 라이브 송출을 시험하려면 `npm run dev` 대신 `npm run dev:https`를 사용합니다(아래 "2단계: 직접 확인 절차" 참고).

### 테스트

```bash
cd backend && ./gradlew test                         # Docker 필요 (Testcontainers가 PostgreSQL을 띄움)
cd frontend && npm test                              # vitest 단위 테스트
cd frontend && npm run build                         # 타입 검사(tsc) + 프로덕션 빌드
```

## 3. 환경변수

- 백엔드: 루트의 [`.env.example`](.env.example)에 **모든** 백엔드 환경변수와 설명이 있습니다.
- 프론트엔드: [`frontend/.env.example`](frontend/.env.example) (`VITE_API_BASE_URL`, `VITE_DEV_HTTPS`, `VITE_ALLOWED_HOSTS`).

로컬에서는 모두 기본값이 있어 별도 설정 없이 동작합니다. Spring Boot는 `application.properties`의 `${NAME:기본값}` 형태로 **프로세스 환경변수**를 읽습니다. 따라서 값을 바꾸려면 셸에서 `export SPRING_DATASOURCE_URL=...` 후 `./gradlew bootRun`을 실행하거나, IDE 실행 구성(Run Configuration)의 Environment variables에 입력하세요. (`.env` 파일은 Spring이 자동으로 읽지 않으며, `docker compose`만 읽습니다.) 프론트엔드의 `VITE_*` 값은 빌드 시점에 번들에 포함되므로 `.env`에 두거나 호스팅 서비스의 빌드 환경변수로 설정합니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `PORT` | `8080` | 서버 포트 |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/chzzk` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | `chzzk` / `chzzk` | DB 계정 (로컬 개발용 값이며 배포 시 반드시 교체) |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 쉼표로 구분한 허용 Origin (프론트엔드 주소) |
| `APP_STORAGE_DIR` | `./uploads` | 업로드 영상 저장 경로 |
| `APP_MAX_UPLOAD_SIZE` | `500MB` | 업로드 최대 크기 |
| `APP_LIVE_GRACE_SECONDS` | `15` | 방송자 연결이 끊긴 뒤 방송을 종료 처리하기까지 기다리는 시간(초). 그 안에 재접속하면 방송이 유지됩니다 |
| `APP_WEBRTC_STUN_URLS` | `stun:stun.l.google.com:19302` | WebRTC STUN 서버(쉼표 구분). `GET /api/live/ice-servers`로 프론트에 전달됩니다 |
| `APP_WEBRTC_TURN_URLS` | 빈 값 | (선택) TURN 서버 URL(쉼표 구분). 비우면 TURN 미사용 |
| `APP_WEBRTC_TURN_USERNAME` / `APP_WEBRTC_TURN_CREDENTIAL` | 빈 값 | (선택) TURN 계정. 실제 값은 저장소에 커밋하지 마세요 |
| `VITE_API_BASE_URL` (프론트) | 빈 값 | 비우면 같은 Origin. 배포 시 백엔드 URL (WebSocket 주소도 여기서 http→ws, https→wss로 만듭니다) |
| `VITE_DEV_HTTPS` (프론트, 개발용) | `false` | `true`이면 개발 서버를 자체 서명 HTTPS로 실행 (`npm run dev:https`가 설정) |
| `VITE_ALLOWED_HOSTS` (프론트, 개발용) | 빈 값 | 개발 서버가 추가로 허용할 호스트(쉼표 구분). 터널 주소 접속 시 사용 |

### 배포 시 참고 (Railway 백엔드 + Cloudflare Pages 프론트)

배포는 이번 단계 범위가 아니지만 설정이 모두 환경변수로 분리되어 있어 코드 수정 없이 옮길 수 있습니다.

- Railway Postgres 연결: `SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`, `SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}`, `SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}`
- Railway의 디스크는 **휘발성**이라 재배포하면 업로드 파일이 사라집니다. Volume을 연결하고 그 마운트 경로를 `APP_STORAGE_DIR`로 지정하세요.
- `APP_CORS_ALLOWED_ORIGINS`에 Cloudflare Pages 주소를, 프론트엔드의 `VITE_API_BASE_URL`에 Railway 백엔드 주소를 넣습니다.

## 4. DB 준비 방법

```bash
docker compose up -d
```

PostgreSQL 17 컨테이너(DB/사용자/비밀번호 기본값 `chzzk`, 포트 5432)가 실행되며 데이터는 Docker 볼륨(`chzzk-pgdata`)에 보존됩니다. 테이블은 백엔드 시작 시 **Flyway가 자동으로 생성**합니다(`backend/src/main/resources/db/migration/V1__create_broadcast.sql`). Hibernate는 스키마를 만들지 않고 검증만 합니다(`ddl-auto=validate`). 데이터를 초기화하려면 `docker compose down -v`를 실행합니다.

## 5. 설계 결정과 이유

- **Spring Boot + PostgreSQL + WebRTC (사용자 선택)**: 백엔드는 Spring Boot, 저장소는 PostgreSQL, 2단계 라이브 송출은 WebRTC로 진행하기로 정했습니다.
- **모노레포 + 프론트/백엔드 분리**: 과제가 "각 단계에서 앞 단계를 유지하며 확장"하도록 요구하므로 한 저장소에서 함께 발전시키고, 배포는 Railway(API)와 Cloudflare Pages(SPA)로 나눌 수 있게 했습니다. 배포 의존 값은 전부 환경변수입니다.
- **영상 등록 방식 두 가지 모두 지원 (파일 업로드 + 외부 URL)**: 과제가 "영상 파일 업로드 또는 저장된 영상 연결 방식은 자유"라고 했으므로 둘 다 제공합니다. 정확히 하나만 입력하도록 서버가 검증하며 DB 체크 제약으로도 보장합니다.
- **`broadcast` 단일 테이블**: 이후 단계의 라이브 방송도 같은 목록·상세에 나와야 하므로 `type`(현재 RECORDED, 2단계에서 LIVE 추가)과 `status` 컬럼을 두고 영상 위치 컬럼(저장 파일명/원본명/콘텐츠 타입/크기 또는 `video_url`)을 함께 둡니다. 스키마는 Flyway SQL로 버전 관리합니다.
- **`VideoStorage` 인터페이스 + 로컬 디스크 구현**: 지금은 디스크에 저장하되, 나중에 S3/R2 구현으로 교체할 수 있게 인터페이스 뒤에 숨겼습니다. 저장 파일명은 UUID로 새로 만들고(확장자는 검증 후 사용), 읽을 때도 이름 형식과 경로 탈출 여부를 검사해 경로 조작(path traversal)을 막습니다.
- **Range 지원 스트리밍**: `/api/broadcasts/{id}/video`가 `ResponseEntity<Resource>`를 반환해 Spring MVC가 `Range` 요청(206)을 처리하므로 `<video>`에서 탐색(seek)이 가능합니다. 외부 URL 방송은 JSON의 `playbackUrl`을 플레이어가 직접 사용합니다.
- **인증 없음을 코드에 명시**: 이번 단계는 로그인·인가를 요구하지 않으므로 Spring Security 의존성을 넣지 않았고, 컨트롤러에 모든 엔드포인트가 공개임을 주석으로 남겼습니다(3단계에서 추가).
- **오류 응답 통일**: `@RestControllerAdvice`가 검증 실패는 400, 방송자 키 불일치는 403, 없는 리소스는 404, 용량 초과는 413으로 `{status, error, message}` JSON을 반환합니다.

2단계에서 추가한 결정:

- **WebRTC P2P + 시그널링 전용 서버 (미디어 서버 없음)**: 영상은 방송자 휴대폰 브라우저에서 시청자 브라우저로 직접 전송되고, 서버는 WebSocket(`/ws/live/{id}`)으로 SDP/ICE 메시지만 중계합니다. 별도 미디어 서버(SFU, RTMP→HLS 변환)를 두지 않아 구성이 가장 단순하고 휴대폰 브라우저만으로 송출할 수 있기 때문입니다. **한계**: 시청자 1명마다 방송자 휴대폰이 영상을 한 번씩 더 업로드하므로(시청자별 `RTCPeerConnection`) 시청자 수가 소수(대략 3~5명)일 때만 적합합니다. 많은 시청자가 필요하면 SFU(LiveKit, mediasoup 등)가 필요합니다.
- **방송 상태 `READY → LIVE → ENDED`**: 방송을 만들면 `READY`이고, 방송자 휴대폰이 키로 시그널링 소켓에 접속해 송출을 시작하는 순간 `LIVE`(`started_at` 기록)가 됩니다. 만들기만 하고 송출하지 않은 방송이 "방송 중"으로 목록에 뜨는 것을 막기 위해서이며, `READY` 방송은 상태를 지정하지 않은 목록에서 숨깁니다. 종료되면 `ENDED`(`ended_at` 기록)입니다.
- **방송자 키(`broadcasterKey`)**: 라이브 방송 생성 응답에 한 번만 내려가는 무작위 256비트 토큰으로, 시그널링 소켓의 방송자 `join`과 `POST /api/broadcasts/{id}/end`(`X-Broadcaster-Key` 헤더)에서 요구합니다. 틀리거나 없으면 403/`FORBIDDEN`입니다. **이것은 사용자 인증이 아니라**(로그인은 3단계) 남이 내 방송을 가로채거나 종료하지 못하게 하는 최소한의 보호입니다. DB에는 SHA-256 해시만 저장하고(고엔트로피 토큰이라 빠른 해시로 충분) 비교는 상수 시간으로 합니다. 브라우저에서는 새로고침·재접속을 위해 해당 탭의 `sessionStorage`에만 보관합니다.
- **방송자 연결 끊김 유예(grace) 시간**: 방송자 소켓이 끊기면 바로 종료하지 않고 `APP_LIVE_GRACE_SECONDS`(기본 15초) 동안 기다립니다. 휴대폰이 잠깐 네트워크를 잃어도 같은 키로 다시 접속하면 방송이 이어지고, 대기 중이던 시청자와는 다시 협상합니다. 시간이 지나면 `ENDED`로 바꾸고 시청자에게 `broadcast-ended`를 보냅니다. 서버가 재시작되면 메모리의 방송실(room)이 사라지므로 시작 시 `READY/LIVE`로 남은 라이브 방송을 모두 `ENDED`로 정리합니다. 방송실 상태가 메모리에 있어 백엔드 인스턴스는 1개를 전제로 합니다.
- **STUN/TURN은 환경변수 → `GET /api/live/ice-servers`**: 프론트에 주소를 하드코딩하지 않고 백엔드 설정을 내려줍니다. 기본은 공개 STUN만이며, 통신사망(대칭형 NAT) 등 직접 연결이 안 되는 환경을 위해 TURN을 환경변수로 추가할 수 있습니다. (정적 TURN 계정은 모든 클라이언트에 노출되므로 필요하면 3단계에서 단기 자격증명으로 바꿀 수 있습니다.)
- **휴대폰 테스트용 HTTPS는 Vite 개발 서버에서 종단**: 브라우저는 보안 컨텍스트(HTTPS 또는 localhost)에서만 카메라를 허용합니다. 휴대폰은 PC의 LAN IP로 접속하므로 HTTPS가 필요한데, `@vitejs/plugin-basic-ssl`(`VITE_DEV_HTTPS=true`일 때만 활성화)로 개발 서버만 HTTPS로 만들고 `/api`·`/ws`는 같은 서버가 `localhost:8080`으로 프록시하므로 백엔드에는 TLS 설정이 필요 없습니다. 프록시는 브라우저의 `Origin` 헤더를 제거해 전달합니다(`https://<PC IP>:5173`에서 온 요청을 백엔드가 `http`로 보고 교차 출처로 거절하는 것을 막기 위함이며, 브라우저는 Vite 서버하고만 통신하므로 CORS 보호가 필요 없습니다). 배포 시에는 백엔드 허용 Origin(`APP_CORS_ALLOWED_ORIGINS`)이 REST CORS와 WebSocket 허용 Origin에 똑같이 적용됩니다.
- **시그널링 프로토콜은 JSON 메시지 + 서버가 `from`을 찍어 중계**: `join`(role: broadcaster/viewer), `viewer-joined`/`viewer-left`, `offer`/`answer`/`ice-candidate`, `broadcast-ended`, `error`. 방송자는 `to`(시청자 id)를 지정해야 하고, 시청자의 메시지는 항상 방송자에게 갑니다. 서버가 발신자 id(`from`)를 직접 채우므로 다른 시청자 흉내를 낼 수 없습니다. 존재하지 않거나 종료된 방송, 잘못된 키, 방향이 틀린 메시지는 `error`로 응답합니다.
- **상태 필터와 라이브 우선 목록**: `GET /api/broadcasts?type=LIVE&status=LIVE`처럼 `type`, `status`로 거를 수 있고, 목록은 항상 `LIVE`를 위에 둔 최신순입니다. 목록 화면은 "방송 중"과 "지난 방송" 구역으로 나눠 `방송 중`/`종료` 배지를 보여 줍니다.
- **스키마는 V2 마이그레이션으로 확장**: `V2__add_live_broadcast.sql`이 `started_at`, `ended_at`, `broadcaster_key_hash`를 추가하고, 허용 값을 넓히기 위해 같은 이름의 CHECK 제약을 다시 만듭니다(PostgreSQL은 CHECK를 제자리에서 수정할 수 없음). 녹화방송은 항상 `ENDED`, 라이브는 영상 파일/URL 없이 키 해시 필수라는 제약도 DB에 추가했습니다.

## 1단계: 인증·인가 없는 녹화방송

### 구현 범위

- 녹화방송 등록: 제목 + (영상 파일 업로드 **또는** 영상 URL) — `POST /api/broadcasts/recorded` (multipart)
- 방송 목록(최신순, `type` 필터 선택) `GET /api/broadcasts`, 상세 `GET /api/broadcasts/{id}`
- 업로드 영상 재생용 스트리밍 `GET /api/broadcasts/{id}/video` (HTTP Range 지원)
- 방송 메타데이터와 영상 위치를 PostgreSQL에 저장 (업로드 파일은 `APP_STORAGE_DIR`에 저장)
- 프론트엔드: 목록 `/`, 등록 `/recordings/new`, 상세·플레이어 `/broadcasts/:id`
- 로그인·권한 검사 없음: 모든 화면과 API가 공개 (인증·인가·라이브·댓글·도네이션은 이번 단계 범위 아님)
- 자동화 테스트로 확인되는 범위: 백엔드 Testcontainers(PostgreSQL) + MockMvc 통합 테스트 — 파일 등록, URL 등록, 검증 실패(제목 없음/소스 둘 다·없음/잘못된 URL/비영상 파일), 목록·상세·404, 영상 응답 본문과 `Range` 응답(206), DB 영속화, 저장소 경로 조작 방어; 프론트엔드 vitest — URL 조합, 폼 검증. (브라우저 화면과 실제 영상 재생은 자동화 테스트 범위 밖입니다.)

### 직접 확인 절차

1. `docker compose up -d`로 DB를 띄우고, `backend`에서 `./gradlew bootRun`, `frontend`에서 `npm install && npm run dev`를 실행합니다.
2. http://localhost:5173 을 열어 목록이 비어 있다는 안내가 나오는지 확인합니다.
3. "녹화방송 등록"에서 제목을 입력하고 **파일 업로드**로 영상(mp4 등)을 선택해 등록합니다. 등록 후 상세 화면으로 이동하는지 확인합니다.
4. 상세 화면의 플레이어에서 영상이 재생되고, 재생 바를 이동(seek)할 수 있는지 확인합니다.
5. 다시 "녹화방송 등록"에서 **영상 URL 연결**을 선택하고 재생 가능한 영상 URL(예: 직접 접근 가능한 `.mp4` 주소)로 등록한 뒤, 상세 화면에서 재생되는지 확인합니다.
6. 목록 화면으로 돌아가 두 방송이 최신순 카드(제목, 유형 배지, 등록 시각)로 보이는지, 카드를 눌러 상세로 이동하는지 확인합니다.
7. 검증 확인: 제목을 비우거나, 잘못된 URL(`ftp://...`)을 넣으면 오류 메시지가 표시되고 등록되지 않는지 확인합니다.
8. **재시작 후 유지 확인**: 백엔드를 종료(Ctrl+C)했다가 `./gradlew bootRun`으로 다시 시작합니다. 페이지를 새로고침했을 때 목록에 방송이 그대로 남아 있고, 업로드한 영상이 다시 재생되는지 확인합니다. (DB는 Docker 볼륨, 영상 파일은 `backend/uploads`에 남아 있습니다.)
9. API 직접 확인(선택): `curl -F title=테스트 -F videoUrl=https://example.com/a.mp4 http://localhost:8080/api/broadcasts/recorded` 후 `curl http://localhost:8080/api/broadcasts`

## 2단계: 인증·인가 없는 휴대폰 라이브 송출

### 구현 범위

- 휴대폰(또는 PC) 브라우저의 카메라·마이크로 라이브 방송을 **시작·종료**합니다. 송출 방식은 WebRTC(P2P)이고 서버는 WebSocket 시그널링과 방송 상태 저장만 맡습니다.
- 로그인·권한 검사 없음: 누구나 방송을 만들고 시청할 수 있습니다. 방송 만들기 응답의 `broadcasterKey`는 사용자 인증이 아니라 남이 내 방송을 가로채지 못하게 하는 임시 보호 장치입니다(인증은 3단계). 댓글·도네이션도 이번 단계 범위가 아닙니다.
- 백엔드
  - `POST /api/broadcasts/live` `{title}` → 라이브 방송 생성(상태 `READY`) + `broadcasterKey` 반환(한 번만 노출, DB에는 SHA-256 해시만 저장)
  - `POST /api/broadcasts/{id}/end` (`X-Broadcaster-Key` 헤더) → 종료(`ENDED`). 키가 틀리거나 없으면 403, 이미 종료됐으면 그대로 200(멱등)
  - `GET /api/broadcasts?type=LIVE&status=LIVE` 상태·유형 필터, 목록은 `LIVE`를 맨 위에 둔 최신순(상태 미지정 시 `READY`는 숨김)
  - `WS /ws/live/{broadcastId}` 시그널링: `join`(broadcaster는 키 필요/viewer), `viewer-joined`, `viewer-left`, `offer`, `answer`, `ice-candidate`, `broadcast-ended`, `error`. 종료되었거나 시작 전인 방송에 시청자가 들어오면 `error`
  - 방송자 소켓이 끊기면 `APP_LIVE_GRACE_SECONDS`(기본 15초) 뒤에 `ENDED`로 바꾸고 시청자에게 `broadcast-ended` 전송(그 안에 같은 키로 재접속하면 유지)
  - `GET /api/live/ice-servers`: STUN/TURN 설정(`APP_WEBRTC_*`)을 프론트에 전달. 허용 WebSocket Origin은 `APP_CORS_ALLOWED_ORIGINS`와 동일
  - Flyway `V2__add_live_broadcast.sql`: `LIVE` 유형, `READY/LIVE/ENDED` 상태, `started_at`/`ended_at`/`broadcaster_key_hash`
- 프론트엔드
  - 목록 `/`: "방송 중" 구역과 "지난 방송" 구역, `방송 중`/`종료` 배지, "라이브 방송 시작" 링크
  - `/live/new` 제목 입력 → `/live/:id/studio` 방송자 스튜디오: 카메라·마이크 미리보기(내 화면은 음소거), 전면/후면 카메라 전환, `방송 시작`/`방송 종료`, 시청자 수. HTTPS가 아니거나 카메라 API가 없을 때, 권한이 거부되었을 때 한국어 안내 표시
  - `/broadcasts/:id`: 라이브 방송은 `<video autoplay playsinline>`으로 WebRTC 스트림 시청(자동재생 정책 때문에 음소거로 시작하고 `소리 켜기` 버튼 제공), 방송 중/종료 배지와 종료 안내. 녹화방송은 1단계 플레이어 그대로
- 한계: 시청자마다 방송자 휴대폰에서 별도 연결을 만드는 방식이라 시청자는 소수(대략 3~5명)만 지원합니다. 직접 연결이 안 되는 네트워크(통신사망 대칭형 NAT 등)에서는 TURN 서버가 필요합니다(아래 참고).
- 자동화 테스트로 확인되는 범위(`./gradlew test`, `npm test`): 라이브 생성·종료(틀린/없는 키 403, 멱등 종료), 상태·유형 필터와 라이브 우선 정렬, V2 마이그레이션(기존 V1 데이터 유지와 CHECK 제약), 실제 WebSocket 클라이언트를 쓴 시그널링 통합 테스트(방송자/시청자 join과 `viewer-joined`, offer/answer/ice가 올바른 상대에게만 전달, 종료·시작 전 방송 입장 거부, 잘못된 키 거부, 허용되지 않은 Origin 거부, 방송자 끊김 후 유예 시간이 지나면 `ENDED` + `broadcast-ended`, 유예 시간 내 재접속); 프론트엔드의 WebSocket URL 계산, 시그널링 메시지 직렬화·파싱, 연결 목록 관리, 카메라 지원 판정·오류 문구. **브라우저/휴대폰에서의 실제 카메라 송출과 영상 재생은 자동화 테스트 범위 밖이며, 아래 절차로 직접 확인해야 합니다(이 저장소 작성 시점에 휴대폰·브라우저로 확인했다고 주장하지 않습니다).**

### 직접 확인 절차

준비: PC와 휴대폰이 **같은 Wi-Fi**에 연결되어 있어야 합니다(게스트 Wi-Fi나 "AP 격리"가 켜진 공유기는 기기 간 통신이 막혀 있을 수 있습니다).

1. **DB·백엔드 실행**: `docker compose up -d` 후 `backend`에서 `./gradlew bootRun` (http://localhost:8080). 환경변수는 기본값 그대로 동작합니다.
2. **PC의 LAN IP 확인**: PowerShell/cmd에서 `ipconfig`를 실행하고 사용 중인 Wi-Fi 어댑터의 `IPv4 주소`(예: `192.168.0.12`)를 적어 둡니다. (macOS/Linux는 `ifconfig` 또는 `ip addr`.) 아래 `<PC IP>`가 이 값입니다.
3. **Windows 방화벽에서 5173 포트 허용**: 처음 실행할 때 "Windows 보안 경고"가 뜨면 **개인 네트워크 허용**을 선택합니다. 뜨지 않았다면 관리자 PowerShell에서 `New-NetFirewallRule -DisplayName "Vite 5173" -Direction Inbound -Protocol TCP -LocalPort 5173 -Action Allow -Profile Private` 를 실행하거나 "고급 보안이 포함된 Windows Defender 방화벽 → 인바운드 규칙 → 새 규칙 → 포트 TCP 5173"으로 허용합니다. (백엔드 8080은 휴대폰이 직접 접속하지 않으므로 열지 않아도 됩니다. 확인이 끝나면 규칙을 삭제하세요.)
4. **HTTPS 개발 서버 실행**: `frontend`에서 `npm install` 후 `npm run dev:https`. 터미널의 `Network: https://<PC IP>:5173/` 줄에서 주소를 확인합니다. (자체 서명 인증서를 쓰고, `/api`와 `/ws`는 같은 서버가 백엔드로 프록시하므로 백엔드에는 HTTPS가 필요 없습니다. 휴대폰 브라우저는 HTTPS에서만 카메라를 허용합니다.)
5. **휴대폰에서 접속**: 휴대폰 브라우저에서 `https://<PC IP>:5173/live/new`를 엽니다. 자체 서명 인증서라 "연결이 비공개로 설정되어 있지 않음" 경고가 뜨는데, 이 개발용 서버가 맞다면 **고급 → `<PC IP>`(안전하지 않음)으로 이동**(Android Chrome) 또는 **세부사항 보기 → 이 웹 사이트 방문**(iOS Safari)으로 진행합니다.
6. **방송 만들기**: 제목을 입력하고 "스튜디오로 이동"을 누릅니다. 주소가 `/live/<id>/studio`로 바뀌어야 합니다.
7. **카메라·마이크 권한 허용**: 스튜디오가 열리면 브라우저가 권한을 묻습니다. **허용**을 누르면 내 얼굴 미리보기가 나옵니다.
   - Android Chrome: 팝업에서 "사이트 방문 중에 허용". 실수로 거부했다면 주소창 왼쪽 아이콘 → 권한(또는 사이트 설정)에서 카메라·마이크를 "허용"으로 바꾸고 새로고침합니다.
   - iOS Safari: 팝업에서 "허용". 거부했다면 `설정 → Safari → 카메라/마이크`를 "묻기" 또는 "허용"으로 바꾸거나, 주소창의 `aA` 메뉴 → 웹 사이트 설정에서 변경한 뒤 새로고침합니다. iOS에서는 Safari(또는 Safari 기반 브라우저)를 사용하세요.
   - 권한이 거부되었거나 HTTPS가 아니면 화면에 한국어 안내 문구가 표시됩니다.
8. **카메라 전환(선택)**: "카메라 전환" 버튼으로 전면/후면 카메라가 바뀌는지 확인합니다.
9. **방송 시작**: "방송 시작"을 누릅니다. 상태 배지가 `연결 중` → `방송 중`으로 바뀌고 시청자 수 `0명`이 보여야 합니다.
10. **다른 화면에서 시청**: PC 브라우저에서 `https://localhost:5173`(인증서 경고 수락) 또는 `npm run dev`로 띄운 `http://localhost:5173`을 엽니다. 목록의 **방송 중** 구역에 `방송 중` 배지가 붙은 방송이 보이는지 확인하고 카드를 눌러 시청합니다(스튜디오의 "시청 화면 열기"로도 열 수 있습니다). 영상이 나오면 "소리 켜기"를 눌러 소리가 나는지 확인합니다. 휴대폰 스튜디오의 시청자 수가 `1명`으로 오르는지도 확인합니다. (주의: 같은 PC에서 시청하면 에코가 날 수 있으니 소리는 이어폰으로 확인하세요.)
11. **종료 확인**: 휴대폰에서 "방송 종료"를 누릅니다. 휴대폰 화면에 `종료` 배지와 "방송이 종료되었습니다"가 나오고, PC 시청 화면의 배지가 `종료`로 바뀌며 종료 안내가 보이는지 확인합니다. 목록을 새로고침하면 해당 방송이 "지난 방송" 구역에 `종료` 배지로 있어야 합니다. 종료된 방송 주소를 다시 열면 영상 없이 종료 안내만 보여야 합니다.
12. **비정상 종료 확인(선택)**: 방송 중에 휴대폰의 탭을 닫거나 Wi-Fi를 끄면 `APP_LIVE_GRACE_SECONDS`(기본 15초) 뒤에 방송이 `종료`로 바뀌고 시청 화면에도 종료 안내가 뜹니다. 그 안에 같은 탭에서 다시 연결되면 방송이 이어집니다.
13. **API로 확인(선택)**: 방송 중일 때 `curl "http://localhost:8080/api/broadcasts?type=LIVE&status=LIVE"`로 방송이 보이는지, 종료 후에는 비어 있는지 확인합니다. 다른 사람이 종료하지 못하는지는 `curl -i -X POST http://localhost:8080/api/broadcasts/<id>/end -H "X-Broadcaster-Key: wrong"`이 403인지로 확인합니다.

### 대안: 터널로 HTTPS 주소 만들기

방화벽·자체 서명 인증서 경고가 번거롭거나 PC와 휴대폰이 같은 Wi-Fi에 있지 않을 때는 Cloudflare Quick Tunnel로 신뢰되는 HTTPS 주소를 받을 수 있습니다(`cloudflared` 설치 필요).

1. 백엔드를 실행합니다(`./gradlew bootRun`). 터널이 HTTPS를 맡으므로 `dev:https`는 쓰지 않습니다.
2. 터미널에서 `cloudflared tunnel --url http://localhost:5173`을 실행하면 `https://<임의 단어>.trycloudflare.com` 주소가 출력됩니다. (호스트 이름은 터널을 띄워야 알 수 있습니다.)
3. `frontend`에서 그 호스트를 `VITE_ALLOWED_HOSTS`에 넣어 개발 서버를 실행합니다. Vite는 알 수 없는 Host 헤더를 거부하므로 이 값이 `vite.config.ts`의 `server.allowedHosts`로 전달됩니다: `VITE_ALLOWED_HOSTS=<임의 단어>.trycloudflare.com npm run dev` (PowerShell: `$env:VITE_ALLOWED_HOSTS="<임의 단어>.trycloudflare.com"; npm run dev`).
4. 휴대폰에서 `https://<임의 단어>.trycloudflare.com/live/new`를 열고 위 6번부터 같은 절차로 진행합니다. 이 주소는 공개 주소이므로 확인이 끝나면 터널(`cloudflared`)을 종료하세요. 방송자 키로 방송 가로채기는 막지만 로그인이 없으므로 누구나 방송을 만들고 볼 수 있습니다.

### 연결이 안 될 때: TURN 서버

WebRTC는 기본적으로 휴대폰과 시청자 브라우저가 서로 직접 연결(P2P)합니다. 같은 Wi-Fi에서는 대부분 되지만, 휴대폰이 LTE/5G(통신사 CGNAT, 대칭형 NAT)이거나 방화벽이 UDP를 막는 환경에서는 영상 없이 "연결 중"에 머물 수 있습니다. 이 경우 TURN 릴레이 서버(예: coturn)가 필요하고, 백엔드를 시작하기 전에 다음 환경변수를 설정합니다(값 예시는 [`.env.example`](.env.example) 참고).

```bash
export APP_WEBRTC_TURN_URLS="turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349"
export APP_WEBRTC_TURN_USERNAME="<계정>"
export APP_WEBRTC_TURN_CREDENTIAL="<비밀번호>"
./gradlew bootRun
```

브라우저는 `GET /api/live/ice-servers` 응답으로 STUN과 TURN을 함께 받아 사용합니다. 이번 단계에서는 TURN을 직접 운영하지 않으며, TURN 경유 동작은 확인하지 않았습니다.

## 3단계: 인증·인가와 댓글 (진행 예정)

> 회원가입·로그인·로그아웃, 방송 소유자 권한 검사, 댓글, 시청·목록 조회 공개 여부 설명은 이 단계에서 작성합니다.

## 4단계: 도네이션 (진행 예정)

> 토스페이먼츠 테스트 모드 결제, 서버 승인 확인, 중복 통지 방어, 방송별 후원 내역·합계, 테스트 결제 성공·실패·취소·중복 통지 확인 방법은 이 단계에서 작성합니다.
