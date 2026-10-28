package kr.mujuthaek.api.chat.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 채팅방 = 공고 하나. 정정으로 공고 버전이 바뀌어도 같은 방이에요.
 *
 * @param noticeId 방 ID. 다른 공고에 합쳐진 공고면 남은 공고 ID
 * @param applyEnd 접수 마감일. 상시 공고는 null
 */
public record ChatRoom(UUID noticeId, LocalDate applyEnd) {

  /** 접수 마감 뒤 이만큼 지나면 읽기 전용이에요. */
  public static final int READ_ONLY_AFTER_DAYS = 30;

  /** 읽기 전용이 되는 날 (이날부터). 마감일이 없는 공고는 null: 읽기 전용이 되지 않아요. */
  public LocalDate readOnlyFrom() {
    return applyEnd == null ? null : applyEnd.plusDays(READ_ONLY_AFTER_DAYS + 1);
  }

  /** 접수 마감일 + 30일 < 오늘이면 읽기 전용 (보내기·답장·좋아요를 막아요). */
  public boolean readOnly(LocalDate today) {
    LocalDate from = readOnlyFrom();
    return from != null && !today.isBefore(from);
  }
}
