package kr.mujuthaek.api.eligibility.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import kr.mujuthaek.api.eligibility.dto.request.HomeSummaryRequest;
import kr.mujuthaek.api.eligibility.dto.response.HomeSummaryResponse;
import kr.mujuthaek.api.eligibility.dto.response.NoticeSearchResponse;
import kr.mujuthaek.api.eligibility.rule.Applicant;
import kr.mujuthaek.api.eligibility.rule.Overall;
import org.springframework.stereotype.Service;

/** 홈 요약: 맞춤 목록과 같은 판정 결과에서 개수·새 공고·마감 임박·생활권 좋은 곳을 골라요. 판정 입력은 저장하지 않아요. */
@Service
public class HomeSummaryService {

  static final int NEWS_SIZE = 4;
  static final int DEADLINE_SIZE = 3;
  static final int NEW_DAYS = 7;
  static final int CLOSING_DAYS = 7;
  static final int DEADLINE_DAYS = 14;

  private final NoticeSearchService search;
  private final Clock clock;

  public HomeSummaryService(NoticeSearchService search, Clock clock) {
    this.search = search;
    this.clock = clock;
  }

  public HomeSummaryResponse summary(HomeSummaryRequest request) {
    Applicant applicant =
        request.applicant() == null ? Applicant.empty() : request.applicant().toApplicant();
    List<NoticeSearchResponse.Item> judged = search.judgeAll(request.filtersOrEmpty(), applicant);
    LocalDate today = LocalDate.now(clock);
    LocalDate newSince = today.minusDays(NEW_DAYS - 1L);

    List<NoticeSearchResponse.Item> open =
        judged.stream().filter(i -> i.overall() != Overall.INELIGIBLE).toList();
    Predicate<NoticeSearchResponse.Item> isNew =
        i -> i.postedOn() != null && !i.postedOn().isBefore(newSince);
    Predicate<NoticeSearchResponse.Item> closingWithin7 = closingWithin(CLOSING_DAYS);

    var homes =
        new HomeSummaryResponse.Homes(
            count(judged, Overall.ELIGIBLE),
            count(judged, Overall.NEEDS_INPUT),
            count(judged, Overall.INELIGIBLE),
            (int) open.stream().filter(isNew).count(),
            (int) open.stream().filter(closingWithin7).count());

    var bestArea =
        open.stream()
            .filter(i -> i.areaScore() != null)
            .max(
                Comparator.comparing(NoticeSearchResponse.Item::areaScore)
                    .thenComparing(i -> i.overall() == Overall.ELIGIBLE)
                    .thenComparing(
                        NoticeSearchResponse.Item::id, Comparator.<java.util.UUID>reverseOrder()))
            .map(
                i ->
                    new HomeSummaryResponse.BestArea(
                        i.id(), i.title(), i.regionName(), i.areaScore()))
            .orElse(null);

    var news =
        open.stream()
            .filter(i -> i.postedOn() != null)
            .sorted(
                Comparator.comparing(NoticeSearchResponse.Item::postedOn)
                    .reversed()
                    .thenComparing(NoticeSearchResponse.Item::id))
            .limit(NEWS_SIZE)
            .map(HomeSummaryService::notice)
            .toList();

    var deadlines =
        open.stream()
            .filter(closingWithin(DEADLINE_DAYS))
            .sorted(
                Comparator.comparing(NoticeSearchResponse.Item::dday)
                    .thenComparing(NoticeSearchResponse.Item::id))
            .limit(DEADLINE_SIZE)
            .map(HomeSummaryService::notice)
            .toList();

    return new HomeSummaryResponse(homes, bestArea, news, deadlines);
  }

  /** 접수 중이고 오늘부터 days일 안에 마감 (D-0 ~ D-(days-1)). */
  private static Predicate<NoticeSearchResponse.Item> closingWithin(int days) {
    return i ->
        "APPLYING".equals(i.status()) && i.dday() != null && i.dday() >= 0 && i.dday() < days;
  }

  private static HomeSummaryResponse.Notice notice(NoticeSearchResponse.Item i) {
    return new HomeSummaryResponse.Notice(
        i.id(),
        i.title(),
        i.provider(),
        i.noticeType(),
        i.regionName(),
        i.status(),
        i.postedOn(),
        i.applyEnd(),
        i.dday(),
        i.overall());
  }

  private static int count(List<NoticeSearchResponse.Item> items, Overall overall) {
    return (int) items.stream().filter(i -> i.overall() == overall).count();
  }
}
