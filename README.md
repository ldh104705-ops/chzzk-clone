# 치지직 클론

## 1. 프로젝트 소개

치지직(CHZZK) 스타일의 라이브 스트리밍 사이트를 5단계에 걸쳐 만드는 과제 프로젝트입니다. 1단계는 로그인 없이 녹화된 영상을 등록하고, 목록·상세 화면에서 재생하는 기능이고, 2단계는 휴대폰 카메라·마이크로 하는 라이브 송출(WebRTC)과 시청입니다. 3단계에서는 회원가입·로그인(JWT)과 방송 소유자 권한 검사, 댓글을 추가했습니다. 4단계에서는 토스페이먼츠 테스트 모드로 방송자에게 후원(도네이션)하는 기능을 추가했습니다. 5단계에서는 서버를 컨테이너 이미지로 만들어 로컬 Kubernetes(kind)에 여러 인스턴스로 띄우고, 부하에 따라 인스턴스 수가 자동으로 늘고 줄게 했습니다(HPA). 이를 위해 로그인 세션·영상·라이브 상태를 인스턴스 밖 저장소(Redis, S3 호환 저장소, PostgreSQL)로 옮겼습니다.

모노레포 구성:

| 디렉터리 | 내용 |
| --- | --- |
| `backend/` | Spring Boot 4 (Java 25) REST API, PostgreSQL, Flyway, (5단계) Redis·S3 호환 저장소 연동과 `Dockerfile` |
| `frontend/` | React + Vite + TypeScript SPA (react-router), 컨테이너 이미지는 nginx가 SPA를 서빙하고 `/api`·`/ws`를 백엔드로 프록시 |
| `deploy/` | (5단계) kind 클러스터 설정, Kubernetes 매니페스트(`deploy/k8s/`), 실행·정리·관찰 스크립트(`deploy/scripts/`) |
| `load/` | (5단계) k6 부하 테스트 스크립트(`load/k6/scale-test.js`) |
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

### 클러스터 모드로 실행 (5단계, 선택)

기본(`APP_CLUSTER_MODE=local`)은 위처럼 인스턴스 1개·메모리 상태로 동작하며 Redis가 필요 없습니다. 여러 인스턴스를 띄우려면 두 가지 방법이 있습니다.

```bash
# (A) Kubernetes(kind)에 한 번에 — 자세한 절차는 "5단계: 로컬 오토스케일"
export PATH="$PATH:/c/Users/<사용자>/AppData/Local/Microsoft/WinGet/Packages/Kubernetes.kind_Microsoft.Winget.Source_8wekyb3d8bbwe"   # kind가 PATH에 없을 때
deploy/scripts/up.sh          # → http://localhost:8090
deploy/scripts/down.sh

# (B) Kubernetes 없이 — Redis·MinIO만 docker compose로 띄우고 백엔드를 포트만 바꿔 여러 개 실행
docker compose --profile cluster up -d      # postgres + redis + minio
export APP_CLUSTER_MODE=redis APP_JWT_SECRET="$(openssl rand -base64 48)" \
       APP_STORAGE_TYPE=s3 APP_STORAGE_S3_ENDPOINT=http://localhost:9000 \
       APP_STORAGE_S3_ACCESS_KEY=chzzkminio APP_STORAGE_S3_SECRET_KEY=chzzkminio-secret APP_STORAGE_S3_CREATE_BUCKET=true
PORT=8080 ./gradlew bootRun &     # (이 방법 B는 직접 실행해 보지 않았습니다) 두 번째 인스턴스: PORT=8081 (같은 환경변수) — 앞단 로드밸런서는 직접 두어야 합니다
```

### 컨테이너 이미지

```bash
docker build -t chzzk-backend:local backend       # JDK 25로 빌드 → eclipse-temurin:25-jre 위에서 비루트 사용자로 실행, PORT 환경변수 사용
docker build -t chzzk-frontend:local frontend     # Node로 빌드 → nginx가 SPA 서빙 + /api·/ws 프록시 (BACKEND_ADDRESS=host:port)
# 프론트엔드를 다른 도메인에 따로 두는 경우: docker build --build-arg VITE_API_BASE_URL=https://api.example.com frontend
```

### 테스트

```bash
cd backend && ./gradlew test                         # Docker 필요 (Testcontainers가 PostgreSQL·Redis·MinIO를 띄움)
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
| `APP_JWT_SECRET` | 빈 값(실행마다 무작위 생성) | 로그인 토큰(JWT, HS256) 서명 비밀키, **32바이트 이상**. 비워 두면 서버는 시작되지만 실행마다 무작위 키를 만들고 WARN을 남기므로 재시작하면 모든 로그인이 풀립니다. 실제 값은 저장소에 커밋하지 마세요. **`APP_CLUSTER_MODE=redis`에서는 필수**(5단계) |
| `APP_JWT_TTL` | `PT12H` | 로그인 토큰 유효 시간(ISO-8601, 예: `PT12H`, `P7D`) |
| `TOSS_CLIENT_KEY` | 빈 값 | (4단계) 토스페이먼츠 **테스트** 클라이언트 키(`test_gck_…`). 브라우저에 전달되는 공개 키입니다. 비우면 서버는 시작되지만 후원이 꺼집니다(`/api/payments/config` → `{"enabled":false}`, 후원 API는 503) |
| `TOSS_SECRET_KEY` | 빈 값 | (4단계) 토스페이먼츠 **테스트** 시크릿 키(`test_gsk_…`). **서버에서만** 사용(결제 승인 호출). 저장소·프론트엔드에 넣지 마세요 |
| `TOSS_API_BASE_URL` | `https://api.tosspayments.com` | 토스페이먼츠 API 주소(테스트 키도 같은 주소) |
| `TOSS_WEBHOOK_ENABLED` | `false` | `true`이면 `POST /api/payments/toss/webhook`이 승인 통지를 처리합니다(꺼져 있으면 404) |
| `TOSS_ALLOW_LIVE_KEYS` | `false` | 기본은 `test_`로 시작하지 않는 키를 거절하고 후원을 끕니다. 이 과제는 테스트 모드 전용이므로 `true`로 바꾸지 마세요 |
| `APP_FRONTEND_BASE_URL` | `http://localhost:5173` | 결제창 이후 토스가 브라우저를 돌려보낼 프론트엔드 주소(`<주소>/donations/success`, `/donations/fail`) |
| `APP_LIVE_GRACE_SECONDS` | `15` | 방송자 연결이 끊긴 뒤 방송을 종료 처리하기까지 기다리는 시간(초). 그 안에 재접속하면 방송이 유지됩니다 |
| `APP_WEBRTC_STUN_URLS` | `stun:stun.l.google.com:19302` | WebRTC STUN 서버(쉼표 구분). `GET /api/live/ice-servers`로 프론트에 전달됩니다 |
| `APP_WEBRTC_TURN_URLS` | 빈 값 | (선택) TURN 서버 URL(쉼표 구분). 비우면 TURN 미사용 |
| `APP_WEBRTC_TURN_USERNAME` / `APP_WEBRTC_TURN_CREDENTIAL` | 빈 값 | (선택) TURN 계정. 실제 값은 저장소에 커밋하지 마세요 |
| `APP_CLUSTER_MODE` | `local` | (5단계) `local`: 인스턴스 1개, 상태는 메모리(Redis 불필요). `redis`: 공유 상태(로그아웃 목록, 라이브 접속 정보·시그널링 중계, 고아 방송 정리)를 Redis에 둡니다. `redis`에서는 `APP_JWT_SECRET`이 **필수**이며 비어 있으면 시작이 실패합니다 |
| `SPRING_DATA_REDIS_URL` | 빈 값 | (5단계) Redis 접속 URL(`redis://default:<비밀번호>@host:6379`, TLS는 `rediss://`). 설정하면 host/port보다 우선합니다. Railway: `${{Redis.REDIS_URL}}` |
| `SPRING_DATA_REDIS_HOST` / `PORT` | `localhost` / `6379` | (5단계) URL 대신 host/port로 지정 |
| `APP_STORAGE_TYPE` | `local` | (5단계) `local`: `APP_STORAGE_DIR`의 디스크, `s3`: S3 호환 버킷(MinIO, Cloudflare R2, AWS S3). 인스턴스가 여러 개면 `s3`여야 합니다 |
| `APP_STORAGE_S3_ENDPOINT` | 빈 값 | (5단계, `s3`일 때) 예: MinIO `http://localhost:9000`, R2 `https://<account-id>.r2.cloudflarestorage.com`. AWS S3는 비워 둡니다 |
| `APP_STORAGE_S3_REGION` | `us-east-1` | (5단계) R2는 `auto` |
| `APP_STORAGE_S3_BUCKET` | `chzzk-videos` | (5단계) 버킷 이름 |
| `APP_STORAGE_S3_ACCESS_KEY` / `SECRET_KEY` | 빈 값 | (5단계) 접근 키. `s3`인데 비어 있으면 시작이 실패합니다. 저장소에 커밋 금지 |
| `APP_STORAGE_S3_PATH_STYLE` | `true` | (5단계) path-style 주소(MinIO 필수, R2/S3도 허용) |
| `APP_STORAGE_S3_CREATE_BUCKET` | `false` | (5단계) `true`이면 시작 시 버킷이 없을 때 만듭니다(MinIO용. R2는 대시보드에서 만듭니다) |
| `APP_LIVE_PRESENCE_TTL_SECONDS` / `HEARTBEAT_SECONDS` | `10` / `3` | (5단계) 방송자 접속 표시(Redis 키)의 유효 시간과 갱신 주기. 인스턴스가 죽으면 키가 만료됩니다 |
| `APP_LIVE_REAPER_INTERVAL_SECONDS` / `READY_TTL_SECONDS` | `5` / `3600` | (5단계) 고아 방송 정리 주기, 만들기만 하고 송출하지 않은 `READY` 방송을 정리하기까지의 시간 |
| `APP_INSTANCE_HEADER` | `false` | (5단계) `true`이면 모든 응답에 `X-Instance: <호스트명>`(Kubernetes에서는 파드 이름)을 붙입니다. 데모용 |
| `APP_READINESS_INCLUDE` | `readinessState,db` | (5단계) `/actuator/health/readiness`가 확인할 항목. 클러스터 모드에서는 `readinessState,db,redis` |
| `APP_SHUTDOWN_TIMEOUT` | `25s` | (5단계) 종료 신호(SIGTERM) 후 처리 중인 요청을 기다리는 시간(graceful shutdown) |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | (5단계) 프록시/인그레스가 보낸 `X-Forwarded-*`를 신뢰하는 방식 |
| `JAVA_OPTS` (이미지) | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | (5단계) 컨테이너 이미지의 JVM 옵션: 힙을 컨테이너 메모리 제한의 75%로 |
| `BACKEND_ADDRESS` (프론트 이미지) | `backend:8080` | (5단계) nginx가 `/api`·`/ws`를 보낼 백엔드 `host:port`(런타임 환경변수) |
| `VITE_API_BASE_URL` (프론트) | 빈 값 | 비우면 같은 Origin. 배포 시 백엔드 URL (WebSocket 주소도 여기서 http→ws, https→wss로 만듭니다) |
| `VITE_DEV_HTTPS` (프론트, 개발용) | `false` | `true`이면 개발 서버를 자체 서명 HTTPS로 실행 (`npm run dev:https`가 설정) |
| `VITE_ALLOWED_HOSTS` (프론트, 개발용) | 빈 값 | 개발 서버가 추가로 허용할 호스트(쉼표 구분). 터널 주소 접속 시 사용 |

### 배포 준비 (Railway 백엔드 + Cloudflare Pages 프론트 + Cloudflare R2 영상)

클라우드 배포는 과제 범위가 아니지만, 5단계의 모든 추가 기능이 환경변수로 켜고 끄도록 만들어져 있어 코드 수정 없이 옮길 수 있습니다. 아래는 나중에 배포할 때의 대응표이며 **이번 단계에서 실제 배포는 하지 않았습니다.**

**Railway (백엔드 + Postgres + Redis)**

- 서비스 만들기: 저장소의 `backend/`를 Root Directory로 지정하면 `backend/Dockerfile`로 빌드됩니다. Railway가 넣어 주는 `PORT`를 서버가 그대로 사용합니다.
- Postgres: `SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`, `SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}`, `SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}`
- Redis: Redis 서비스를 추가하고 `SPRING_DATA_REDIS_URL=${{Redis.REDIS_URL}}` (Railway가 제공하는 `REDIS_URL`을 이 앱의 변수 이름으로 매핑).
- 여러 인스턴스: `APP_CLUSTER_MODE=redis`, 서비스 Settings의 Replicas를 2 이상으로. 이때 `APP_JWT_SECRET`(32바이트 이상, `openssl rand -base64 48`)은 **필수**이고 모든 레플리카가 같은 값을 써야 합니다. 비워 두면 서버가 시작을 거부합니다(레플리카마다 다른 무작위 키를 쓰면 다른 레플리카에서 로그인이 풀리기 때문).
- 영상: Railway 디스크는 휘발성이고 레플리카끼리 공유되지 않으므로 `APP_STORAGE_TYPE=s3`와 아래 R2 설정을 씁니다.
- `APP_CORS_ALLOWED_ORIGINS`와 `APP_FRONTEND_BASE_URL`에 Cloudflare Pages 주소를 넣습니다. 헬스체크 경로는 `/actuator/health/readiness`(liveness는 `/actuator/health/liveness`)입니다.

**Cloudflare Pages (프론트엔드)**

- 빌드 설정: Root directory `frontend`, Build command `npm run build`, Output directory `dist`. `frontend/Dockerfile`은 Kubernetes/Docker 환경용이고 Pages는 위 설정으로 직접 빌드하므로 둘은 서로 영향을 주지 않습니다.
- 환경변수(빌드 시점): `VITE_API_BASE_URL=https://<railway 백엔드 주소>` (WebSocket 주소도 여기서 `wss://`로 만들어집니다).

**Cloudflare R2 (영상 저장소, S3 호환)**

- 버킷을 만들고 R2 API 토큰(Object Read & Write)을 발급받아 Railway 변수로 넣습니다: `APP_STORAGE_TYPE=s3`, `APP_STORAGE_S3_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com`, `APP_STORAGE_S3_REGION=auto`, `APP_STORAGE_S3_BUCKET=<버킷>`, `APP_STORAGE_S3_ACCESS_KEY` / `APP_STORAGE_S3_SECRET_KEY`. 버킷은 대시보드에서 만들므로 `APP_STORAGE_S3_CREATE_BUCKET`은 `false`로 둡니다.
- 영상은 백엔드를 거쳐 재생됩니다(`/api/broadcasts/{id}/video`가 `Range`를 R2의 `GetObject`로 전달해 206으로 응답). 영상을 누구나 볼 수 있는 기존 공개 정책을 유지하면서 버킷은 비공개로 두고 백엔드만 접근하게 하기 위해서입니다.
- **확인하지 못한 점**: R2에 직접 접속해 보지는 않았습니다. 같은 S3 API를 쓰는 MinIO로 시험했고, R2가 지원하지 않는 AWS SDK의 기본 체크섬 기능은 꺼 두었습니다(`WHEN_REQUIRED`).

