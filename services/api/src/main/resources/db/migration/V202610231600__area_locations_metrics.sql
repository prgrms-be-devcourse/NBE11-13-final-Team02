-- area 3·4단계 저장 테이블: 공고 위치의 좌표(notice_locations)와 세권 지표(location_metrics).
-- 이름은 notice가 보내는 NoticeLocationsChanged(위치 ID = location_id)에 맞춰요. 공고 테이블 외래 키는 공고 연결 때 붙여요.
-- 좌표 찾기(3단계)보다 먼저 만드는 건 세권 지표가 이 테이블의 위치를 가리켜서예요.

-- 3단계 결과: 공고 위치마다 좌표. location_id는 notice가 주는 위치 ID.
create table area.notice_locations (
    location_id     uuid        primary key,
    notice_id       uuid        not null,
    notice_version  integer     not null,
    kind            text        not null,                -- 위치 종류 (단지, 주택목록 호 …)
    address_text    text        not null,                -- 받은 주소 원문
    address_key     text,                                -- 정리한 주소 (같은 주소면 다시 찾지 않으려고)
    bjd_code_given  text,                                -- 받은 법정동코드 10자리 (없으면 null)
    bjd_code_found  text,                                -- 찾은 좌표의 법정동코드
    geom            geography(Point, 4326),              -- FAILED면 null
    status          text        not null check (status in ('OK', 'APPROX', 'FAILED')),
    method          text        check (method in ('FILE', 'FUZZY', 'SEARCH_API', 'COORD_API', 'CENTROID')),
    fail_reason     text,                                -- NOT_FOUND, OUT_OF_REGION, PARSE_FAILED, API_ERROR …
    region_mismatch boolean     not null default false,  -- 받은 법정동과 찾은 법정동이 다름
    calculated_at   timestamptz not null default now()
);
create index notice_locations_notice_idx on area.notice_locations (notice_id);
create index notice_locations_key_idx on area.notice_locations (address_key);
create index notice_locations_geom_gix on area.notice_locations using gist (geom);

-- 4단계 결과: 위치마다 지표 (긴 모양: 지표 하나에 한 줄). 근처에 그 시설이 아예 없으면 줄이 없어요.
create table area.location_metrics (
    location_id   uuid        not null references area.notice_locations (location_id) on delete cascade,
    metric        text        not null,
    value         numeric     not null,
    calculated_at timestamptz not null default now(),
    primary key (location_id, metric)
);
