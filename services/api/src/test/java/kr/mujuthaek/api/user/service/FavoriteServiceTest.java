package kr.mujuthaek.api.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujuthaek.api.benefit.service.BenefitQueryService;
import kr.mujuthaek.api.benefit.service.BenefitSummary;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.notice.service.NoticeQueryService;
import kr.mujuthaek.api.notice.service.NoticeSummary;
import kr.mujuthaek.api.user.repository.FavoriteRepository;
import org.junit.jupiter.api.Test;

/** 관심 공고 저장: 합쳐진 공고, 숨긴·종류가 다른 정책, 200개 상한 (DB 없이). */
class FavoriteServiceTest {

  final FavoriteRepository repository = mock(FavoriteRepository.class);
  final NoticeQueryService notices = mock(NoticeQueryService.class);
  final BenefitQueryService benefits = mock(BenefitQueryService.class);
  final FavoriteService service =
      new FavoriteService(
          repository,
          notices,
          benefits,
          Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC));
  final UUID user = UUID.randomUUID();

  @Test
  void mergedNoticeIsSavedAsTheRemainingOne() {
    UUID asked = UUID.randomUUID();
    UUID remaining = UUID.randomUUID();
    when(notices.findSummary(asked)).thenReturn(Optional.of(notice(remaining)));

    assertThat(service.add(user, "notice", asked)).isEqualTo(remaining);
    verify(repository).add(eq(user), eq("NOTICE"), eq(remaining), any());
  }

  @Test
  void hiddenOrWrongKindOfBenefitIsNotFound() {
    UUID hidden = UUID.randomUUID();
    UUID space = UUID.randomUUID();
    when(benefits.findSummary(hidden))
        .thenReturn(Optional.of(benefit(hidden, "BENEFIT", "HIDDEN")));
    when(benefits.findSummary(space))
        .thenReturn(Optional.of(benefit(space, "SPACE_RECRUITMENT", "APPLYING")));

    assertThatThrownBy(() -> service.add(user, "BENEFIT", hidden)).satisfies(e -> notFound(e));
    assertThatThrownBy(() -> service.add(user, "BENEFIT", space)).satisfies(e -> notFound(e));
    assertThat(service.add(user, "space-recruitment", space)).isEqualTo(space);
  }

  @Test
  void limitStopsNewOnesButNotSavingAgain() {
    UUID id = UUID.randomUUID();
    when(notices.findSummary(any())).thenAnswer(i -> Optional.of(notice(i.getArgument(0))));
    when(repository.count(user)).thenReturn((long) FavoriteService.MAX_FAVORITES);
    when(repository.exists(user, "NOTICE", id)).thenReturn(true);

    assertThat(service.add(user, "NOTICE", id)).isEqualTo(id); // 이미 있는 것은 그대로
    assertThatThrownBy(() -> service.add(user, "NOTICE", UUID.randomUUID()))
        .isInstanceOfSatisfying(
            CustomException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FAVORITE_LIMIT_EXCEEDED));
    verify(repository, never()).add(any(), anyString(), any(), any());
  }

  private static void notFound(Throwable e) {
    assertThat(((CustomException) e).errorCode()).isEqualTo(ErrorCode.FAVORITE_TARGET_NOT_FOUND);
  }

  private static NoticeSummary notice(UUID id) {
    return new NoticeSummary(
        id, 1, "HOUSING", "t", null, null, "APPLYING", null, null, List.of(), false, "/", null);
  }

  private static BenefitSummary benefit(UUID id, String field, String status) {
    return new BenefitSummary(
        id, field, 1, "t", null, status, null, null, false, "NATIONWIDE", List.of(), null);
  }
}