## 4. DB 준비 방법

```bash
docker compose up -d
```

PostgreSQL 17 컨테이너(DB/사용자/비밀번호 기본값 `chzzk`, 포트 5432)가 실행되며 데이터는 Docker 볼륨(`chzzk-pgdata`)에 보존됩니다. 테이블은 백엔드 시작 시 **Flyway가 자동으로 생성**합니다(`backend/src/main/resources/db/migration/`의 `V1`(방송), `V2`(라이브), `V3`(회원·방송 소유자·댓글), `V4`(도네이션)). Hibernate는 스키마를 만들지 않고 검증만 합니다(`ddl-auto=validate`). 데이터를 초기화하려면 `docker compose down -v`를 실행합니다.

## 5. 설계 결정과 이유

- **Spring Boot + PostgreSQL + WebRTC (사용자 선택)**: 백엔드는 Spring Boot, 저장소는 PostgreSQL, 2단계 라이브 송출은 WebRTC로 진행하기로 정했습니다.
- **모노레포 + 프론트/백엔드 분리**: 과제가 "각 단계에서 앞 단계를 유지하며 확장"하도록 요구하므로 한 저장소에서 함께 발전시키고, 배포는 Railway(API)와 Cloudflare Pages(SPA)로 나눌 수 있게 했습니다. 배포 의존 값은 전부 환경변수입니다.
- **영상 등록 방식 두 가지 모두 지원 (파일 업로드 + 외부 URL)**: 과제가 "영상 파일 업로드 또는 저장된 영상 연결 방식은 자유"라고 했으므로 둘 다 제공합니다. 정확히 하나만 입력하도록 서버가 검증하며 DB 체크 제약으로도 보장합니다.
- **`broadcast` 단일 테이블**: 이후 단계의 라이브 방송도 같은 목록·상세에 나와야 하므로 `type`(현재 RECORDED, 2단계에서 LIVE 추가)과 `status` 컬럼을 두고 영상 위치 컬럼(저장 파일명/원본명/콘텐츠 타입/크기 또는 `video_url`)을 함께 둡니다. 스키마는 Flyway SQL로 버전 관리합니다.
- **`VideoStorage` 인터페이스 + 로컬 디스크 구현**: 지금은 디스크에 저장하되, 인터페이스 뒤에 숨겨 두었고, 5단계에서 S3 호환(MinIO/R2) 구현(`S3VideoStorage`)을 추가했습니다(`APP_STORAGE_TYPE`으로 선택). 저장 파일명은 UUID로 새로 만들고(확장자는 검증 후 사용), 읽을 때도 이름 형식과 경로 탈출 여부를 검사해 경로 조작(path traversal)을 막습니다.
- **Range 지원 스트리밍**: `/api/broadcasts/{id}/video`가 `ResponseEntity<Resource>`를 반환해 Spring MVC가 `Range` 요청(206)을 처리하므로 `<video>`에서 탐색(seek)이 가능합니다. 외부 URL 방송은 JSON의 `playbackUrl`을 플레이어가 직접 사용합니다.
- **인증 없음 → 3단계에서 Spring Security로 대체**: 1~2단계는 모든 엔드포인트가 공개였지만, 3단계부터 Spring Security가 요청을 걸러 냅니다(자세한 내용은 아래 "3단계에서 추가한 결정").
- **오류 응답 통일**: `@RestControllerAdvice`가 검증 실패는 400, 로그인 필요·토큰 오류·로그인 실패는 401, 소유자/작성자가 아님은 403, 없는 리소스는 404, 아이디 중복은 409, 용량 초과는 413으로 `{status, error, message}` JSON을 반환합니다. Spring Security가 필터 단계에서 내는 401/403도 같은 모양으로 쓰도록 진입점·접근 거부 핸들러를 설정했습니다.

2단계에서 추가한 결정:

- **WebRTC P2P + 시그널링 전용 서버 (미디어 서버 없음)**: 영상은 방송자 휴대폰 브라우저에서 시청자 브라우저로 직접 전송되고, 서버는 WebSocket(`/ws/live/{id}`)으로 SDP/ICE 메시지만 중계합니다. 별도 미디어 서버(SFU, RTMP→HLS 변환)를 두지 않아 구성이 가장 단순하고 휴대폰 브라우저만으로 송출할 수 있기 때문입니다. **한계**: 시청자 1명마다 방송자 휴대폰이 영상을 한 번씩 더 업로드하므로(시청자별 `RTCPeerConnection`) 시청자 수가 소수(대략 3~5명)일 때만 적합합니다. 많은 시청자가 필요하면 SFU(LiveKit, mediasoup 등)가 필요합니다.
- **방송 상태 `READY → LIVE → ENDED`**: 방송을 만들면 `READY`이고, 방송자 휴대폰이 키로 시그널링 소켓에 접속해 송출을 시작하는 순간 `LIVE`(`started_at` 기록)가 됩니다. 만들기만 하고 송출하지 않은 방송이 "방송 중"으로 목록에 뜨는 것을 막기 위해서이며, `READY` 방송은 상태를 지정하지 않은 목록에서 숨깁니다. 종료되면 `ENDED`(`ended_at` 기록)입니다.
- **방송자 키(`broadcasterKey`) — 3단계에서 제거됨**: 2단계에서는 방송 생성 응답에 한 번만 내려가는 무작위 토큰(DB에는 SHA-256 해시만 저장)으로 소켓 방송자 `join`과 `end`를 보호했습니다. 3단계에서 로그인한 소유자 검사로 대체하고, 코드와 `broadcaster_key_hash` 컬럼(V3 마이그레이션)을 모두 지웠습니다. 키는 사용자 인증이 아니라 "링크를 아는 사람"을 막는 임시 장치였고, 로그인 기반 소유권이 더 정확하기 때문입니다.
- **방송자 연결 끊김 유예(grace) 시간**: 방송자 소켓이 끊기면 바로 종료하지 않고 `APP_LIVE_GRACE_SECONDS`(기본 15초) 동안 기다립니다. 휴대폰이 잠깐 네트워크를 잃어도 같은 소유자 토큰으로 다시 접속하면 방송이 이어지고, 대기 중이던 시청자와는 다시 협상합니다. 시간이 지나면 `ENDED`로 바꾸고 시청자에게 `broadcast-ended`를 보냅니다. 서버가 재시작되면 메모리의 방송실(room)이 사라지므로 시작 시 `READY/LIVE`로 남은 라이브 방송을 모두 `ENDED`로 정리합니다. 방송실 상태가 메모리에 있어 2단계에서는 백엔드 인스턴스 1개를 전제로 했고, 5단계에서 여러 인스턴스를 지원하도록 바꿨습니다(아래 "5단계에서 추가한 결정"; 시작 시 정리는 로컬 모드에서만 하고 클러스터 모드에서는 주기적 정리기로 대체).
- **STUN/TURN은 환경변수 → `GET /api/live/ice-servers`**: 프론트에 주소를 하드코딩하지 않고 백엔드 설정을 내려줍니다. 기본은 공개 STUN만이며, 통신사망(대칭형 NAT) 등 직접 연결이 안 되는 환경을 위해 TURN을 환경변수로 추가할 수 있습니다. (정적 TURN 계정은 모든 클라이언트에 노출되므로 필요하면 3단계에서 단기 자격증명으로 바꿀 수 있습니다.)
- **휴대폰 테스트용 HTTPS는 Vite 개발 서버에서 종단**: 브라우저는 보안 컨텍스트(HTTPS 또는 localhost)에서만 카메라를 허용합니다. 휴대폰은 PC의 LAN IP로 접속하므로 HTTPS가 필요한데, `@vitejs/plugin-basic-ssl`(`VITE_DEV_HTTPS=true`일 때만 활성화)로 개발 서버만 HTTPS로 만들고 `/api`·`/ws`는 같은 서버가 `localhost:8080`으로 프록시하므로 백엔드에는 TLS 설정이 필요 없습니다. 프록시는 브라우저의 `Origin` 헤더를 제거해 전달합니다(`https://<PC IP>:5173`에서 온 요청을 백엔드가 `http`로 보고 교차 출처로 거절하는 것을 막기 위함이며, 브라우저는 Vite 서버하고만 통신하므로 CORS 보호가 필요 없습니다). 배포 시에는 백엔드 허용 Origin(`APP_CORS_ALLOWED_ORIGINS`)이 REST CORS와 WebSocket 허용 Origin에 똑같이 적용됩니다.
- **시그널링 프로토콜은 JSON 메시지 + 서버가 `from`을 찍어 중계**: `join`(role: broadcaster/viewer), `viewer-joined`/`viewer-left`, `offer`/`answer`/`ice-candidate`, `broadcast-ended`, `error`. 방송자는 `to`(시청자 id)를 지정해야 하고, 시청자의 메시지는 항상 방송자에게 갑니다. 서버가 발신자 id(`from`)를 직접 채우므로 다른 시청자 흉내를 낼 수 없습니다. 존재하지 않거나 종료된 방송, 소유자가 아닌 방송자 `join`, 방향이 틀린 메시지는 `error`로 응답합니다.
- **상태 필터와 라이브 우선 목록**: `GET /api/broadcasts?type=LIVE&status=LIVE`처럼 `type`, `status`로 거를 수 있고, 목록은 항상 `LIVE`를 위에 둔 최신순입니다. 목록 화면은 "방송 중"과 "지난 방송" 구역으로 나눠 `방송 중`/`종료` 배지를 보여 줍니다.
- **스키마는 V2 마이그레이션으로 확장**: `V2__add_live_broadcast.sql`이 `started_at`, `ended_at`, `broadcaster_key_hash`를 추가하고, 허용 값을 넓히기 위해 같은 이름의 CHECK 제약을 다시 만듭니다(PostgreSQL은 CHECK를 제자리에서 수정할 수 없음). 녹화방송은 항상 `ENDED`, 라이브는 영상 파일/URL 없이 키 해시 필수라는 제약도 DB에 추가했습니다.

3단계에서 추가한 결정:

- **JWT 베어러 토큰(stateless) — 쿠키 세션 대신**: 프론트엔드(Cloudflare Pages)와 API(Railway)는 **서로 다른 사이트**라서, 세션 쿠키를 쓰면 브라우저의 서드파티 쿠키 차단·`SameSite`·CSRF 문제를 겪습니다. `Authorization: Bearer` 헤더는 쿠키가 아니므로 이 문제가 없고 CSRF 토큰도 필요 없습니다(그래서 CSRF 보호는 꺼 두었습니다). 서버가 상태를 갖지 않아 배포도 단순합니다. 대신 토큰을 `localStorage`에 두므로 XSS에는 쿠키(`HttpOnly`)보다 약합니다. 사용자 입력은 React가 이스케이프해서만 출력하고 HTML로 삽입하지 않는 것으로 줄였습니다.
- **JWT 라이브러리 대신 Spring Security의 `oauth2-resource-server` + Nimbus**: 토큰 발급(`NimbusJwtEncoder`)과 검증(`NimbusJwtDecoder`)에 대칭키(HS256)를 쓰고, 베어러 헤더 파싱·401 처리·`@AuthenticationPrincipal Jwt`를 프레임워크가 제공해 직접 만든 필터를 줄였습니다. 새 의존성은 `spring-boot-starter-security`와 `spring-boot-starter-oauth2-resource-server` 둘뿐이고 서명·만료 검증은 검증된 구현을 그대로 씁니다. 토큰에는 사용자 id(`sub`), 고유 id(`jti`), 발급·만료 시각만 넣습니다.
- **비밀키는 `APP_JWT_SECRET`, 없으면 무작위 생성**: 32바이트 미만이면 시작을 거부하고(약한 키 방지), 비어 있으면 서버는 뜨되 실행마다 무작위 키를 만들며 WARN을 남깁니다. 저장소에는 자리표시 값만 있습니다(`.env.example`).
- **로그아웃 = 클라이언트 폐기 + 서버 거부 목록(작은 메모리 목록)**: 무상태 토큰은 원래 만료 전까지 유효하므로, `POST /api/auth/logout`이 토큰의 `jti`를 만료 시각까지 거부 목록에 올려 그 토큰이 바로 무효가 되게 했습니다. 만료된 항목은 저장 시 정리됩니다. 로컬 모드에서는 메모리 방식이라 재시작·다중 인스턴스에 한계가 있었고, 5단계의 클러스터 모드에서는 같은 인터페이스(`TokenDenylist`)의 Redis 구현이 대신합니다(키 `auth:denylist:{jti}`, TTL = 토큰 남은 수명).
- **인가는 서버가 강제, 두 층으로 나눔**: "로그인했는가"는 `SecurityConfig`(`anyRequest().authenticated()` + 공개 GET 예외 목록, 기본이 비공개라 새 엔드포인트를 실수로 열어 두지 않음)가, "내 방송/내 댓글인가"는 서비스 계층(`BroadcastService.requireOwner`, `CommentService.requireAuthor`)이 판단해 401과 403을 구분합니다. 프론트의 버튼 표시는 편의일 뿐이며 같은 규칙이 서버에서 다시 검사됩니다.
- **시청·목록·댓글 읽기는 공개 (실제 치지직과 같은 정책)**: 방송은 불특정 다수가 링크로 들어와 보는 서비스이고, 로그인을 강제하면 시청자가 줄며 공유 링크도 불편해집니다. 로그인이 필요한 것은 "만들기·관리·송출·댓글 쓰기"처럼 **주체가 필요한 동작**뿐입니다. 이 결정은 공개 GET 목록(`/api/broadcasts`, `/{id}`, `/{id}/video`, `/{id}/comments`, `/api/live/ice-servers`)과 시그널링 viewer `join`에 코드로 드러나 있습니다. 공개 GET 요청에 낡은 토큰이 붙어 있어도 서버가 그 헤더를 무시하므로 만료된 토큰 때문에 목록이 깨지지 않습니다.
- **WebSocket 인증은 `join` 메시지에 토큰**: 브라우저의 `WebSocket`은 헤더를 못 붙이고, `?token=` 쿼리는 프록시·접근 로그에 남기 쉬워서 첫 메시지(`join`)에 `token`을 싣는 방식을 택했습니다. 핸들러가 같은 `JwtDecoder`로 검증하고(서명·만료·로그아웃 목록), 소유자가 아니면 `FORBIDDEN`, 토큰 문제면 `UNAUTHORIZED`를 보내고 닫습니다. 토큰은 로그에 남기지 않습니다. 시청자 `join`은 로그인이 필요 없습니다.
- **로그인 실패 메시지는 하나로**: "없는 아이디"와 "틀린 비밀번호"를 같은 401 문구로 답하고, 없는 아이디도 더미 해시와 BCrypt 비교를 해 응답 시간 차이로 아이디 존재 여부를 알아내기 어렵게 했습니다. 아이디 중복은 가입 시에만 409로 알립니다(경쟁 상황은 DB 유니크 제약이 최종 판정).
- **스키마 `V3`**: `users`(유니크 소문자 아이디, BCrypt 해시, 닉네임), `broadcast.owner_id`(외래키, **옛 행 때문에 NULL 허용**이지만 앱은 항상 채움), `comment`(`ON DELETE CASCADE`로 방송 삭제 시 함께 삭제, 내용 1~500자 `CHECK`, `(broadcast_id, created_at DESC, id DESC)` 인덱스). 2단계의 `broadcaster_key_hash`는 컬럼과 그 컬럼을 참조하던 CHECK 제약을 정리해 완전히 제거했습니다.
- **댓글 갱신은 폴링**: 라이브 방송에서도 댓글은 4초마다 다시 불러옵니다. 이미 있는 시그널링 소켓은 방송자·시청자 협상용이라 댓글 방송 로직을 섞지 않았고, 폴링은 서버 상태·재접속 처리가 필요 없어 단순하며 시청자 수가 소수인 이 과제 규모에 충분합니다. 실시간성이 더 필요하면 댓글 전용 WebSocket/SSE로 바꿀 수 있습니다. 화면이 보이지 않는 탭에서는 폴링을 건너뜁니다.
- **댓글 수정·삭제는 작성자만**: 방송 소유자도 남의 댓글은 지울 수 없습니다(관리자·신고 기능은 범위 밖). 목록은 최신 N개 페이지로 주고 `더 보기`로 이전 페이지를 이어 붙입니다.
- **CORS에 `Authorization` 허용**: Spring Security의 CORS 필터가 인증보다 먼저 프리플라이트(OPTIONS)에 답하도록 `CorsConfigurationSource` 빈으로 바꿨고, 허용 헤더에 `Authorization`을 명시했습니다(와일드카드는 이 헤더를 포함하지 않습니다). 허용 Origin은 계속 `APP_CORS_ALLOWED_ORIGINS`입니다.

