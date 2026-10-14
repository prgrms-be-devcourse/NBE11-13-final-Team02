# 로컬 개발 명령. 저장소 루트에서 실행해요. 자세한 내용: .docs/개발 환경.md
COMPOSE = docker compose -f infra/docker-compose.yml --env-file infra/.env

.PHONY: help infra up down clean migrate-collector ps logs

help: ## 명령 목록
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-18s %s\n", $$1, $$2}'

infra/.env:
	cp infra/.env.example infra/.env

infra: infra/.env ## PostgreSQL·RabbitMQ·MinIO 띄우기 (수집기·api는 직접 실행)
	$(COMPOSE) up -d --wait postgres rabbitmq minio

up: infra/.env ## 앱까지 전부 Docker로 띄우기 (apps 프로필)
	$(COMPOSE) --profile apps up -d --build

down: ## 모두 끄기 (데이터는 볼륨에 남아요)
	$(COMPOSE) --profile apps down

clean: ## 모두 끄고 데이터 볼륨까지 지우기 (DB·버킷 초기화)
	$(COMPOSE) --profile apps down -v

migrate-collector: infra/.env ## collector 데이터베이스 테이블 만들기·갱신 (Flyway)
	$(COMPOSE) --profile apps run --rm migrate-collector

ps: ## 컨테이너 상태
	$(COMPOSE) --profile apps ps

logs: ## 로그 보기 (예: make logs s=minio)
	$(COMPOSE) --profile apps logs -f --tail=100 $(s)
