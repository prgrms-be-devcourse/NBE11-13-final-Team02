package kr.mujuthaek.api.common.event;

import java.util.UUID;

/** 새 공고가 등록됨. area(점수 계산)와 notification(사용자 알림)이 받아요. reprocess면 사용자 알림을 보내지 않아요. */
public record NoticePublished(
    UUID noticeId, String field, String regionCode, String title, boolean reprocess) {}