4단계에서 추가한 결정:

- **결제 서비스는 토스페이먼츠**: 개발자센터 회원가입만으로 무료 **테스트 키**를 받을 수 있어 사업자 등록 없이 과제 환경에서 끝까지 시험할 수 있고, 한국어 문서와 결제창 SDK v2 + 서버 승인 API(클라이언트 키/시크릿 키 분리)가 갖춰져 있습니다. 현재 문서가 새 연동에 권장하는 **결제창형 결제**(`widgets()` + `renderPaymentWindow()`)를 썼고, 구버전 결제창(`payment()`)은 쓰지 않았습니다.
- **서버 쪽 검증이 확정의 유일한 근거**: 브라우저가 알려 주는 성공 표시·쿼리스트링(금액 포함)은 조작할 수 있으므로 확정에 쓰지 않습니다. 서버가 저장된 주문(금액, 주문 당사자, 방송 소유자)과 토스의 승인 응답(`DONE`, 주문번호, `paymentKey`, 총액)을 모두 대조합니다. 토스에 보내는 금액도 요청값이 아니라 저장된 금액입니다. 키는 테스트 키만 허용하므로 확정되는 결제는 테스트 결제뿐입니다.
- **중복 방지는 DB가 보장**: 애플리케이션의 `if`만 믿지 않고 `UNIQUE(order_id)`·`UNIQUE(payment_key)`·`CHECK`를 두고, 확정은 행 잠금(`FOR UPDATE`) + 조건부 `UPDATE … WHERE status='PENDING'`으로 합니다. 잠금 덕분에 동시 요청이 와도 토스 승인 호출조차 한 번만 나가고, 제약이 최후 방어선입니다. 반복 요청은 에러가 아니라 **같은 결과(200)** 로 답해 새로고침·재시도·웹훅 재전송을 안전하게 만들었습니다(다른 `paymentKey`만 409).
- **확실한 거절만 `FAILED`, 불확실하면 `PENDING` 유지**: 토스가 거절하면 최종 상태로 닫지만, 타임아웃·5xx처럼 승인 여부를 모르는 경우에 `FAILED`로 닫으면 돈은 빠져나갔는데 후원은 없는 상태가 될 수 있어 `PENDING`으로 두고 재시도하게 했습니다.
- **웹훅은 본문을 믿지 않고 재조회**: 웹훅 URL은 누구나 호출할 수 있으므로 본문의 상태·금액을 믿지 않고 `paymentKey`만 받아 토스에서 다시 조회해 같은 검증을 합니다(위조된 통지로는 아무것도 확정되지 않음). 그래서 서명 헤더 검증은 생략했습니다(과제 범위). 기본은 꺼져 있습니다.
- **자기 방송 후원은 막음(400 + DB `CHECK`)**: 자기가 자기에게 후원하면 후원 합계·내역만 부풀릴 수 있고 실제 의미가 없으며, 실제 서비스도 대부분 막습니다. 결제 전에 서버가 거절하므로 불필요한 결제창도 열리지 않습니다. 소유자가 없는 옛 방송(`owner_id` NULL)은 받을 사람이 없어 후원할 수 없습니다.
- **방송을 지워도 후원 기록은 남김**: `donation.broadcast_id`는 `ON DELETE SET NULL`입니다. 방송자가 받은 돈의 기록이므로 방송과 함께 지우지 않으며, 방송이 지워진 뒤에는 대기 중 주문을 확정하지 않습니다. 확정 직전에 방송 소유자가 바뀌었는지도 다시 확인합니다.
- **금액·메시지 한도는 세 겹**: UI(즉시 안내), 서버(400), DB `CHECK`(1,000~1,000,000원, 메시지 100자) 모두에서 같은 규칙을 검사합니다. 서버를 우회해도 DB가 막습니다.
- **키는 환경변수 전용, 없어도 서버는 시작**: 키가 없거나 `test_`가 아니면 후원 기능만 꺼집니다(503/`enabled:false`). 다른 단계 기능은 그대로 동작해 채점·실행 환경에 키가 없어도 앱이 뜹니다. 코드와 `.env.example`에는 키가 없고 자리표시자만 있습니다.
- **`customerKey`는 요청마다 무작위 UUID**: 토스는 추측하기 어려운 22자 이상의 값을 요구하므로 사용자 id·이메일 대신 무작위 값을 씁니다(자동결제·저장된 결제수단 기능은 쓰지 않음).
- **후원 목록은 공개 + 합계는 SQL `SUM`**: 댓글처럼 누구나 볼 수 있고, `CONFIRMED`만 집계합니다. 합계는 목록 길이 제한과 무관하게 DB가 계산합니다. 라이브 방송은 댓글처럼 폴링합니다.

5단계에서 추가한 결정:

- **로컬 오케스트레이터는 kind(Kubernetes in Docker) + HPA**: 과제가 "HPA OK"라고 했고, `HorizontalPodAutoscaler`가 CPU 지표로 파드 수를 조절하는 표준 방식이라 직접 스케일러를 만들 이유가 없습니다. kind는 Docker만 있으면 PC 안에 클러스터를 만들고 `kind load docker-image`로 로컬에서 빌드한 이미지를 레지스트리 없이 넣을 수 있습니다. docker compose만으로는 지표 기반 자동 증감이 없어 요구사항을 채울 수 없습니다.
- **기본 동작은 그대로 두는 모드 스위치(`APP_CLUSTER_MODE`)**: `local`(기본)은 이전 단계와 똑같이 메모리로 동작해 `./gradlew bootRun`과 기존 테스트가 Redis 없이 통과합니다. `redis`일 때만 Redis 구현 빈이 만들어집니다(`@ConditionalOnProperty`). 로컬 모드에서 Redis 스타터가 만드는 헬스 인디케이터는 DOWN이 되므로 같은 이름의 빈으로 대체해 `/actuator/health`가 초록으로 남게 했습니다.
- **어떤 상태를 어디로 옮겼나** (파드가 죽거나 늘어나도 남아야 하는 것):

  | 상태 | 이전 | 5단계 |
  | --- | --- | --- |
  | 사용자·방송·댓글·도네이션 | PostgreSQL | 그대로(이미 공유 저장소) |
  | 로그인 토큰 검증 | 인스턴스마다 무작위 키 가능 | 모든 파드가 같은 `APP_JWT_SECRET`을 사용. 클러스터 모드에서 비어 있으면 **시작 실패**(조용히 로그인이 깨지는 것보다 빨리 실패) |
  | 로그아웃 목록 | 메모리 | Redis `auth:denylist:{jti}` + TTL(토큰 남은 수명) |
  | 업로드 영상 | 파드 디스크 | S3 호환 저장소(MinIO, 배포 시 R2) |
  | 라이브 방송자·시청자 접속 정보, 시그널링 | 메모리 `LiveRoom` | Redis(접속 키 + 시청자 정렬 집합 + pub/sub 채널). 각 파드는 **자기 WebSocket만** 들고 있음 |
  | 방송 종료 | 파드의 타이머 | DB 조건부 `UPDATE`(원자적) + Redis 접속 키 확인 |

- **라이브 시그널링을 여러 파드에 걸쳐 중계**: 방송자와 시청자가 서로 다른 파드에 붙을 수 있으므로, 상대에게 가는 모든 메시지(`viewer-joined`, `offer`/`answer`/`ice-candidate`, `broadcast-ended`)를 Redis pub/sub 채널(`chzzk:live`)에 봉투(`broadcastId`, 받는 사람 id)로 한 번 발행하고, 모든 파드가 받아 **자기가 가진 소켓**에만 전달합니다(`LiveCoordinator` 인터페이스: 로컬은 메모리 + 즉시 호출, 클러스터는 Redis). 존재 여부(방송자가 접속 중인지, 그 시청자가 있는지)와 시청자 수는 Redis에서 읽습니다. 방송자 접속은 TTL이 짧은 키(`live:{id}:presence`)이고 파드가 하트비트로 갱신하므로 **파드가 죽으면 키가 스스로 만료**됩니다. 새 방송자 연결이 다른 파드에서 들어오면 이전 연결은 `replace` 메시지로 닫힙니다.
- **방송 종료는 한 번만, 그리고 파드 시작이 방송을 끊지 않게**: 방송자가 끊기면 유예(grace) 뒤에 "접속 키가 여전히 없으면" 종료합니다(다른 파드로 재접속했다면 유지). 종료는 `UPDATE … WHERE id=? AND status IN ('READY','LIVE')`가 1행을 바꾼 호출만 성공하므로 여러 파드가 동시에 시도해도 `broadcast-ended`가 한 번만 발행됩니다. 기존 `LiveStartupCleanup`은 시작할 때 모든 `READY/LIVE` 방송을 끝내는데, 스케일 아웃으로 새 파드가 뜰 때마다 **다른 파드에서 송출 중인 방송을 끊어 버리는** 문제가 있으므로 클러스터 모드에서는 비활성화하고 주기적 정리기(`LiveReaper`)로 대체했습니다. 정리기는 접속 키가 유예 시간보다 오래 없는 방송만 끝내며(파드가 통째로 죽어 타이머가 없어진 경우를 처리), Redis 락으로 파드들이 동시에 훑지 않게 하고 조건부 UPDATE로 중복이 일어나도 안전합니다. 로컬 모드는 이전과 같이 시작 시 정리합니다.
- **영상은 `S3VideoStorage` + 느린 탐색(lazy seek) 스트림으로 `Range` 유지**: Spring MVC는 `Range` 요청에 대해 리소스 스트림을 `start`만큼 건너뛴 뒤 구간을 복사합니다. S3 리소스의 스트림은 `skip`을 실제로 읽지 않고 시작 위치만 기록했다가 첫 `read`에서 `GetObject`에 `Range: bytes=<start>-`를 붙여 호출하므로 필요한 구간만 가져오고, 응답 헤더(206, `Content-Range`, `Content-Length`)는 Spring이 만듭니다. 업로드는 R2가 길이를 요구해서 임시 파일에 받은 뒤 `PutObject`합니다. AWS SDK의 기본 체크섬은 MinIO/R2 호환을 위해 `WHEN_REQUIRED`로 꺼 두었습니다.
- **MinIO 이미지**: MinIO가 Docker Hub 공식 이미지 배포를 중단해(`minio/minio` 풀 실패 확인) 마지막 동결 빌드인 `bitnamilegacy/minio`(고정 태그)를 로컬 데모와 테스트에 씁니다. 보안 업데이트가 없으므로 로컬 전용이며 배포는 R2/S3를 씁니다.
- **kind 호스트 포트는 8090**: 개발 중 백엔드가 `localhost:8080`을 쓰고 있어서(`bootRun`) 클러스터는 호스트 8090 → NodePort 30080으로 연결했습니다. 바꾸려면 `deploy/kind-config.yaml`의 `hostPort`와 ConfigMap의 `APP_CORS_ALLOWED_ORIGINS`를 함께 바꾸세요.
- **HPA 설정**: CPU 평균 사용률 50%(요청 250m 기준 125m), 최소 2 / 최대 6. 스케일 아웃은 15초마다 최대 2배(또는 +2)로 빠르게, 스케일 인은 안정화 창 60초 + 30초마다 1개씩으로 천천히 합니다(60초는 데모를 짧게 하려는 값이며 운영에서는 더 길게 잡습니다). 최소 2개라서 한 파드가 죽거나 롤링 업데이트(`maxUnavailable: 0`) 중에도 서비스가 이어집니다. 파드는 `readinessProbe`가 DB·Redis까지 확인한 뒤에야 Service에 들어가고, 종료 시 `preStop sleep` + Spring graceful shutdown으로 처리 중인 요청을 마무리합니다.
- **요청 분산은 두 층**: 브라우저 → nginx(프론트 Deployment 2개, NodePort) → `backend` ClusterIP Service → 준비된 백엔드 파드. kube-proxy는 **연결 단위**로 분산하므로 nginx → 백엔드에 keep-alive 풀을 두되 `keepalive_requests`·`keepalive_time`으로 연결을 주기적으로 갱신해, 오토스케일로 새로 생긴 파드도 트래픽을 받게 했습니다. (첫 부하 시험에서 keep-alive 없이 요청마다 새 TCP 연결을 만들자 임시 포트가 고갈돼 502가 났고, 그래서 이 설정을 넣었습니다 — "확인 결과 기록" 참고.)
- **비밀값은 저장소에 없음**: `deploy/scripts/up.sh`가 실행할 때마다 `APP_JWT_SECRET`, Postgres·MinIO 비밀번호를 무작위로 만들어 `kubectl create secret`으로 클러스터에만 넣습니다. 저장소에는 자리표시자뿐인 `deploy/k8s/secret.example.yaml`만 있고, 이 파일은 `kustomization.yaml`에 넣지 않아 실수로 적용되지 않습니다.
- **프론트엔드 이미지는 Pages 빌드와 별개**: `frontend/Dockerfile`은 `VITE_API_BASE_URL` 빌드 인자(기본 빈 값 = 같은 Origin)로 빌드하고, nginx가 `/api`·`/ws`(WebSocket 업그레이드, 긴 read timeout)를 `BACKEND_ADDRESS`로 프록시합니다. Cloudflare Pages는 이 이미지를 쓰지 않고 `npm run build`로 직접 빌드합니다.
- **알려진 한계**: Postgres·Redis·MinIO는 각각 1개입니다(오토스케일 대상은 백엔드). Redis가 재시작되면 로그아웃 목록과 접속 키가 사라집니다(접속 키는 하트비트로 최대 몇 초 안에 복구되고, 로그아웃된 토큰은 만료 전까지 다시 유효해질 수 있음). 시청자는 방송자 휴대폰과 P2P로 연결되므로 파드 증감은 이미 연결된 영상 전송에는 영향이 없고, 시그널링 소켓이 붙어 있던 파드가 스케일 인으로 종료되면 그 소켓은 끊깁니다(방송자는 유예 시간 안에 재접속하면 유지, 시청자는 새로고침). 스케일 인 때 소켓이 끊기지 않게 하려면 연결 드레이닝이 더 필요합니다.

