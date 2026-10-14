package kr.mujuthaek.api.common.event;

import java.time.LocalDate;
import java.util.UUID;

/** 공고 접수가 마감됨. */
public record NoticeClosed(UUID noticeId, LocalDate closedOn) {}
