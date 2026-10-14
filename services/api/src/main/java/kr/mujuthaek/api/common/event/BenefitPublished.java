package kr.mujuthaek.api.common.event;

import java.util.UUID;

/** 새 혜택·지원사업이 등록됨. */
public record BenefitPublished(
    UUID benefitId, String field, String regionCode, String title, boolean reprocess) {}
