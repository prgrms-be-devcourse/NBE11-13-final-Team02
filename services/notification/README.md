# notification (자바 25 + 스프링 부트, 알림 서버)

api와 따로 빌드·배포하는 알림 서버예요. 다른 서비스와는 RabbitMQ 이벤트로만 이야기하고, DB도 따로 써요(`notification`).
화면(PWA)은 api만 불러요. 받을 사람·받을 주소·문구는 api가 정해서 보내고, 알림 서버는 **보내기만** 해요 (메일은 AWS SES).

지금은 뼈대예요: 이벤트를 받아 한 번만 처리하는 데까지. 발송 줄·발송 워커·메일 발송은 다음 PR에서 붙여요.

```
src/main/java/kr/mujuthaek/notification/
├─ NotificationApplication.java
└─ event/
   ├─ EventQueueConfig.java       큐 mujuthaek.notification (+ 실패 큐 .dlq), 받는 이벤트 목록
   └─ IncomingEventListener.java  이벤트를 한 번만 처리 (processed_events로 중복 거름)
```

## 이벤트
| 항목 | 값 |
| --- | --- |
| exchange | `mujuthaek.events` (topic, 다른 서비스와 같이 씀) |
| 큐 | `mujuthaek.notification` |
| 받는 이벤트 | `notification.requested` (받을 회원·주소·문구), `email.verification.requested` (인증 코드 메일), `user.withdrawn` (탈퇴 회원 기록 삭제). 모두 api가 보내요 |
| 역할 | 대상자 찾기·회원 설정 확인·문구·알림함은 api. 알림 서버는 조용한 시간·묶음·중복 방지·SES 발송·재시도만 해요 |
| 테이블 | `processed_events` (받은 이벤트 장부). 발송 테이블은 다음 PR |
| 실패 | 봉투가 깨진 메시지는 재시도 없이 `mujuthaek.dlx` → `mujuthaek.notification.dlq` (라우팅 키 `notification.dead`) |

## 실행
```bash
make infra                    # 저장소 루트에서. PostgreSQL·RabbitMQ
make db-notification          # 알림 DB 만들기 (이미 있는 볼륨에 한 번만. 새 볼륨은 자동)
./gradlew spotlessApply       # 포맷 맞추기
./gradlew check               # 포맷 검사 + 테스트 (PostgreSQL·RabbitMQ는 Testcontainers)
./gradlew bootRun             # 실행 http://localhost:8081/actuator/health
```

앱을 한 번 띄우면 RabbitMQ에 `mujuthaek.notification` 큐가 생기고, 그 뒤로는 앱이 꺼져 있어도 이벤트가 큐에 쌓여요.