## 1단계: 인증·인가 없는 녹화방송

> **3단계 현재 상태**: 이 절은 1단계 구현 범위의 기록입니다. 3단계부터 녹화방송 **등록은 로그인이 필요**하고(소유자 = 로그인한 사용자), 목록·상세·재생은 여전히 로그인 없이 가능합니다. 아래 절차의 "등록"과 `curl` 예시는 이 점을 반영해 고쳐 두었습니다.

### 구현 범위

- 녹화방송 등록: 제목 + (영상 파일 업로드 **또는** 영상 URL) — `POST /api/broadcasts/recorded` (multipart)
- 방송 목록(최신순, `type` 필터 선택) `GET /api/broadcasts`, 상세 `GET /api/broadcasts/{id}`
- 업로드 영상 재생용 스트리밍 `GET /api/broadcasts/{id}/video` (HTTP Range 지원)
- 방송 메타데이터와 영상 위치를 PostgreSQL에 저장 (업로드 파일은 `APP_STORAGE_DIR`에 저장)
- 프론트엔드: 목록 `/`, 등록 `/recordings/new`, 상세·플레이어 `/broadcasts/:id`
- 1단계 당시에는 로그인·권한 검사 없이 모든 화면과 API가 공개였습니다. (3단계 현재: 등록만 로그인 필요, 아래 "3단계" 참고)
- 자동화 테스트로 확인되는 범위: 백엔드 Testcontainers(PostgreSQL) + MockMvc 통합 테스트 — 파일 등록, URL 등록, 검증 실패(제목 없음/소스 둘 다·없음/잘못된 URL/비영상 파일), 목록·상세·404, 영상 응답 본문과 `Range` 응답(206), DB 영속화, 저장소 경로 조작 방어; 프론트엔드 vitest — URL 조합, 폼 검증. (브라우저 화면과 실제 영상 재생은 자동화 테스트 범위 밖입니다.)

### 직접 확인 절차

1. `docker compose up -d`로 DB를 띄우고, `backend`에서 `./gradlew bootRun`, `frontend`에서 `npm install && npm run dev`를 실행합니다. (3단계 현재: 등록하려면 먼저 상단의 "회원가입"으로 계정을 만들어 로그인해야 합니다.)
2. http://localhost:5173 을 열어 목록이 비어 있다는 안내가 나오는지 확인합니다.
3. "녹화방송 등록"에서 제목을 입력하고 **파일 업로드**로 영상(mp4 등)을 선택해 등록합니다. 등록 후 상세 화면으로 이동하는지 확인합니다.
4. 상세 화면의 플레이어에서 영상이 재생되고, 재생 바를 이동(seek)할 수 있는지 확인합니다.
5. 다시 "녹화방송 등록"에서 **영상 URL 연결**을 선택하고 재생 가능한 영상 URL(예: 직접 접근 가능한 `.mp4` 주소)로 등록한 뒤, 상세 화면에서 재생되는지 확인합니다.
6. 목록 화면으로 돌아가 두 방송이 최신순 카드(제목, 유형 배지, 등록 시각)로 보이는지, 카드를 눌러 상세로 이동하는지 확인합니다.
7. 검증 확인: 제목을 비우거나, 잘못된 URL(`ftp://...`)을 넣으면 오류 메시지가 표시되고 등록되지 않는지 확인합니다.
8. **재시작 후 유지 확인**: 백엔드를 종료(Ctrl+C)했다가 `./gradlew bootRun`으로 다시 시작합니다. 페이지를 새로고침했을 때 목록에 방송이 그대로 남아 있고, 업로드한 영상이 다시 재생되는지 확인합니다. (DB는 Docker 볼륨, 영상 파일은 `backend/uploads`에 남아 있습니다.)
9. API 직접 확인(선택, 토큰 얻는 법은 "3단계: 직접 확인 절차" 참고): `curl -H "Authorization: Bearer $TOKEN" -F title=테스트 -F videoUrl=https://example.com/a.mp4 http://localhost:8080/api/broadcasts/recorded` 후 `curl http://localhost:8080/api/broadcasts` (토큰 없이 등록하면 401)

## 2단계: 인증·인가 없는 휴대폰 라이브 송출

> **3단계 현재 상태**: 이 절은 2단계 구현 범위의 기록입니다. 3단계부터 라이브 방송 **생성은 로그인이 필요**하고, 송출(시그널링 방송자 `join`)과 종료(`POST /api/broadcasts/{id}/end`)는 **그 방송의 소유자만** 할 수 있습니다. 2단계의 `broadcasterKey`는 제거되었고(`X-Broadcaster-Key` 헤더도 사라짐), 시청과 목록은 계속 공개입니다. 아래 절차의 해당 부분은 이에 맞게 고쳐 두었습니다.

### 구현 범위

- 휴대폰(또는 PC) 브라우저의 카메라·마이크로 라이브 방송을 **시작·종료**합니다. 송출 방식은 WebRTC(P2P)이고 서버는 WebSocket 시그널링과 방송 상태 저장만 맡습니다.
- 2단계 당시에는 로그인 없이 누구나 방송을 만들고 시청할 수 있었고, `broadcasterKey`가 남의 송출·종료를 막는 임시 장치였습니다. **3단계에서 이를 로그인한 소유자 검사로 대체했습니다.** 댓글은 3단계, 도네이션은 4단계입니다.
- 백엔드
  - `POST /api/broadcasts/live` `{title}` → 라이브 방송 생성(상태 `READY`) (3단계 현재: 로그인 필요, 호출한 사용자가 소유자, 응답은 방송 정보이며 2단계의 `broadcasterKey`는 없음)
  - `POST /api/broadcasts/{id}/end` → 종료(`ENDED`). 3단계 현재: 소유자만(로그인 없음 401, 다른 사용자 403), 이미 종료됐으면 그대로 200(멱등). (2단계에서는 `X-Broadcaster-Key` 헤더로 보호)
  - `GET /api/broadcasts?type=LIVE&status=LIVE` 상태·유형 필터, 목록은 `LIVE`를 맨 위에 둔 최신순(상태 미지정 시 `READY`는 숨김)
  - `WS /ws/live/{broadcastId}` 시그널링: `join`(broadcaster는 소유자의 로그인 토큰 `token` 필요 / viewer는 로그인 불필요), `viewer-joined`, `viewer-left`, `offer`, `answer`, `ice-candidate`, `broadcast-ended`, `error`. 종료되었거나 시작 전인 방송에 시청자가 들어오면 `error`
  - 방송자 소켓이 끊기면 `APP_LIVE_GRACE_SECONDS`(기본 15초) 뒤에 `ENDED`로 바꾸고 시청자에게 `broadcast-ended` 전송(그 안에 같은 소유자 토큰으로 재접속하면 유지)
  - `GET /api/live/ice-servers`: STUN/TURN 설정(`APP_WEBRTC_*`)을 프론트에 전달. 허용 WebSocket Origin은 `APP_CORS_ALLOWED_ORIGINS`와 동일
  - Flyway `V2__add_live_broadcast.sql`: `LIVE` 유형, `READY/LIVE/ENDED` 상태, `started_at`/`ended_at`/`broadcaster_key_hash`(3단계 V3에서 제거)
- 프론트엔드
  - 목록 `/`: "방송 중" 구역과 "지난 방송" 구역, `방송 중`/`종료` 배지, "라이브 방송 시작" 링크
  - `/live/new` 제목 입력 → `/live/:id/studio` 방송자 스튜디오: 카메라·마이크 미리보기(내 화면은 음소거), 전면/후면 카메라 전환, `방송 시작`/`방송 종료`, 시청자 수. HTTPS가 아니거나 카메라 API가 없을 때, 권한이 거부되었을 때 한국어 안내 표시
  - `/broadcasts/:id`: 라이브 방송은 `<video autoplay playsinline>`으로 WebRTC 스트림 시청(자동재생 정책 때문에 음소거로 시작하고 `소리 켜기` 버튼 제공), 방송 중/종료 배지와 종료 안내. 녹화방송은 1단계 플레이어 그대로
- 한계: 시청자마다 방송자 휴대폰에서 별도 연결을 만드는 방식이라 시청자는 소수(대략 3~5명)만 지원합니다. 직접 연결이 안 되는 네트워크(통신사망 대칭형 NAT 등)에서는 TURN 서버가 필요합니다(아래 참고).
- 자동화 테스트로 확인되는 범위(`./gradlew test`, `npm test`): 라이브 생성·종료(3단계 현재: 소유자 검사, 멱등 종료), 상태·유형 필터와 라이브 우선 정렬, V2 마이그레이션(기존 V1 데이터 유지와 CHECK 제약), 실제 WebSocket 클라이언트를 쓴 시그널링 통합 테스트(방송자/시청자 join과 `viewer-joined`, offer/answer/ice가 올바른 상대에게만 전달, 종료·시작 전 방송 입장 거부, 소유자가 아닌 방송자 거부, 허용되지 않은 Origin 거부, 방송자 끊김 후 유예 시간이 지나면 `ENDED` + `broadcast-ended`, 유예 시간 내 재접속); 프론트엔드의 WebSocket URL 계산, 시그널링 메시지 직렬화·파싱, 연결 목록 관리, 카메라 지원 판정·오류 문구. **브라우저/휴대폰에서의 실제 카메라 송출과 영상 재생은 자동화 테스트 범위 밖이며, 아래 절차로 직접 확인해야 합니다(이 저장소 작성 시점에 휴대폰·브라우저로 확인했다고 주장하지 않습니다).**

### 직접 확인 절차

준비: PC와 휴대폰이 **같은 Wi-Fi**에 연결되어 있어야 합니다(게스트 Wi-Fi나 "AP 격리"가 켜진 공유기는 기기 간 통신이 막혀 있을 수 있습니다).

