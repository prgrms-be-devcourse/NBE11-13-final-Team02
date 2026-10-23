# notification (자바 25 + 스프링 부트, 알림 서버)

api와 따로 빌드·배포하는 알림 서버예요. 다른 서비스와는 RabbitMQ 이벤트로만 이야기하고, DB도 따로 써요(`notification`).
화면(PWA)은 api만 불러요. 받을 사람·받을 주소·문구·회원 설정은 api가 정해서 보내고, 알림 서버는 **보내기만** 해요.

```
src/main/java/kr/mujuthaek/notification/
├─ event/          큐 설정, 이벤트 받기 (processed_events로 한 번만)
├─ notification/   notification.requested 계약, 발송 줄 만들기, 탈퇴 회원 지우기
├─ delivery/       채널과 상관없는 발송: 보낼 시각(묶음·조용한 시간·재시도), 워커, 채널 인터페이스
│  └─ email/       이메일 채널, SES·로그 발송, 인증 코드 메일
└─ support/        UUID v7, 시계(Asia/Seoul)
```

## 이벤트
| 항목 | 값 |
| --- | --- |
| exchange | `mujuthaek.events` (topic, 다른 서비스와 같이 씀) |
| 큐 | `mujuthaek.notification` |
| 받는 이벤트 | `notification.requested` (받을 회원·주소·문구), `email.verification.requested` (인증 코드 메일), `user.withdrawn` — `contracts/schemas/notification-events.draft.md` |
| 역할 | 대상자 찾기·회원 설정 확인·문구·알림함은 api. 알림 서버는 조용한 시간·묶음·중복 방지·SES 발송·재시도만 해요 |
| 테이블 | `deliveries`, `processed_events` — `contracts/schemas/notification-tables.draft.md` |
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

## 발송 구조 (채널 갈아끼우기)

```
notification.requested → NotificationService  회원마다·채널마다 받을 곳이 있으면 발송 줄(deliveries)
                         DeliveryWorker        보낼 시각이 된 줄을 채널에 넘기고 SENT·재시도·FAILED 기록
                         NotificationChannel   채널마다 다른 일만: 요청에서 받을 곳 꺼내기 / 묶음 지원 / 보내기
                           └ EmailChannel → EmailSender (log: 로그만, ses: AWS SES)
```

웹 푸시 붙이기: api가 구독 기기를 저장하고 `recipients[].push`로 보내면, 알림 서버는 `NotificationChannel`을 구현한 `PushChannel` 빈 하나만 추가해요. 발송 줄·중복 거르기·조용한 시간·재시도는 그대로 써요.

메일 설정
- 로컬: `NOTIFICATION_MAIL_PROVIDER=ses`, `NOTIFICATION_MAIL_FROM`(SES에서 인증한 주소), `AWS_PROFILE`(키는 `~/.aws/credentials`에만)
- 배포(EC2): SES 정책을 붙인 IAM 역할을 인스턴스에 연결하고 메타데이터 hop limit을 2로. `.env`에 AWS 키를 넣지 않아요 (넣으면 역할보다 먼저 써요)
