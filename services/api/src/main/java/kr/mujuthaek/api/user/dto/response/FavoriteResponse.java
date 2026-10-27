package kr.mujuthaek.api.user.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * 관심 공고·관심 정책 하나. 화면은 targetType에 맞는 상세 API로 내용을 불러요.
 *
 * @param targetType NOTICE · BENEFIT · STARTUP_SUPPORT · SPACE_RECRUITMENT
 */
public record FavoriteResponse(String targetType, UUID targetId, Instant createdAt) {}
