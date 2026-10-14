package kr.mujuthaek.api.common.pagination;

import java.time.Instant;
import java.util.UUID;

/** 커서 = 마지막 항목의 (생성 시각, ID). 같은 시각이어도 ID로 순서가 정해져요. */
public record CursorKey(Instant createdAt, UUID id) {}
