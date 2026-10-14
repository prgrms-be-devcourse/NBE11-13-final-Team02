# ADR: 로컬 S3는 bitnamilegacy/minio로

2026-10-15 · 상태: 채택

## 배경
- collector는 원본 파일을 S3에 두고, 로컬에서는 MinIO(`http://localhost:9000`, `minio`/`minio-pass`, 버킷 `mujuthaek-raw`)를 써요.
- 그런데 공식 이미지 `minio/minio`, `minio/mc`를 Docker Hub와 quay.io 모두에서 받을 수 없어요.
- 검토한 대안
  - `cgr.dev/chainguard/minio`: 무료는 `latest`만 있어서 버전을 고정할 수 없어요.
  - RustFS, SeaweedFS: S3 호환이지만 MinIO가 아니라 계정·버킷 설정 방식이 달라요.
  - `bitnamilegacy/minio:2025.7.23`: MinIO 그대로이고 버전을 고정할 수 있어요.

## 결정
- 로컬 compose의 minio는 `bitnamilegacy/minio:2025.7.23`을 써요.
- 버킷은 `MINIO_DEFAULT_BUCKETS=${S3_BUCKET}`으로 처음 뜰 때 만들어요. 따로 돌던 `minio-init` 컨테이너는 없앴어요.
- 계정은 `.env`의 `MINIO_ROOT_USER=minio`, `MINIO_ROOT_PASSWORD=minio-pass`예요. collector 설정 기본값과 같아서, 로컬에서는 환경 변수를 따로 주지 않아도 돼요.

## 이유
- 코드와 설정이 MinIO를 전제로 해서, 같은 서버를 쓰는 게 바꿀 곳이 가장 적어요.
- 버전을 고정해야 팀원마다 같은 동작을 봐요.

## 영향
- bitnamilegacy는 더 이상 갱신되지 않는 보관용 저장소예요. 로컬 개발용으로만 쓰고, 운영은 AWS S3를 써요.
- 이 버전부터 커뮤니티판 MinIO에 웹 콘솔이 없어요. 파일 확인은 `docker compose … exec minio mc ls --recursive local/mujuthaek-raw/`로 해요.
- 이미지를 받을 수 없게 되면 RustFS 등 S3 호환 서버로 바꾸고, `S3_ENDPOINT`·계정만 맞추면 돼요. 코드는 boto3라 그대로예요.
