package kr.mujuthaek.api.common.pagination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;

/** 커서를 URL에 넣기 좋은 문자열로 바꾸고 되돌려요. */
public final class CursorCodec {

  private CursorCodec() {}

  public static String encode(CursorKey key) {
    String raw = key.createdAt().toString() + "|" + key.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** 커서가 없으면 null (첫 페이지). */
  public static CursorKey decode(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split("\\|", 2);
      return new CursorKey(Instant.parse(parts[0]), UUID.fromString(parts[1]));
    } catch (RuntimeException e) {
      throw new CustomException(ErrorCode.INVALID_CURSOR, ErrorCode.INVALID_CURSOR.message());
    }
  }
}
