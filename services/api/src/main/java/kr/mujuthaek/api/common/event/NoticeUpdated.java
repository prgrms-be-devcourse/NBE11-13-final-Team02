package kr.mujuthaek.api.common.event;

import java.util.UUID;

/** 공고가 정정됨. changedFields에 바뀐 항목(applyEndOn, depositWon 등)이 들어가요. */
public record NoticeUpdated(
    UUID noticeId, java.util.List<String> changedFields, boolean reprocess) {}
