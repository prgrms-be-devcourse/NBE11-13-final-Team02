# api (자바 25 + 스프링 부트, 모듈러 모놀리스)

```
src/main/java/kr/mujuthaek/api/
├─ ApiApplication.java
├─ common/          event/ · dto/response/ · exception/ · pagination/ · config/   (업무 규칙 없음)
├─ notice/          공고                     ┐
├─ benefit/         혜택·창업지원            │  모듈마다: controller · service · repository · domain ·
├─ eligibility/     판정·준비할 돈 (+ rule/) │           model(필요할 때) · dto/request · dto/response ·
├─ area/            생활권·상권              │           mapper · event
├─ user/            로그인·관심 (account)    │
└─ notification/    사용자 알림·웹 푸시      ┘
```

## 규칙 요약
- 다른 모듈 데이터는 서비스 계층에서 그 모듈 서비스의 **조회 메서드**로. 한 방향으로만, record로만
- 다른 모듈의 repository·테이블은 쓰지 않음. 다른 모듈에 영향을 주는 변경은 `common/event` 이벤트로
- 각 모듈의 `service` 패키지만 공개(`@NamedInterface`). `ModuleStructureTest`가 경계와 순환을 검사

자세한 규칙은 저장소 루트의 `CLAUDE.md`와 팀 컨벤션 문서를 봐요.

## 실행
```bash
./gradlew spotlessApply       # 포맷 맞추기
./gradlew check               # 포맷 검사 + 테스트 (모듈 경계 포함)
./gradlew bootRun             # 실행 (DB·RabbitMQ는 infra에서 먼저 띄우기)
```
