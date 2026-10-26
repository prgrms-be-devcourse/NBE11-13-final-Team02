package kr.mujuthaek.api.notification.service.internal;

import java.util.List;

/**
 * 알림 받을 회원 찾기. 관심 조건(지역·지원 종류)과 관심 공고, 알림 설정·이메일로 골라요.
 *
 * <p>관심 조건·알림 설정 테이블(contracts/schemas/user-preferences.draft.md)은 아직 없어서, 지금은 {@link
 * ConfiguredRecipients}(설정 파일의 테스트 회원)만 있어요. 테이블이 생기면 user 모듈 조회 메서드를 쓰는 구현으로 바꿔요.
 */
public interface RecipientFinder {

  /** 새 지원 정책·창업지원·사업공간 모집에 관심 있는 회원 */
  List<Recipient> forNewBenefit(String field, String regionScope, List<String> regionCodes);

  /**
   * 새 주거·사업공간 공고에 관심 있는 회원 (주거면 housing_interests, 사업공간이면 business_interests)
   *
   * @param regionCodes 공고가 걸친 지역 5자리 (NoticePublished.regionCodes)
   */
  List<Recipient> forNewNotice(String field, List<String> regionCodes, boolean nationwide);

  /** 이 공고·지원 정책·사업공간 모집을 관심 공고로 둔 회원 (targetType NOTICE·BENEFIT·SPACE_RECRUITMENT) */
  List<Recipient> forFavorite(String targetType, java.util.UUID targetId);
}
