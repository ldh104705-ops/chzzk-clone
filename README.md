# 치지직 클론

## 1. 프로젝트 소개

치지직(CHZZK) 스타일의 라이브 스트리밍 사이트를 4단계에 걸쳐 만드는 과제 프로젝트입니다. 1단계는 로그인 없이 녹화된 영상을 등록하고, 목록·상세 화면에서 재생하는 기능입니다. 이후 단계에서 휴대폰 라이브 송출(WebRTC), 인증·인가와 댓글, 도네이션(토스페이먼츠 테스트 모드)을 같은 코드베이스에 이어서 추가합니다.

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

브라우저에서 http://localhost:5173 을 엽니다. 개발 서버가 `/api` 요청을 `http://localhost:8080`으로 프록시합니다.

### 테스트

```bash
cd backend && ./gradlew test                         # Docker 필요 (Testcontainers가 PostgreSQL을 띄움)
cd frontend && npm test                              # vitest 단위 테스트
cd frontend && npm run build                         # 타입 검사(tsc) + 프로덕션 빌드
```

## 3. 환경변수

- 백엔드: 루트의 [`.env.example`](.env.example)에 **모든** 백엔드 환경변수와 설명이 있습니다.
- 프론트엔드: [`frontend/.env.example`](frontend/.env.example) (`VITE_API_BASE_URL`).

로컬에서는 모두 기본값이 있어 별도 설정 없이 동작합니다. Spring Boot는 `application.properties`의 `${NAME:기본값}` 형태로 **프로세스 환경변수**를 읽습니다. 따라서 값을 바꾸려면 셸에서 `export SPRING_DATASOURCE_URL=...` 후 `./gradlew bootRun`을 실행하거나, IDE 실행 구성(Run Configuration)의 Environment variables에 입력하세요. (`.env` 파일은 Spring이 자동으로 읽지 않으며, `docker compose`만 읽습니다.) 프론트엔드의 `VITE_*` 값은 빌드 시점에 번들에 포함되므로 `.env`에 두거나 호스팅 서비스의 빌드 환경변수로 설정합니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `PORT` | `8080` | 서버 포트 |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/chzzk` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | `chzzk` / `chzzk` | DB 계정 (로컬 개발용 값이며 배포 시 반드시 교체) |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 쉼표로 구분한 허용 Origin (프론트엔드 주소) |
| `APP_STORAGE_DIR` | `./uploads` | 업로드 영상 저장 경로 |
| `APP_MAX_UPLOAD_SIZE` | `500MB` | 업로드 최대 크기 |
| `VITE_API_BASE_URL` (프론트) | 빈 값 | 비우면 같은 Origin. 배포 시 백엔드 URL |

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
- **오류 응답 통일**: `@RestControllerAdvice`가 검증 실패는 400, 없는 리소스는 404, 용량 초과는 413으로 `{status, error, message}` JSON을 반환합니다.

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

## 2단계: 인증·인가 없는 휴대폰 라이브 송출 (진행 예정)

> 휴대폰 카메라·마이크 라이브 송출(WebRTC), 시청, 방송 중/종료 상태 표시. 카메라·마이크 권한, 접속 환경, 송출 시작·종료 확인 방법은 이 단계에서 작성합니다.

## 3단계: 인증·인가와 댓글 (진행 예정)

> 회원가입·로그인·로그아웃, 방송 소유자 권한 검사, 댓글, 시청·목록 조회 공개 여부 설명은 이 단계에서 작성합니다.

## 4단계: 도네이션 (진행 예정)

> 토스페이먼츠 테스트 모드 결제, 서버 승인 확인, 중복 통지 방어, 방송별 후원 내역·합계, 테스트 결제 성공·실패·취소·중복 통지 확인 방법은 이 단계에서 작성합니다.
