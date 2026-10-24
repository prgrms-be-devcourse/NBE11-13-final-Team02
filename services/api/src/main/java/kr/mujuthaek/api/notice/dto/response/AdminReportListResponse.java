package kr.mujuthaek.api.notice.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 관리자 제보 목록 (최근 것부터 최대 100건)과 상태별 건수. */
public record AdminReportListResponse(Counts counts, List<Item> items) {

  /** 상태별 전체 건수 (사이드바·탭 숫자). */
  public record Counts(int newCount, int doing, int done) {}

  /**
   * 제보 한 건.
   *
   * @param status NEW 새 제보 · DOING 처리 중 · DONE 처리 끝
   * @param screen 제보한 화면
   * @param field 칸 이름
   * @param summary 설명 앞부분
   * @param groupCount 같은 공고·칸의 처리 전 제보 수 (이 제보 포함, 끝난 제보면 0)
   * @param result 처리 결과 FIXED · KEPT (끝나기 전엔 null)
   */
  public record Item(
      UUID id,
      String status,
      String screen,
      UUID noticeId,
      String noticeTitle,
      String field,
      String currentValue,
      String reportedValue,
      String summary,
      OffsetDateTime createdAt,
      int groupCount,
      String result) {}
}