1. **DB·백엔드 실행**: `docker compose up -d` 후 `backend`에서 `./gradlew bootRun` (http://localhost:8080). 환경변수는 기본값 그대로 동작합니다.
2. **PC의 LAN IP 확인**: PowerShell/cmd에서 `ipconfig`를 실행하고 사용 중인 Wi-Fi 어댑터의 `IPv4 주소`(예: `192.168.0.12`)를 적어 둡니다. (macOS/Linux는 `ifconfig` 또는 `ip addr`.) 아래 `<PC IP>`가 이 값입니다.
3. **Windows 방화벽에서 5173 포트 허용**: 처음 실행할 때 "Windows 보안 경고"가 뜨면 **개인 네트워크 허용**을 선택합니다. 뜨지 않았다면 관리자 PowerShell에서 `New-NetFirewallRule -DisplayName "Vite 5173" -Direction Inbound -Protocol TCP -LocalPort 5173 -Action Allow -Profile Private` 를 실행하거나 "고급 보안이 포함된 Windows Defender 방화벽 → 인바운드 규칙 → 새 규칙 → 포트 TCP 5173"으로 허용합니다. (백엔드 8080은 휴대폰이 직접 접속하지 않으므로 열지 않아도 됩니다. 확인이 끝나면 규칙을 삭제하세요.)
4. **HTTPS 개발 서버 실행**: `frontend`에서 `npm install` 후 `npm run dev:https`. 터미널의 `Network: https://<PC IP>:5173/` 줄에서 주소를 확인합니다. (자체 서명 인증서를 쓰고, `/api`와 `/ws`는 같은 서버가 백엔드로 프록시하므로 백엔드에는 HTTPS가 필요 없습니다. 휴대폰 브라우저는 HTTPS에서만 카메라를 허용합니다.)
5. **휴대폰에서 접속**: 휴대폰 브라우저에서 `https://<PC IP>:5173/live/new`를 엽니다. 자체 서명 인증서라 "연결이 비공개로 설정되어 있지 않음" 경고가 뜨는데, 이 개발용 서버가 맞다면 **고급 → `<PC IP>`(안전하지 않음)으로 이동**(Android Chrome) 또는 **세부사항 보기 → 이 웹 사이트 방문**(iOS Safari)으로 진행합니다.
6. **방송 만들기**: (3단계 현재: 먼저 `https://<PC IP>:5173/signup`으로 가입해 로그인합니다. 비로그인이면 `/live/new`가 로그인 화면으로 보냅니다.) 제목을 입력하고 "스튜디오로 이동"을 누릅니다. 주소가 `/live/<id>/studio`로 바뀌어야 합니다.
7. **카메라·마이크 권한 허용**: 스튜디오가 열리면 브라우저가 권한을 묻습니다. **허용**을 누르면 내 얼굴 미리보기가 나옵니다.
   - Android Chrome: 팝업에서 "사이트 방문 중에 허용". 실수로 거부했다면 주소창 왼쪽 아이콘 → 권한(또는 사이트 설정)에서 카메라·마이크를 "허용"으로 바꾸고 새로고침합니다.
   - iOS Safari: 팝업에서 "허용". 거부했다면 `설정 → Safari → 카메라/마이크`를 "묻기" 또는 "허용"으로 바꾸거나, 주소창의 `aA` 메뉴 → 웹 사이트 설정에서 변경한 뒤 새로고침합니다. iOS에서는 Safari(또는 Safari 기반 브라우저)를 사용하세요.
   - 권한이 거부되었거나 HTTPS가 아니면 화면에 한국어 안내 문구가 표시됩니다.
8. **카메라 전환(선택)**: "카메라 전환" 버튼으로 전면/후면 카메라가 바뀌는지 확인합니다.
9. **방송 시작**: "방송 시작"을 누릅니다. 상태 배지가 `연결 중` → `방송 중`으로 바뀌고 시청자 수 `0명`이 보여야 합니다.
10. **다른 화면에서 시청**: PC 브라우저에서 `https://localhost:5173`(인증서 경고 수락) 또는 `npm run dev`로 띄운 `http://localhost:5173`을 엽니다. 목록의 **방송 중** 구역에 `방송 중` 배지가 붙은 방송이 보이는지 확인하고 카드를 눌러 시청합니다(스튜디오의 "시청 화면 열기"로도 열 수 있습니다). 영상이 나오면 "소리 켜기"를 눌러 소리가 나는지 확인합니다. 휴대폰 스튜디오의 시청자 수가 `1명`으로 오르는지도 확인합니다. (주의: 같은 PC에서 시청하면 에코가 날 수 있으니 소리는 이어폰으로 확인하세요.)
11. **종료 확인**: 휴대폰에서 "방송 종료"를 누릅니다. 휴대폰 화면에 `종료` 배지와 "방송이 종료되었습니다"가 나오고, PC 시청 화면의 배지가 `종료`로 바뀌며 종료 안내가 보이는지 확인합니다. 목록을 새로고침하면 해당 방송이 "지난 방송" 구역에 `종료` 배지로 있어야 합니다. 종료된 방송 주소를 다시 열면 영상 없이 종료 안내만 보여야 합니다.
12. **비정상 종료 확인(선택)**: 방송 중에 휴대폰의 탭을 닫거나 Wi-Fi를 끄면 `APP_LIVE_GRACE_SECONDS`(기본 15초) 뒤에 방송이 `종료`로 바뀌고 시청 화면에도 종료 안내가 뜹니다. 그 안에 같은 탭에서 다시 연결되면 방송이 이어집니다.
13. **API로 확인(선택)**: 방송 중일 때 `curl "http://localhost:8080/api/broadcasts?type=LIVE&status=LIVE"`로 방송이 보이는지, 종료 후에는 비어 있는지 확인합니다. 다른 사람이 종료하지 못하는지는 "3단계: 직접 확인 절차"의 `curl`(다른 사용자 토큰으로 `/end` 호출 시 403)로 확인합니다.

### 대안: 터널로 HTTPS 주소 만들기

방화벽·자체 서명 인증서 경고가 번거롭거나 PC와 휴대폰이 같은 Wi-Fi에 있지 않을 때는 Cloudflare Quick Tunnel로 신뢰되는 HTTPS 주소를 받을 수 있습니다(`cloudflared` 설치 필요).

1. 백엔드를 실행합니다(`./gradlew bootRun`). 터널이 HTTPS를 맡으므로 `dev:https`는 쓰지 않습니다.
2. 터미널에서 `cloudflared tunnel --url http://localhost:5173`을 실행하면 `https://<임의 단어>.trycloudflare.com` 주소가 출력됩니다. (호스트 이름은 터널을 띄워야 알 수 있습니다.)
3. `frontend`에서 그 호스트를 `VITE_ALLOWED_HOSTS`에 넣어 개발 서버를 실행합니다. Vite는 알 수 없는 Host 헤더를 거부하므로 이 값이 `vite.config.ts`의 `server.allowedHosts`로 전달됩니다: `VITE_ALLOWED_HOSTS=<임의 단어>.trycloudflare.com npm run dev` (PowerShell: `$env:VITE_ALLOWED_HOSTS="<임의 단어>.trycloudflare.com"; npm run dev`).
4. 휴대폰에서 `https://<임의 단어>.trycloudflare.com/live/new`를 열고 위 6번부터 같은 절차로 진행합니다. 이 주소는 공개 주소이므로 확인이 끝나면 터널(`cloudflared`)을 종료하세요. 3단계부터는 로그인한 소유자만 송출·종료할 수 있지만, 이 주소는 누구나 열 수 있고 가입도 가능하므로 확인이 끝나면 터널을 종료하세요.

### 연결이 안 될 때: TURN 서버

WebRTC는 기본적으로 휴대폰과 시청자 브라우저가 서로 직접 연결(P2P)합니다. 같은 Wi-Fi에서는 대부분 되지만, 휴대폰이 LTE/5G(통신사 CGNAT, 대칭형 NAT)이거나 방화벽이 UDP를 막는 환경에서는 영상 없이 "연결 중"에 머물 수 있습니다. 이 경우 TURN 릴레이 서버(예: coturn)가 필요하고, 백엔드를 시작하기 전에 다음 환경변수를 설정합니다(값 예시는 [`.env.example`](.env.example) 참고).

```bash
export APP_WEBRTC_TURN_URLS="turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349"
export APP_WEBRTC_TURN_USERNAME="<계정>"
export APP_WEBRTC_TURN_CREDENTIAL="<비밀번호>"
./gradlew bootRun
```

브라우저는 `GET /api/live/ice-servers` 응답으로 STUN과 TURN을 함께 받아 사용합니다. 이번 단계에서는 TURN을 직접 운영하지 않으며, TURN 경유 동작은 확인하지 않았습니다.

## 3단계: 인증·인가와 댓글

### 구현 범위

- **회원가입·로그인·로그아웃**: `POST /api/auth/signup`(201, 아이디 중복 409), `POST /api/auth/login` → `{token, user}`(틀린 아이디/비밀번호 401), `POST /api/auth/logout`(204), `GET /api/auth/me`. 아이디는 영문 소문자·숫자·밑줄 3~30자(대소문자 구분 없이 같은 계정), 비밀번호 8자 이상(BCrypt 한계인 72바이트 이하), 닉네임 20자 이하. 비밀번호는 **BCrypt 해시만** 저장합니다.
- **인증 상태 관리**: 로그인하면 서버가 서명된 JWT(HS256, 만료 `APP_JWT_TTL`)를 주고, 클라이언트가 이후 모든 요청에 `Authorization: Bearer <token>`을 붙입니다. 서버는 세션을 두지 않습니다(stateless). 프론트는 `localStorage`에 토큰을 저장하고(`frontend/src/auth/tokenStore.ts`), React 컨텍스트(`AuthContext`)가 로그인 사용자를 관리합니다.
- **방송 소유자**: 녹화방송·라이브 방송을 만들면 `broadcast.owner_id`가 로그인한 사용자로 저장됩니다(Flyway `V3`). 응답에는 `ownerId`, `ownerNickname`이 있습니다.
- **서버에서 검사하는 권한** (버튼 숨김은 편의일 뿐이고, 모든 규칙은 백엔드가 강제합니다):

  | 동작 | 비로그인 | 소유자/작성자 | 다른 로그인 사용자 |
  | --- | --- | --- | --- |
  | 방송 목록·상세·영상 재생(`GET /api/broadcasts…`), ICE 서버, 라이브 **시청**(시그널링 viewer `join`), 댓글 **목록** | 허용 | 허용 | 허용 |
  | 녹화방송 등록, 라이브 방송 생성 | 401 | 허용(본인이 소유자) | 허용(본인이 소유자) |
  | 방송 제목 수정 `PATCH /api/broadcasts/{id}`, 삭제 `DELETE /api/broadcasts/{id}`, 라이브 종료 `POST /api/broadcasts/{id}/end`, **송출**(시그널링 broadcaster `join`) | 401 | 허용 | **403** |
  | 댓글 작성 `POST /api/broadcasts/{id}/comments` | 401 | 허용 | 허용 |
  | 댓글 수정·삭제 `PATCH`/`DELETE /api/comments/{id}` | 401 | 작성자만 허용 | **403** (방송 소유자도 남의 댓글은 403) |

  없는 방송·댓글은 404, 검증 실패(빈 제목, 빈 댓글, 501자 이상 댓글 등)는 400입니다. 401·403 응답도 다른 오류와 같은 `{status, error, message}` JSON입니다.
- **방송 삭제**: 소유자가 삭제하면 DB 행, 업로드된 영상 파일, 그 방송의 댓글(`ON DELETE CASCADE`)이 함께 지워집니다. 아직 `READY/LIVE`인 라이브는 먼저 종료 처리해 시청자에게 `broadcast-ended`가 가고 나서 삭제됩니다.
- **소유자가 없는 옛 방송**: 1~2단계에서 만든(`owner_id`가 NULL인) 방송은 아무도 수정·삭제·종료할 수 없습니다(403). 열람은 가능합니다.
- **댓글**: 모든 방송(녹화·라이브)에 댓글을 달 수 있고, 목록은 최신순 페이지(`?page=0&size=30`, 최대 100)이며 작성자 `authorId`·`authorNickname`을 포함합니다. 작성자는 항상 토큰의 사용자이고 요청 본문으로 바꿀 수 없습니다. 내용은 공백만이면 안 되고 500자 이하입니다(DB `CHECK`로도 보장).
- **프론트엔드**: `/signup`, `/login`(로그인 후 원래 가려던 화면으로 복귀), 헤더에 닉네임과 `로그아웃`(로그아웃 시 서버에도 알림). `/live/new`, `/recordings/new`, `/live/:id/studio`는 비로그인이면 `/login`으로 보냅니다. 상세 화면에서 소유자에게만 `제목 수정`·`방송 삭제`(라이브는 `스튜디오 열기`)가, 본인 댓글에만 `수정`·`삭제`가 보입니다. 상세 화면 아래에 댓글 영역이 있고(읽기는 로그인 불필요), 라이브 방송 중에는 4초마다 새 댓글을 다시 불러옵니다. 어떤 API가 401을 반환하면 저장된 토큰을 지우고 로그아웃 상태로 돌아갑니다. 스튜디오는 방송자 `join` 메시지에 로그인 토큰을 실어 보내고 `end`도 같은 토큰으로 호출합니다.
- **시청·목록 조회는 공개**: 로그인 없이 목록, 상세, 녹화 재생, 라이브 시청, 댓글 읽기가 가능합니다. 이유는 아래 "3단계에서 추가한 결정" 참고.
- **환경변수**: `APP_JWT_SECRET`(32바이트 이상, 비우면 실행마다 무작위 생성 + WARN), `APP_JWT_TTL`(기본 `PT12H`). 값 예시는 [`.env.example`](.env.example)에 있고 실제 비밀 값은 없습니다.
- **한계**: 로그아웃은 클라이언트가 토큰을 버리는 것이 본체이고, 서버 쪽은 로그아웃된 토큰의 id(`jti`)를 만료 시각까지 **메모리 거부 목록**에 올립니다. 이 목록은 서버를 재시작하면 사라지고 인스턴스 간에 공유되지 않으므로, 로그아웃 이전에 유출된 토큰은 재시작 후 만료 시각까지 다시 유효할 수 있습니다(`APP_JWT_SECRET`을 고정해 두었다면). 토큰 갱신(refresh), 비밀번호 변경, 계정 삭제, 요청 횟수 제한은 구현하지 않았습니다. 라이브 송출의 소유자 확인은 소켓 `join` 시점에 한 번만 하므로, 이미 송출 중인 소켓은 토큰이 만료·로그아웃되어도 끊기지 않습니다(끊겼다 재접속할 때는 다시 검사).
- 자동화 테스트로 확인되는 범위(`./gradlew test`, `npm test`): 가입·로그인·`me`·로그아웃(로그아웃된 토큰 거절), 아이디 중복(대소문자 포함)·검증 실패·틀린 비밀번호, 변조·형식이 잘못된 토큰 401과 공개 조회는 낡은 토큰을 무시, BCrypt 해시 저장, 비로그인 등록·생성 401, 다른 사용자의 수정·삭제·종료 403(데이터 변화 없음)과 소유자 성공, 방송 삭제 시 영상 파일·댓글 삭제, 소유자 없는 옛 방송, 시그널링 방송자 `join`(토큰 없음·잘못된 토큰 `UNAUTHORIZED`, 소유자 아님 `FORBIDDEN`, 소유자 성공), 댓글(비로그인 조회 가능·작성 401, 작성자 수정·삭제, 다른 사용자·방송 소유자 403, 빈/501자 검증, 페이지네이션), V3 마이그레이션(기존 행 유지, 제약 조건, 방송 삭제 시 댓글 cascade), JWT 비밀키·TTL 설정 검증; 프론트엔드 vitest — 토큰 저장소, `Authorization` 헤더 주입, 401 시 토큰 삭제(새 토큰은 보존), 서버 오류 메시지 변환, 가입 폼 검증. **브라우저 화면(로그인·댓글·버튼 표시)은 자동화 테스트 범위 밖이며, 이 저장소 작성 시점에 브라우저로 확인했다고 주장하지 않습니다.**

### 직접 확인 절차

세 주체(비로그인 / 방송 소유자 / 다른 사용자)로 나눠 확인합니다. 아래 명령은 Git Bash, macOS/Linux 셸 기준입니다(Windows PowerShell에서는 `curl.exe`와 따옴표 처리가 달라 Git Bash나 WSL을 권장합니다). 셸 인코딩 문제를 피하려고 JSON 값은 영문으로 적었습니다.

**준비**: `docker compose up -d` → `backend`에서 `./gradlew bootRun`. `APP_JWT_SECRET`을 설정하지 않아도 시작되며 로그에 `APP_JWT_SECRET is not set` 경고가 한 번 나옵니다(그 실행 동안만 유효한 무작위 키).

```bash
API=http://localhost:8080
JSON='Content-Type: application/json'
code() { curl -s -o /dev/null -w "%{http_code}\n" "$@"; }   # 상태 코드만 출력
token() { curl -s -X POST $API/api/auth/login -H "$JSON" -d "{\"username\":\"$1\",\"password\":\"password-1\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/'; }

# 1) 회원가입 2명 -> 201 201, 같은 아이디 재가입 -> 409
curl -s -X POST $API/api/auth/signup -H "$JSON" -d '{"username":"owner1","password":"password-1","nickname":"owner"}'
curl -s -X POST $API/api/auth/signup -H "$JSON" -d '{"username":"other1","password":"password-1","nickname":"other"}'
code -X POST $API/api/auth/signup -H "$JSON" -d '{"username":"owner1","password":"password-1","nickname":"dup"}'   # 409

# 2) 로그인 -> 토큰. 틀린 비밀번호는 401
OWNER=$(token owner1); OTHER=$(token other1)
code -X POST $API/api/auth/login -H "$JSON" -d '{"username":"owner1","password":"wrong-password"}'               # 401
curl -s $API/api/auth/me -H "Authorization: Bearer $OWNER"                                                       # 내 정보(200)
code $API/api/auth/me                                                                                            # 토큰 없음: 401
```

**A. 방송 제어 (`ID`는 생성 응답의 `id`)**

```bash
# 비로그인: 등록 거절
code -X POST $API/api/broadcasts/recorded -F title=Anon -F videoUrl=https://example.com/a.mp4                    # 401
# 소유자: 등록 성공 (소유자 = owner1)
ID=$(curl -s -X POST $API/api/broadcasts/recorded -H "Authorization: Bearer $OWNER" -F title=MyBroadcast \
     -F videoUrl=https://example.com/a.mp4 | sed -E 's/.*"id":([0-9]+).*/\1/')                                   # 201
# 비로그인: 목록·상세는 공개
code $API/api/broadcasts; code $API/api/broadcasts/$ID                                                           # 200 200

# 수정: 비로그인 401 / 다른 사용자 403 / 소유자 200
code -X PATCH $API/api/broadcasts/$ID -H "$JSON" -d '{"title":"hacked"}'                                           # 401
code -X PATCH $API/api/broadcasts/$ID -H "Authorization: Bearer $OTHER" -H "$JSON" -d '{"title":"hacked"}'         # 403
code -X PATCH $API/api/broadcasts/$ID -H "Authorization: Bearer $OWNER" -H "$JSON" -d '{"title":"NewTitle"}'      # 200

# 삭제: 비로그인 401 / 다른 사용자 403 (소유자 삭제는 맨 마지막에 확인)
code -X DELETE $API/api/broadcasts/$ID                                                                           # 401
code -X DELETE $API/api/broadcasts/$ID -H "Authorization: Bearer $OTHER"                                         # 403

# 라이브: 비로그인 생성 401 / 소유자 생성 201 / 종료는 다른 사용자 403, 소유자 200
code -X POST $API/api/broadcasts/live -H "$JSON" -d '{"title":"Live"}'                                         # 401
LIVE=$(curl -s -X POST $API/api/broadcasts/live -H "Authorization: Bearer $OWNER" -H "$JSON" -d '{"title":"Live"}' \
       | sed -E 's/.*"id":([0-9]+).*/\1/')                                                                       # 201
code -X POST $API/api/broadcasts/$LIVE/end -H "Authorization: Bearer $OTHER"                                     # 403
code -X POST $API/api/broadcasts/$LIVE/end -H "Authorization: Bearer $OWNER"                                     # 200
```

송출용 WebSocket(`/ws/live/{id}`)의 방송자 `join`도 같은 규칙입니다: `{"type":"join","role":"broadcaster","token":"<JWT>"}`에 토큰이 없거나 틀리면 `error`(`UNAUTHORIZED`), 소유자가 아닌 사용자의 토큰이면 `error`(`FORBIDDEN`)를 받고 연결이 닫히며, 소유자의 토큰일 때만 `joined`가 옵니다(자동화 테스트가 확인합니다).

**B. 댓글**

```bash
code $API/api/broadcasts/$ID/comments                                                                            # 비로그인 목록 200
code -X POST $API/api/broadcasts/$ID/comments -H "$JSON" -d '{"content":"anonymous comment"}'                            # 비로그인 작성 401
CID=$(curl -s -X POST $API/api/broadcasts/$ID/comments -H "Authorization: Bearer $OTHER" -H "$JSON" \
      -d '{"content":"hello"}' | sed -E 's/.*"id":([0-9]+).*/\1/')                                           # 작성 201 (작성자 = other1)
code -X POST $API/api/broadcasts/$ID/comments -H "Authorization: Bearer $OTHER" -H "$JSON" -d '{"content":"   "}' # 빈 댓글 400

# 수정·삭제: 비로그인 401 / 작성자가 아닌 사용자(방송 소유자 포함) 403 / 작성자 200·204
code -X PATCH $API/api/comments/$CID -H "$JSON" -d '{"content":"x"}'                                             # 401
code -X PATCH $API/api/comments/$CID -H "Authorization: Bearer $OWNER" -H "$JSON" -d '{"content":"x"}'           # 403
code -X DELETE $API/api/comments/$CID -H "Authorization: Bearer $OWNER"                                          # 403
code -X PATCH $API/api/comments/$CID -H "Authorization: Bearer $OTHER" -H "$JSON" -d '{"content":"edited"}'      # 200
code -X DELETE $API/api/comments/$CID -H "Authorization: Bearer $OTHER"                                          # 204
```

**C. 로그아웃과 소유자 삭제**

```bash
code -X POST $API/api/auth/logout -H "Authorization: Bearer $OTHER"          # 204
code $API/api/auth/me -H "Authorization: Bearer $OTHER"                      # 401 (로그아웃된 토큰은 더 이상 통하지 않음)
code -X DELETE $API/api/broadcasts/$ID -H "Authorization: Bearer $OWNER"     # 204 (영상 파일·댓글도 함께 삭제)
code $API/api/broadcasts/$ID                                                 # 404
```

**D. 브라우저에서 확인(화면)**

1. `npm run dev` → http://localhost:5173 . 로그아웃 상태에서 목록·방송 상세·댓글 목록이 보이고, 헤더에 `로그인`/`회원가입`이 있는지 확인합니다. 상세 화면의 댓글 입력 칸 대신 로그인 안내가 보여야 합니다.
2. "녹화방송 등록"(또는 "라이브 방송 시작")을 누르면 `/login`으로 이동하는지 확인합니다. `/signup`에서 가입하면 자동 로그인되어 원래 가려던 화면으로 돌아가고 헤더에 닉네임과 `로그아웃`이 보여야 합니다.
3. 소유자 계정으로 방송을 등록하면 상세 화면에 `제목 수정`·`방송 삭제`가 보입니다. 다른 계정(시크릿 창)으로 같은 방송을 열면 이 버튼들이 없어야 합니다. (버튼을 숨겨도 권한은 서버가 검사하므로, 위 `curl`의 403이 진짜 확인입니다.)
4. 두 계정으로 댓글을 쓰고, 각자 **자기 댓글에만** `수정`/`삭제`가 보이는지 확인합니다. 라이브 방송 상세를 두 창에서 열고 한쪽에서 댓글을 쓰면 다른 쪽에 몇 초 안에 나타나는지 확인합니다.
5. 로그인 상태에서 개발자 도구 → Application → Local Storage의 `chzzk.token`을 지우거나 값을 바꾼 뒤 새로고침하면(또는 로그아웃 후 다른 탭에서 요청하면) 로그아웃 상태로 돌아가는지 확인합니다(401 처리).
6. 라이브 송출은 2단계 절차대로 하되 소유자 계정으로 로그인한 상태에서 스튜디오를 열어야 합니다. 다른 계정으로 `/live/<id>/studio`를 열면 "이 방송의 소유자만 송출할 수 있습니다" 안내가 나와야 합니다.

## 4단계: 도네이션 (토스페이먼츠 테스트 모드)

### 구현 범위

로그인한 사용자가 방송 상세 화면에서 **방송자(방송 소유자)** 에게 금액과 메시지를 정해 후원합니다. 결제는 토스페이먼츠 **테스트 모드**로 진행하므로 실제 돈은 움직이지 않습니다. 후원은 **서버가 토스에 승인을 요청하고 그 결과를 주문과 대조한 뒤에만** 확정되며, 같은 승인 요청·통지가 몇 번 반복돼도 한 번만 적립됩니다.

- **결제 흐름** (결제창형 결제, 토스 JS SDK v2 `widgets()` → `renderPaymentWindow()` → `requestPayment()`):
  1. 상세 화면의 후원 폼에서 금액(프리셋 1,000/5,000/10,000/50,000원 또는 직접 입력, **1,000 ~ 1,000,000원 정수**)과 메시지(선택, 100자 이하)를 정합니다. 받는 사람(방송자 닉네임)이 폼에 표시됩니다.
  2. `POST /api/donations`(로그인 필요): 서버가 `PENDING` 후원 행을 만들고 `orderId`(`donation_<UUID>`), 금액, `orderName`, `customerKey`, 성공/실패 URL(`APP_FRONTEND_BASE_URL` 기준)을 돌려줍니다. 받는 사람은 **요청이 아니라 서버가 그 시점의 방송 소유자로 정합니다**.
  3. 프론트엔드가 토스 SDK(`https://js.tosspayments.com/v2/standard`)로 결제창을 띄우고, 토스가 브라우저를 `/donations/success?paymentKey&orderId&amount` 또는 `/donations/fail?code&message&orderId`로 보냅니다.
  4. 성공 페이지가 `POST /api/donations/confirm {paymentKey, orderId, amount}`를 호출합니다. **서버**가 ① 주문이 호출자의 것인지(아니면 403), ② 상태가 `PENDING`인지, ③ 금액이 저장된 금액과 같은지(아니면 400, 토스 호출 없음), ④ 받는 사람이 아직 그 방송의 소유자인지 확인한 뒤 토스 `POST /v1/payments/confirm`(Basic 인증 `base64(시크릿키:)`)을 호출하고, 응답이 `status == DONE`, 같은 `orderId`·`paymentKey`, `totalAmount ==` 저장 금액일 때만 `CONFIRMED`로 바꿉니다. 화면의 성공 표시나 쿼리스트링만으로는 아무것도 확정되지 않습니다.
  5. 실패 페이지가 `POST /api/donations/{orderId}/fail`을 호출합니다. 결제창을 닫거나 취소한 경우(`PAY_PROCESS_CANCELED`, `USER_CANCEL`)는 `CANCELED`, 그 밖의 실패는 `FAILED`(코드·메시지 저장)입니다. 이미 확정된 후원은 이 호출로 바뀌지 않습니다.
  6. (선택) `POST /api/payments/toss/webhook`(공개, `TOSS_WEBHOOK_ENABLED=true`일 때만 동작, 꺼져 있으면 404): **본문을 믿지 않고** `data.paymentKey`만 읽어 토스에서 결제를 다시 조회(`GET /v1/payments/{paymentKey}`)한 뒤 같은 검증·멱등 로직을 적용합니다. 토스가 승인했는데 우리 서버가 기록하지 못한 결제(브라우저가 닫힘 등)를 복구하는 경로입니다.
- **API 요약**

  | 엔드포인트 | 권한 | 설명 |
  | --- | --- | --- |
  | `GET /api/payments/config` | 공개 | `{enabled:false}` 또는 `{enabled:true, clientKey, minAmount, maxAmount, maxMessageLength}` (시크릿 키는 절대 내려가지 않음) |
  | `POST /api/donations` | 로그인 | 후원 주문 생성(201). 금액·메시지 검증 400, 없는 방송 404, **자기 방송 400**, 키 없음 503 |
  | `POST /api/donations/confirm` | 로그인, 주문 당사자만 | 승인 확인·확정(멱등). 남의 주문 403, 금액 불일치 400, 다른 `paymentKey`로 재확정 409, 토스 거절 400(`FAILED`), 토스 응답 불가·불일치 502 |
  | `POST /api/donations/{orderId}/fail` | 로그인, 주문 당사자만 | 대기 중 주문을 `CANCELED`/`FAILED`로 닫음 |
  | `GET /api/broadcasts/{id}/donations` | 공개 | **확정된** 후원만 최신순(후원자 닉네임, 금액, 메시지, 승인 시각) + `totalAmount`(SQL `SUM`) + `count` |
  | `POST /api/payments/toss/webhook` | 공개(토스), 기본 꺼짐 | 위 6번 |

- **상태**: `PENDING`(생성됨) → `CONFIRMED`(토스 승인 + 서버 검증 완료, 합계에 포함되는 유일한 상태) / `FAILED`(거절·검증 실패) / `CANCELED`(결제창 닫음). `PENDING`만 다른 상태로 바뀌고 나머지는 최종 상태입니다.
- **저장 구조와 중복 방지 (Flyway `V4__create_donation.sql`)**: `donation` 테이블에 `UNIQUE(order_id)`, `UNIQUE(payment_key)`(승인 전에는 NULL이라 `PENDING` 행은 여러 개 가능), `CHECK(amount BETWEEN 1000 AND 1000000)`, `CHECK(status IN ('PENDING','CONFIRMED','FAILED','CANCELED'))`, `CHECK(status <> 'CONFIRMED' OR (payment_key IS NOT NULL AND approved_at IS NOT NULL))`, `CHECK(donor_id <> recipient_id)`(자기 후원 금지), 주문번호 형식 `CHECK`, `donor_id`·`recipient_id`·`broadcast_id` 외래키가 있습니다. 확정은 `SELECT … FOR UPDATE`로 행을 잠근 뒤 `UPDATE donation SET status='CONFIRMED', payment_key=…, approved_at=… WHERE order_id=? AND status='PENDING'`(조건부 UPDATE, 영향 행 1개일 때만 성공)로 합니다. 그래서 같은 승인에 대한 `confirm`·웹훅이 반복되거나 동시에 들어와도 **한 번만** 적립됩니다. 코드가 실수해도 DB 제약이 마지막으로 막고, 제약 위반(`DataIntegrityViolationException`)은 이미 같은 결제로 확정된 경우 기존 결과(200), 다른 후원이 쓴 결제면 409로 바꿉니다.
- **반복 호출 동작**: 이미 `CONFIRMED`인 주문에 같은 `paymentKey`로 `confirm`을 다시 보내면 **토스를 다시 호출하지 않고** 200과 기존 결과(`alreadyConfirmed:true`)를 돌려주며 합계는 그대로입니다. 다른 `paymentKey`면 409입니다. 토스에는 `Idempotency-Key` 헤더도 보냅니다(주문별 고정값).
- **토스 오류 처리**: 토스가 결제를 거절(4xx)하면 `FAILED`로 닫고 사유를 저장합니다. 네트워크 오류·타임아웃·5xx·키 오류처럼 **신뢰할 만한 답을 못 받은 경우**는 결제가 승인됐는지 알 수 없으므로 `PENDING`을 유지하고 502를 돌려줍니다(같은 요청을 다시 보내면 안전하게 이어집니다). 토스가 `ALREADY_PROCESSED_PAYMENT`라고 답하면 결제를 조회해 같은 검증을 거칩니다.
- **키 설정과 테스트 모드 전용**: 키는 환경변수로만 받습니다(`TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY`, [`.env.example`](.env.example)에 자리표시자). **키가 없어도 서버는 시작**되고, 이때 `/api/payments/config`는 `{"enabled":false}`, 후원 생성·확정은 503(`결제 설정이 없어 후원을 사용할 수 없습니다.`)이며 화면에는 같은 문구가 보입니다. 키가 `test_`로 시작하지 않으면(`TOSS_ALLOW_LIVE_KEYS=true`가 아닌 한) WARN을 남기고 후원을 끈 상태로 둡니다. 시크릿 키는 서버에서만 쓰고 응답·로그에 남기지 않습니다.
- **프론트엔드**: 방송 상세(녹화·라이브 모두)에 후원 영역(금액 프리셋·직접 입력·메시지·받는 사람 표시, 후원 건수·합계, 후원 내역; 라이브 방송은 4초마다 폴링)이 있고, `/donations/success`, `/donations/fail` 페이지(로그인 필요)가 서버 확정/실패 보고를 호출하고 결과를 보여 줍니다. 로그아웃 상태·자기 방송·키 없음일 때는 폼 대신 이유를 안내합니다.
- **자동화 테스트(가짜 토스 클라이언트 사용, 네트워크 호출 없음)**: 키 없음(config `enabled:false`, 생성·확정 503, 웹훅 404), `test_` 아닌 키 거절, 로그인 필요(401), 금액·메시지 검증, 자기 후원 400, 확정 성공 → `CONFIRMED`이고 목록·합계 반영, 금액 불일치 400(토스 호출 없음), 남의 주문 403, 토스 거절 → `FAILED`, 토스 응답 불가 → 502·`PENDING` 유지 후 재시도 성공, 토스 응답이 주문과 다르면 확정하지 않음, 방송자 변경·방송 삭제 시 미확정, 같은 `paymentKey` 반복 확정 → 200·토스 1회 호출·합계 1회, 다른 `paymentKey` 409, 같은 결제로 두 주문 확정 시도 → 제약 위반 409, **5개 스레드 동시 확정 → 1회만 적립**, 취소/실패 엔드포인트, 웹훅(복구·중복·위조 본문 무시), DB 제약 직접 검증(`order_id`/`payment_key` 중복 INSERT 거부 등). 프론트엔드는 금액 검증과 성공/실패 쿼리 파싱 단위 테스트가 있습니다.
- **한계**: 실제 토스 샌드박스와의 통신은 자동화 테스트가 아니라 아래 직접 확인 절차로 확인해야 합니다(작성자가 키 없이 코드를 작성했으므로 이 README는 토스 문서를 따라 쓴 것이며 결제 테스트를 수행했다고 주장하지 않습니다). 환불·결제 취소 API, 방치된 `PENDING` 주문 정리, 웹훅 서명(`tosspayments-webhook-signature`) 검증은 범위 밖입니다.

### 직접 확인 절차

**0. 토스 테스트 키 받기와 실행**

1. [토스페이먼츠 개발자센터](https://developers.tosspayments.com)에서 이메일로 회원가입하고 로그인합니다(문서 기준 테스트 키는 사업자 등록 없이 받을 수 있습니다. 화면 구성은 바뀔 수 있습니다).
2. 왼쪽 **API 키** 메뉴의 **주문서형, 결제창형 연동 키**에서 **테스트** 클라이언트 키(`test_gck_…`)와 시크릿 키(`test_gsk_…`)를 확인합니다. (`test_ck_…`/`test_sk_…`인 "API 개별 연동 키"는 이 구현이 쓰는 결제창형 SDK와 맞지 않습니다.)
3. 백엔드를 실행하는 셸에 환경변수를 설정하고 실행합니다. **키를 파일에 적어 커밋하지 마세요.**

   ```bash
   docker compose up -d
   # Git Bash / macOS / Linux
   export TOSS_CLIENT_KEY='test_gck_...'     # 개발자센터에서 복사한 테스트 키
   export TOSS_SECRET_KEY='test_gsk_...'
   cd backend && ./gradlew bootRun
   # Windows PowerShell:  $env:TOSS_CLIENT_KEY='test_gck_...'; $env:TOSS_SECRET_KEY='test_gsk_...'; .\gradlew.bat bootRun
   # 프론트엔드는 새 터미널에서: cd frontend && npm install && npm run dev   (http://localhost:5173)
   curl -s http://localhost:8080/api/payments/config      # {"enabled":true,"clientKey":"test_gck_...",...} (시크릿 키는 없어야 함)
   ```

   키를 설정하지 않고 실행하면 서버는 시작되고 위 `curl`이 `{"enabled":false}`를 돌려주며 후원 폼 자리에 `결제 설정이 없어 후원을 사용할 수 없습니다`가 보입니다.
4. 두 계정을 만듭니다: 계정 A(방송자)와 계정 B(후원자). 브라우저 일반 창/시크릿 창으로 나누면 편합니다. A로 로그인해 녹화방송을 하나 등록합니다(영상 URL 방식이면 간단).

**1. 결제 성공** — B로 로그인해 A의 방송 상세를 엽니다. 후원 영역에 "후원 받는 사람: A 닉네임 (방송자)"가 보이는지 확인하고, 금액(예: 5,000원)과 메시지를 정한 뒤 `후원하기`를 누릅니다. 토스 결제창에서 테스트 결제를 진행합니다(테스트 환경이라 카드 번호를 넣어도 가상으로 승인되며 돈이 빠져나가지 않습니다. 자세한 규칙은 [토스 테스트 환경 안내](https://docs.tosspayments.com/guides/v2/get-started/environment)). `/donations/success`에서 "후원이 확정되었습니다"가 보이고, 방송 상세로 돌아가면 후원 내역에 B의 닉네임·금액·메시지가 있고 합계가 5,000원 늘어야 합니다. DB에서도 확인합니다.

```bash
docker exec chzzk-postgres psql -U chzzk -d chzzk -c "SELECT order_id, status, amount, payment_key, approved_at FROM donation ORDER BY id DESC LIMIT 5;"
curl -s http://localhost:8080/api/broadcasts/<방송id>/donations     # 로그인 없이 확정된 후원 + totalAmount + count
```

**2. 결제 실패** — 두 가지로 확인합니다.
- 서버 검증 실패(금액 변조): 성공 후 받은 값이 아니라 새 주문으로 시험합니다. B로 로그인한 토큰(`Authorization: Bearer <토큰>`)으로 주문을 만들고(`POST /api/donations`, `{"broadcastId":<id>,"amount":5000}`), 응답의 `orderId`에 **다른 금액**으로 `confirm`을 보냅니다.

  ```bash
  curl -s -X POST http://localhost:8080/api/donations/confirm -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
    -d '{"paymentKey":"anything","orderId":"<orderId>","amount":100}'     # 400 "결제 금액이 주문 금액과 일치하지 않습니다." (토스 호출 없음, 주문은 PENDING 그대로)
  ```
- 토스가 거절하는 경우: 같은 주문에 존재하지 않는 `paymentKey`로 `amount`를 올바르게 넣어 `confirm`을 보내면 토스가 오류를 답하고(예: `NOT_FOUND_PAYMENT`) 서버는 주문을 `FAILED`(`fail_code`에 토스 코드 저장)로 닫고 400을 돌려줍니다. 합계는 늘지 않습니다. 결제창에서 카드사 거절 등으로 실패하면 `/donations/fail`이 열리고 같은 방식으로 `FAILED`가 됩니다(토스가 실패를 재현하는 방법은 위 테스트 환경 안내 참고).

**3. 결제 취소** — `후원하기`로 결제창을 연 뒤 결제를 끝내지 않고 창을 닫거나 결제 중 `취소`를 누릅니다. 닫으면 "결제가 취소되었습니다. 후원은 적립되지 않았습니다.", 결제 진행 중 취소하면 `/donations/fail`(`PAY_PROCESS_CANCELED`)에서 "결제가 취소되었습니다"가 보이고, DB의 해당 주문은 `CANCELED`, 후원 합계는 그대로여야 합니다. 취소된 주문에 `confirm`을 보내면 409입니다.

**4. 승인 통지(확정 요청) 중복** — 1번처럼 성공한 뒤, 같은 요청을 다시 보내 봅니다.
- 성공 페이지(`/donations/success?paymentKey=…&orderId=…&amount=…`)를 **새로고침**하면 "이미 확정된 후원입니다. 중복 적립은 되지 않았습니다."가 보이고 합계는 그대로입니다.
- 같은 요청을 `curl`로 반복합니다(값은 성공 페이지 주소창의 쿼리에서 복사).

  ```bash
  for i in 1 2 3; do curl -s -X POST http://localhost:8080/api/donations/confirm -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
    -d '{"paymentKey":"<paymentKey>","orderId":"<orderId>","amount":5000}'; echo; done     # 매번 200, status CONFIRMED, alreadyConfirmed:true
  curl -s -X POST http://localhost:8080/api/donations/confirm -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
    -d '{"paymentKey":"other-key","orderId":"<orderId>","amount":5000}'                  # 409 (이미 다른 결제로 확정)
  curl -s http://localhost:8080/api/broadcasts/<방송id>/donations                          # totalAmount, count 가 처음과 같음
  ```

  백엔드 로그에서 토스 승인 호출이 처음 한 번뿐인지(반복 때는 호출이 없는지) 볼 수 있습니다.
- **웹훅(선택)**: `TOSS_WEBHOOK_ENABLED=true`로 백엔드를 다시 띄우고, 터널(예: `cloudflared tunnel --url http://localhost:8080`)의 공개 HTTPS 주소 `https://<터널>/api/payments/toss/webhook`을 개발자센터의 웹훅 설정에 `PAYMENT_STATUS_CHANGED` 이벤트로 등록하면 토스가 결제 상태 변경을 통지합니다(재시도로 같은 통지가 여러 번 올 수 있음). 터널 없이도 직접 보낼 수 있습니다.

  ```bash
  curl -s -X POST http://localhost:8080/api/payments/toss/webhook -H 'Content-Type: application/json' \
    -d '{"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"<확정된 paymentKey>"}}'   # 200. 서버가 토스에서 재조회, 합계 그대로
  curl -s -X POST http://localhost:8080/api/payments/toss/webhook -H 'Content-Type: application/json' \
    -d '{"data":{"paymentKey":"forged","orderId":"<orderId>","status":"DONE","totalAmount":999999}}'   # 200이지만 아무것도 바뀌지 않음(본문을 믿지 않고 토스에 물어봄)
  ```

**5. DB 제약 직접 확인** — 같은 `order_id` 또는 `payment_key`로 두 번째 행을 넣으면 DB가 거부해야 합니다.

```bash
docker exec chzzk-postgres psql -U chzzk -d chzzk -c "INSERT INTO donation (order_id, payment_key, amount, status, donor_id, recipient_id, approved_at)
  SELECT 'donation_manual-dup', payment_key, amount, 'CONFIRMED', donor_id, recipient_id, now() FROM donation WHERE status='CONFIRMED' LIMIT 1;"
# ERROR: duplicate key value violates unique constraint "uq_donation_payment_key"
```

**6. 그 밖의 확인**: 로그아웃 상태 또는 자기 방송에서는 후원 폼 대신 안내가 나오는지, `curl`로 자기 방송에 주문을 만들면 400인지(`자신의 방송에는 후원할 수 없습니다.`), 남의 주문에 `confirm`/`fail`을 보내면 403인지, 라이브 방송 상세를 두 창에서 열고 후원하면 다른 창의 후원 내역이 몇 초 안에 갱신되는지 확인합니다. 자동 테스트는 `cd backend && ./gradlew test`, `cd frontend && npm test`입니다.

## 5단계: 로컬 오토스케일

### 구현 범위

서버를 컨테이너 이미지로 만들어 로컬 Kubernetes(kind)에 여러 개 띄우고, CPU 부하에 따라 백엔드 인스턴스 수가 **최소 2 ~ 최대 6** 사이에서 자동으로 늘고 줄게 했습니다. 인스턴스가 늘거나 줄어도 로그인 세션·업로드 영상·라이브 상태·댓글·도네이션이 유지되도록 상태를 모두 인스턴스 밖 저장소에 둡니다.

| 요구사항 | 구현 위치 |
| --- | --- |
| 이미지 빌드 | `backend/Dockerfile`(JDK 25 멀티스테이지 → `eclipse-temurin:25-jre`, 비루트, `PORT` 사용, `.dockerignore`), `frontend/Dockerfile`(Node 빌드 → nginx, `nginx/default.conf.template`) |
| 여러 인스턴스 배포 | `deploy/k8s/backend.yaml`(Deployment replicas 2, CPU 요청 250m/제한 1, startup·readiness·liveness 프로브, ConfigMap + Secret 환경변수), `postgres.yaml`(StatefulSet + PVC), `redis.yaml`, `minio.yaml`(+ PVC), `frontend.yaml`, `deploy/kind-config.yaml` |
| 지표 기준 자동 조절 | `deploy/k8s/hpa.yaml`: `autoscaling/v2`, CPU 평균 50%, `minReplicas: 2`, `maxReplicas: 6`, `behavior`(빠른 스케일 아웃, 스케일 인 안정화 60초). 지표는 metrics-server(`up.sh`가 설치, kind용 `--kubelet-insecure-tls`) |
| 요청 분산 | 브라우저 → nginx(`frontend.yaml`, NodePort 30080 → 호스트 8090) → `backend` ClusterIP Service(`backend.yaml`) → 준비된 파드들. nginx는 `/api`와 `/ws`(WebSocket 업그레이드)를 프록시 |
| 상태를 공용 저장소에 | 로그아웃 목록 → Redis(`RedisTokenDenylist`), 라이브 접속 정보·시그널링 중계 → Redis(`RedisLiveCoordinator`, `LiveReaper`), 영상 → S3 호환 저장소(`S3VideoStorage`, MinIO), JWT 키 → 모든 파드 공통 Secret(클러스터 모드에서 없으면 시작 실패), 사용자·방송·댓글·도네이션 → PostgreSQL |
| 부하 스크립트 | `load/k6/scale-test.js`(k6, ramping VUs), 클러스터 안에서 돌리는 `deploy/k8s/load/k6-job.yaml` |
| 운영 설정 | `server.shutdown=graceful`, `/actuator/health/liveness`·`readiness`, `X-Forwarded-*` 신뢰, `-XX:MaxRAMPercentage=75` |
| 비밀값 | `deploy/scripts/up.sh`가 무작위로 만들어 클러스터에만 넣음. 저장소에는 자리표시자뿐인 `deploy/k8s/secret.example.yaml` |
| 테스트 | Testcontainers의 Redis 7로 두 "인스턴스" 간 시그널링 중계·로그아웃 공유·정리기 시험(`LiveClusterTest`, `ClusterModeApiTest`), MinIO로 `S3VideoStorage` 저장/조회/Range/삭제 시험(`S3VideoStorageTest`), 클러스터 모드에서 JWT 비밀키 없으면 시작 실패(`JwtConfigTest`), 기본 모드가 Redis 없이 동작(`LocalModeTest`) |

### 실행 방법

준비물: Docker Desktop(실행 중), `kind`, `kubectl`, Git Bash(또는 Linux/macOS 셸). `kind`가 PATH에 없으면(Windows winget 설치 위치 예):

```bash
export PATH="$PATH:/c/Users/<사용자>/AppData/Local/Microsoft/WinGet/Packages/Kubernetes.kind_Microsoft.Winget.Source_8wekyb3d8bbwe"
```

```bash
# 클러스터 만들기 + 이미지 빌드 + 배포 (처음에는 이미지·metrics-server를 받느라 몇 분 걸립니다)
deploy/scripts/up.sh          # 끝나면 http://localhost:8090 에서 앱이 열립니다

# 스케일 상황 지켜보기 (10초마다 HPA·Deployment·파드를 시각과 함께 출력, Ctrl+C로 종료)
deploy/scripts/watch.sh 10

# 부하 걸기 (k6를 Docker로 실행, 로컬 설치 불필요)
docker run --rm -i -e BASE_URL=http://host.docker.internal:8090 -e PEAK_VUS=80 -e HOLD=4m grafana/k6 run - < load/k6/scale-test.js

# 정리 (클러스터와 Secret, 볼륨을 모두 삭제. docker compose의 Postgres는 건드리지 않습니다)
deploy/scripts/down.sh
```

`up.sh`가 하는 일: kind 클러스터 생성(호스트 8090 → NodePort 30080) → 두 이미지 빌드 → `kind load docker-image` → metrics-server 설치 + `--kubelet-insecure-tls` 패치 → `APP_JWT_SECRET`·Postgres·MinIO 비밀번호를 무작위로 만들어 Secret 생성 → `kubectl apply -k deploy/k8s` → 모든 롤아웃 대기. 클러스터 안에서 부하를 걸고 싶다면 `deploy/k8s/load/k6-job.yaml` 맨 위의 주석대로 합니다. 토스 테스트 키로 후원까지 시험하려면 `up.sh` 실행 전에 `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY`를 셸에 export 하면 Secret에 함께 들어갑니다(없으면 후원만 꺼짐). 호스트 포트가 8080이 아니라 8090인 이유는 개발 중 `bootRun`이 8080을 쓰고 있었기 때문입니다(바꾸는 방법은 위 설계 결정 참고).

### 직접 확인 절차

아래는 `up.sh`가 끝난 뒤 순서대로 확인하는 방법입니다. 같은 절차를 실제로 실행한 결과는 다음 절에 있습니다. (Windows에서 한글을 `curl -d`로 보내면 인코딩이 깨질 수 있어 예시는 영문 문자열을 씁니다.)

**1. 스케일 전 상태와 데이터 만들기**

```bash
kubectl get hpa,deploy -n chzzk              # backend 2/2, HPA TARGETS cpu: n%/50%, REPLICAS 2
B=http://localhost:8090
curl -s -X POST $B/api/auth/signup -H 'Content-Type: application/json' -d '{"username":"demo_1","password":"demo-password-1","nickname":"demo"}'
TOKEN=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' -d '{"username":"demo_1","password":"demo-password-1"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -s -X POST $B/api/broadcasts/recorded -H "Authorization: Bearer $TOKEN" -F 'title=demo broadcast' -F 'videoUrl=https://example.com/demo.mp4'
curl -s -X POST $B/api/broadcasts/1/comments -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"content":"before scaling"}'
curl -s -X POST $B/api/broadcasts/recorded -H "Authorization: Bearer $TOKEN" -F 'title=video' -F 'file=@demo.mp4;type=video/mp4'   # 작은 mp4 하나
```

**2. 스케일 아웃**: 한 터미널에서 `deploy/scripts/watch.sh 10`, 다른 터미널에서 k6 부하를 겁니다. 1분 안에 HPA의 `TARGETS`가 `50%`를 훨씬 넘고(`cpu: 300%+/50%`) `REPLICAS`가 2 → 4 → 6으로 늘며 `kubectl get pods -n chzzk`에 새 `backend-…` 파드가 `0/1` → `1/1`로 올라옵니다(최대 6에서 멈춤).

**3. 스케일 중에도 상태가 그대로인지** (부하가 걸려 있는 동안):

```bash
# 같은 JWT로 여러 번 호출: X-Instance(응답한 파드 이름)가 바뀌어도 모두 200
for i in $(seq 12); do curl -s -D - -o /dev/null -H "Authorization: Bearer $TOKEN" $B/api/auth/me | grep -iE '^(HTTP/|x-instance)'; done
curl -s $B/api/broadcasts                    # 방송 목록이 그대로
curl -s $B/api/broadcasts/1/comments         # 부하 전에 쓴 댓글이 그대로
curl -s -D - -o /dev/null -H 'Range: bytes=100-199' $B/api/broadcasts/<영상 방송 id>/video   # 206 + Content-Range (MinIO에서 읽음)
kubectl -n chzzk exec deploy/minio -- ls /bitnami/minio/data/chzzk-videos/                    # 영상은 MinIO에 있고
kubectl -n chzzk exec deploy/backend -- sh -c 'ls /app/uploads | wc -l'                       # 백엔드 파드 디스크에는 없어야 함(0)
```

로그아웃도 파드 사이에서 공유됩니다: 파드 A에서 로그아웃한 토큰이 파드 B에서도 401이어야 합니다(`kubectl port-forward pod/<A> 18081:8080`, `pod/<B> 18082:8080`로 파드에 직접 요청). 라이브는 브라우저 두 개(또는 방송자 1 + 시청자 여러 명)로 `http://localhost:8090`에서 방송을 만들고 시청해 보면, 소켓이 서로 다른 파드에 붙어도 시청자 입장·offer/answer 중계·방송 종료가 모두 전달됩니다(위 테스트가 같은 시나리오를 자동 검증).

**4. 스케일 인**: 부하가 끝나면 CPU가 내려가고, 약 1~2분 뒤(스케일 인 안정화 창 60초) `REPLICAS`가 한 번에 하나씩(30초 간격) 줄어 2에서 멈춥니다. 몇 분 걸립니다.

**5. 스케일 인 뒤에도 상태 확인**: 3번 명령을 다시 실행합니다. 같은 토큰, 같은 방송 목록·댓글, 같은 영상이어야 합니다. `kubectl -n chzzk rollout restart deployment/backend`로 모든 파드를 새 파드로 바꾼 뒤에도 같은 토큰이 통과해야 합니다(상태가 파드 밖에 있다는 증거).

### 확인 결과 기록

실행 환경: Windows 11, Docker Desktop(8 CPU / 32GB), kind v0.33.0(노드 이미지 `kindest/node:v1.37.0`), metrics-server v0.9.1, `kubectl` v1.36.1. 아래 출력은 실제로 실행해서 얻은 것을 시각이 보이는 부분만 줄여 옮긴 것입니다(파드 이름은 실행마다 다름). 부하는 k6 80 VU(30초 램프업 + 4분 유지), 각 반복마다 방송 목록·상세·댓글 목록·`/api/auth/me`를 읽고 15%는 로그인(bcrypt, CPU를 많이 씀), 10%는 댓글 작성입니다.

**첫 번째 시도에서 찾은 문제와 수정** — 첫 부하 시험(10:08~10:13)에서 스케일 아웃·인 자체는 정상이었지만 k6가 **요청의 14.7%가 실패**했다고 보고했습니다. 프론트 nginx 로그에 `connect() to 10.96.18.204:8080 failed (99: Address not available)`와 502가 쏟아졌습니다. nginx가 백엔드로 요청마다 새 TCP 연결을 만들어 임시 포트가 고갈된 것이었습니다(백엔드 파드의 문제가 아님). `frontend/nginx/default.conf.template`에 keep-alive 풀(`upstream` + `keepalive 64`, 요청 200개/30초마다 연결 갱신)을 넣고 이미지를 다시 만든 뒤 같은 부하를 다시 걸었더니 실패가 0이 되었습니다. 첫 시도의 스케일 변화 기록도 남겨 둡니다(`kubectl get hpa,deploy`를 `watch.sh`로 기록):

```
10:08:01  cpu: 13%/50%    HPA REPLICAS 2   READY 2/2        # 부하 시작
10:08:42  cpu: 98%/50%    HPA REPLICAS 2   READY 2/4
10:08:53  cpu: 394%/50%   HPA REPLICAS 4   READY 2/6
10:09:13  cpu: 399%/50%   HPA REPLICAS 6   READY 6/6        # 최대 6
10:12:47  (부하 종료)
10:14:22  cpu: 1%/50%     HPA REPLICAS 6   READY 5/5        # 스케일 인 시작
10:15:54  cpu: 0%/50%     HPA REPLICAS 3   READY 2/2        # 10:15:50에 Deployment 2개로 복귀
```

**두 번째(최종) 실행 — 스케일 전** (`kubectl get hpa,pods -n chzzk -l app=backend`, 시각 10:16:15):

```
NAME      REFERENCE            TARGETS       MINPODS   MAXPODS   REPLICAS   AGE
backend   Deployment/backend   cpu: 1%/50%   2         6         2          11m
    backend-6689f94fdc-hfwnr 1/1 Running
    backend-6689f94fdc-tdtnc 1/1 Running
```

같은 JWT로 `GET /api/auth/me` 12번 (`X-Instance`는 응답한 파드, 모두 200):

```
     10 HTTP/1.1 200  X-Instance: backend-6689f94fdc-hfwnr
      2 HTTP/1.1 200  X-Instance: backend-6689f94fdc-tdtnc
```

**스케일 아웃** (k6 시작 10:16:16, `watch.sh` 기록에서 상태가 바뀐 시점만):

```
10:16:16  TARGETS cpu: 1%/50%     HPA REPLICAS 2   deploy READY 2/2
10:16:58  TARGETS cpu: 322%/50%   HPA REPLICAS 2   deploy READY 2/4     # 목표치의 6배 넘음 → 4개로 늘리는 중
10:17:08  TARGETS cpu: 389%/50%   HPA REPLICAS 4   deploy READY 4/6
10:17:28  TARGETS cpu: 400%/50%   HPA REPLICAS 6   deploy READY 6/6     # 최대(6)에 도달, 부하 시작 후 약 72초
```

```
--- 2026-11-23 10:17:28
horizontalpodautoscaler.autoscaling/backend   Deployment/backend   cpu: 400%/50%   2   6   6   12m
deployment.apps/backend    6/6     6            6           12m
    backend-6689f94fdc-5n75v 1/1 Running
    backend-6689f94fdc-dszrw 1/1 Running
    backend-6689f94fdc-hfwnr 1/1 Running
    backend-6689f94fdc-k4q5z 1/1 Running
    backend-6689f94fdc-tdtnc 1/1 Running
    backend-6689f94fdc-x6vzn 1/1 Running
```

**스케일 아웃 상태(파드 6개, 부하 중)에서 상태 확인** (10:17:25 ~ 10:17:36):

```
같은 JWT, GET /api/auth/me 12번 (5개 파드가 번갈아 응답, 전부 200):
      3 HTTP/1.1 200  X-Instance: backend-6689f94fdc-dszrw
      1 HTTP/1.1 200  X-Instance: backend-6689f94fdc-hfwnr
      3 HTTP/1.1 200  X-Instance: backend-6689f94fdc-k4q5z
      3 HTTP/1.1 200  X-Instance: backend-6689f94fdc-tdtnc
      2 HTTP/1.1 200  X-Instance: backend-6689f94fdc-x6vzn
GET /api/broadcasts
    3 LIVE ENDED live across pods
    2 RECORDED ENDED video stored in MinIO
    1 RECORDED ENDED autoscale demo broadcast
GET /api/broadcasts/1/comments (부하 전에 쓴 댓글)
    total comments: 1 | oldest: comment written before scaling
Range 요청 (MinIO에 저장된 영상)
HTTP/1.1 206
Content-Length: 100
X-Instance: backend-6689f94fdc-tdtnc
Content-Range: bytes 100-199/9679
```

영상은 백엔드 파드 디스크가 아니라 MinIO에 있습니다(스케일 전, 10:07):

```
minio bucket chzzk-videos: a547ca55-d676-4d1c-9b41-cad6e6858951.mp4
```

부하 중 라이브 시그널링 시험(`ws` 소켓 9개를 `localhost:8090`으로 연결: 방송자 1 + 시청자 8, 파드 6개가 모두 Redis 채널을 구독 중 — `redis-cli pubsub numsub chzzk:live` → `6`):

```
[10:17:28] created live broadcast id=4 status=READY
[10:17:33] broadcaster saw viewer-joined for 8/8 viewers (last viewerCount=8)
[10:17:34] offers delivered to the right viewer: 8/8, answers back at the broadcaster: 8/8
[10:17:36] POST /end -> HTTP 200; broadcast-ended received by 9/9 sockets
```

(소켓이 어느 파드에 붙었는지는 클라이언트에서 보이지 않으므로 "서로 다른 파드에 붙었다"는 직접 증거는 위 자동 테스트(`LiveClusterTest`)가 대신합니다. 이 스크립트는 클러스터에서 실제 경로로 끝까지 동작한다는 확인입니다.)

로그아웃 공유(파드 6개 상태에서 `/api/auth/logout` 후 같은 토큰으로 12번 호출 → 전부 401, Redis에 `auth:denylist:*` 키 1개, TTL 43197초 ≈ 12시간 토큰 수명). 부하가 끝난 뒤 파드 두 개에 `kubectl port-forward`로 **직접** 요청한 결과:

```
login on A (token issued by A)
  me on A: HTTP/1.1 200  X-Instance: backend-f64c6c779-cgdbl
  me on B: HTTP/1.1 200  X-Instance: backend-f64c6c779-sq5tg
logout on A: HTTP/1.1 204  X-Instance: backend-f64c6c779-cgdbl
  me on A: HTTP/1.1 401  X-Instance: backend-f64c6c779-cgdbl
  me on B: HTTP/1.1 401  X-Instance: backend-f64c6c779-sq5tg
```

**k6 결과** (최종 실행, 부하 종료 10:20:56):

```
checks_succeeded...: 100.00% 370569 out of 370569
http_req_failed................: 0.00%  0 out of 370570
http_req_duration..............: avg=44.08ms  min=1.07ms  med=22.94ms  max=2.2s  p(90)=97.99ms  p(95)=140.96ms
http_reqs......................: 370570 1322.817057/s
```

**스케일 인** (부하 종료 10:20:56 이후, 같은 `watch.sh` 기록):

```
10:20:58  cpu: 291%/50%   HPA REPLICAS 6   READY 6/6          # (watch가 아니라 별도 `kubectl get hpa` 출력) 부하 종료 직후에도 HPA는 직전 평균을 봄
10:22:27  TARGETS cpu: 1%/50%   HPA REPLICAS 6   deploy READY 5/5     # 안정화 창 60초 뒤 하나 제거
10:22:58  TARGETS cpu: 1%/50%   HPA REPLICAS 5   deploy READY 4/4
10:23:28  TARGETS cpu: 0%/50%   HPA REPLICAS 4   deploy READY 3/3
10:23:50  deployment.apps/backend   2/2     2            2           19m   # 최소 2로 복귀 (부하 종료 후 약 2분 54초)
```

```
$ kubectl -n chzzk get hpa,deploy backend      # 10:23:50
NAME                                          REFERENCE            TARGETS       MINPODS   MAXPODS   REPLICAS   AGE
horizontalpodautoscaler.autoscaling/backend   Deployment/backend   cpu: 0%/50%   2         6         2          19m
NAME                      READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/backend   2/2     2            2           19m
```

**스케일 인 뒤 상태 확인** (10:23:56): 같은 토큰으로 12번 전부 200(`X-Instance`가 남은 두 파드), 방송 목록과 댓글(`total comments: 2 | oldest: comment written before scaling`)이 그대로, 영상 Range 요청은 206.

**모든 파드를 새 파드로 교체한 뒤** (`kubectl rollout restart deployment/backend`, 10:25:29): 새 파드 `backend-f64c6c779-*` 두 개에서도 부하 전에 발급한 같은 JWT가 200, 방송 목록·댓글·영상 Range(206)가 그대로였습니다. 상태가 파드가 아니라 Postgres·Redis·MinIO에 있다는 확인입니다.

**확인하지 못한 것**: (1) 스케일 인 중 소켓이 붙어 있던 파드가 종료될 때의 라이브 연결 유지(위 "알려진 한계")는 클러스터에서 시험하지 않았습니다. (2) 이 시험의 방송은 모두 URL/업로드 녹화 방송과 짧은 라이브 시험 하나이며, 실제 카메라 송출·TURN 환경은 이번 단계에서 다시 시험하지 않았습니다(2단계 확인 절차 참고). (3) 클라우드(Railway, Cloudflare)에는 배포하지 않았습니다. 배포 준비 절은 설정 대응표일 뿐 실행해 본 것이 아닙니다. 정리: `deploy/scripts/down.sh`로 클러스터를 삭제했습니다.
