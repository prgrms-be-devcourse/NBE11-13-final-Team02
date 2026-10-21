package kr.mujuthaek.api.common.pagination;

import java.util.Objects;
import java.util.UUID;

/**
 * 커서 = 마지막 항목의 (정렬 이름, 정렬 값, ID). 같은 정렬 값이어도 ID로 순서가 정해져요.
 *
 * @param sort 이 커서를 만든 정렬 (예: DEADLINE, LATEST). 다른 정렬의 커서는 받지 않아요
 * @param value 정렬 값을 글자로 (마감일 2026-10-31, 시각 2026-10-22T03:00:00Z …). 값이 없는 항목은 빈 글자
 */
public record CursorKey(String sort, String value, UUID id) {

  public CursorKey {
    Objects.requireNonNull(sort, "sort");
    Objects.requireNonNull(id, "id");
    value = value == null ? "" : value;
  }

  public static CursorKey of(String sort, Object value, UUID id) {
    return new CursorKey(sort, value == null ? null : value.toString(), id);
  }
}
