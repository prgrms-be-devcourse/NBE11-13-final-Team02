package kr.mujuthaek.api.user.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import kr.mujuthaek.api.benefit.service.BenefitQueryService;
import kr.mujuthaek.api.benefit.service.BenefitSummary;
import kr.mujuthaek.api.common.dto.response.CursorPageResponse;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.common.pagination.CursorKey;
import kr.mujuthaek.api.common.pagination.CursorQuery;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import kr.mujuthaek.api.notice.service.NoticeSummary;
import kr.mujuthaek.api.notice.service.PolicySummary;
import kr.mujuthaek.api.user.dto.response.FavoriteResponse;
import kr.mujuthaek.api.user.repository.FavoriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관심 공고·관심 정책 (contracts/schemas/user-preferences.draft.md 2-1). 저장한 공고의 정정·후속 안내·마감 D-3·D-1 알림을
 * 받아요.
 *
 * <p>저장할 때 그 모듈 조회 메서드로 있는지 확인해요 (다른 모듈 테이블이라 FK가 없어서). 다른 공고에 합쳐진 공고는 남은 공고로 저장해요.
 */
@Service
public class FavoriteService {

  public static final int MAX_FAVORITES = 200;
  static final String SORT = "LATEST";

  private final FavoriteRepository favorites;
  private final NoticeQueryService notices;
  private final BenefitQueryService benefits;
  private final Clock clock;

  public FavoriteService(
      FavoriteRepository favorites,
      NoticeQueryService notices,
      BenefitQueryService benefits,
      Clock clock) {
    this.favorites = favorites;
    this.notices = notices;
    this.benefits = benefits;
    this.clock = clock;
  }

  /** 저장. 이미 저장했으면 그대로예요. 저장한 대상 ID를 돌려줘요 (합쳐진 공고면 남은 공고 ID) */
  @Transactional
  public UUID add(UUID userId, String targetType, UUID targetId) {
    String type = targetType(targetType);
    UUID resolved = resolve(type, targetId);
    if (favorites.exists(userId, type, resolved)) {
      return resolved; // 다시 저장해도 그대로 (처음 저장한 시각 유지)
    }
    if (favorites.count(userId) >= MAX_FAVORITES) {
      throw new CustomException(ErrorCode.FAVORITE_LIMIT_EXCEEDED);
    }
    favorites.add(userId, type, resolved, clock.instant());
    return resolved;
  }

  /** 지우기. 없어도 조용히 끝나요 */
  @Transactional
  public void remove(UUID userId, String targetType, UUID targetId) {
    favorites.remove(userId, targetType(targetType), targetId);
  }

  /** 최근 저장한 것부터. {@code targetType}이 비면 모두 */
  @Transactional(readOnly = true)
  public CursorPageResponse<FavoriteResponse> list(
      UUID userId, String targetType, String cursor, Integer size) {
    String type = targetType == null || targetType.isBlank() ? null : targetType(targetType);
    String sort = SORT + (type == null ? "" : ":" + type); // 거르는 종류가 바뀌면 처음부터
    CursorQuery query = CursorQuery.of(cursor, size, sort);
    Instant afterAt = null;
    UUID afterId = null;
    if (query.after() != null) {
      try {
        afterAt = Instant.parse(query.after().value());
      } catch (RuntimeException e) {
        throw new CustomException(ErrorCode.INVALID_CURSOR, ErrorCode.INVALID_CURSOR.message());
      }
      afterId = query.after().id();
    }
    return CursorPageResponse.from(
        favorites.page(userId, type, afterAt, afterId, query.fetchSize()),
        query.size(),
        f -> new FavoriteResponse(f.targetType(), f.targetId(), f.createdAt()),
        f -> CursorKey.of(sort, f.createdAt(), f.targetId()));
  }

  private UUID resolve(String type, UUID id) {
    return switch (type) {
      case "NOTICE" ->
          notices
              .findSummary(id)
              .map(NoticeSummary::noticeId)
              .orElseThrow(FavoriteService::notFound);
      case "BENEFIT" -> {
        BenefitSummary b = benefits.findSummary(id).orElseThrow(FavoriteService::notFound);
        if ("HIDDEN".equals(b.status())) {
          throw notFound();
        }
        yield b.benefitId();
      }
      // 창업지원 사업·사업공간 모집은 notice.policy_judgments에 있어요 (정책 ID = rawPolicyId)
      case "STARTUP_SUPPORT", "SPACE_RECRUITMENT" ->
          notices
              .findPolicySummary(id)
              .filter(p -> p.field().equals(type))
              .map(PolicySummary::policyId)
              .orElseThrow(FavoriteService::notFound);
      default -> throw notFound();
    };
  }

  /** NOTICE · BENEFIT · STARTUP_SUPPORT · SPACE_RECRUITMENT (대소문자 상관없이) */
  static String targetType(String value) {
    String type = value == null ? "" : value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
    return switch (type) {
      case "NOTICE", "BENEFIT", "STARTUP_SUPPORT", "SPACE_RECRUITMENT" -> type;
      default ->
          throw new CustomException(
              ErrorCode.VALIDATION_FAILED,
              "targetType: NOTICE·BENEFIT·STARTUP_SUPPORT·SPACE_RECRUITMENT 중 하나예요");
    };
  }

  private static CustomException notFound() {
    return new CustomException(ErrorCode.FAVORITE_TARGET_NOT_FOUND);
  }
}
