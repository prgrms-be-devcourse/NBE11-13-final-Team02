# ADR: 브랜치 흐름과 CI·커버리지 기준

2026-10-14 · 상태: 채택

## 배경
- 팀원 4명이 모두 레포 admin이에요. GitHub는 본인 PR을 본인이 승인할 수 없게 막아요.
- 그래서 폴더마다 담당자를 한 명만 두고 "담당자 승인 필수"를 켜면, 담당자가 자기 폴더를 고친 PR은 승인해 줄 사람이 없어서 머지할 수 없어요.
- 배포 브랜치(`main`)에는 검증된 묶음만 들어가야 해요.

## 결정
- **브랜치 흐름**: `기능 브랜치 → dev → main`
  - 기본 브랜치는 `dev`예요. 새 PR은 자동으로 dev를 향해요.
  - 기능 → dev는 **squash**로 머지해요.
  - dev → main은 **merge commit**으로 머지해요. squash로 합치면 dev와 main 기록이 어긋나서, 다음 dev → main 때 이미 들어간 변경이 다시 diff로 떠요.
- **룰셋** (`main 보호`, `dev 보호`)
  - 공통: 삭제 금지, 강제 push 금지, PR 필수, 승인 1명(아무 팀원), 새 커밋이 오면 기존 승인 취소
  - 담당자(CODEOWNERS) 승인은 필수로 하지 않아요. CODEOWNERS는 리뷰어 자동 지정에만 써요.
  - 필수 체크: `api (spotless · test · 모듈 경계)`, `python (ruff)`
  - main은 체크 하나가 더 있어요: `main 머지는 dev에서만`
- **main은 dev에서만**: GitHub 룰셋에는 "출발 브랜치 제한" 기능이 없어요. 그래서 `.github/workflows/main-guard.yml`이 출발 브랜치가 `dev`가 아니면 실패하게 하고, 이 체크를 main의 필수 체크로 걸었어요.
- **CI** (`.github/workflows/ci.yml`)
  - api 잡은 `./gradlew check`를 돌려요(Spotless, 컴파일, `ModuleStructureTest`, Testcontainers 테스트, JaCoCo 기준).
  - python 잡은 ruff로 검사해요.
  - Gradle 캐시는 `gradle/actions/setup-gradle`이 맡아요.
- **커버리지 기준**: JaCoCo로 각 모듈의 `service` 패키지와 `eligibility/rule`의 라인 커버리지를 재요.
  - **80% 미만이면 `check` 실패**예요.
  - PR에는 커버리지 댓글이 달려요.
- **DB 테스트**: Testcontainers(PostGIS 이미지)를 써요. CI에 DB 서비스를 따로 띄우지 않아요.

## 이유
- 승인을 아무 팀원 1명으로 해야 누구도 자기 폴더 때문에 막히지 않아요.
- 측정 대상을 업무 규칙이 있는 곳(service, rule)으로 좁혀야 80%가 의미 있는 숫자가 돼요. controller·dto·설정까지 넣으면 숫자만 맞추는 테스트가 늘어요.
- Testcontainers를 쓰면 로컬과 CI가 같은 DB 버전으로 테스트해요.

## 영향
- 로컬에서 `./gradlew check`를 돌리려면 Docker가 켜져 있어야 해요.
- 서비스 코드는 첫 클래스부터 80% 기준이 적용돼요. 서비스는 테스트와 함께 올려요.
- dev → main PR도 승인 1명이 필요해요.
