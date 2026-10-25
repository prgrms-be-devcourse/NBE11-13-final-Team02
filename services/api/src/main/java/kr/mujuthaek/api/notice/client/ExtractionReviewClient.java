package kr.mujuthaek.api.notice.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * 추출 서비스 검수 API 클라이언트. 목록·상세는 받은 JSON 그대로, 승인·반려는 메모만 보내요(고친 결과를 보내는 칸별 수정은 아직 없어요).
 *
 * <p>오류: 404는 {@link ErrorCode#NOTICE_REVIEW_NOT_FOUND}, 409(이미 처리됨·새 버전으로 대체됨)는 {@link
 * ErrorCode#NOTICE_REVIEW_CONFLICT}, 422(형식이 맞지 않음)는 {@link ErrorCode#NOTICE_REVIEW_INVALID}로 바꾸고
 * 검수 API의 설명(error)을 그대로 detail에 넣어요. 연결 실패·인증 실패·5xx는 {@link
 * ErrorCode#NOTICE_REVIEW_UNAVAILABLE}이고 토큰·주소는 로그에 남기지 않아요.
 */
@Component
public class ExtractionReviewClient {

  private static final Logger log = LoggerFactory.getLogger(ExtractionReviewClient.class);

  private final RestClient http;

  @Autowired
  public ExtractionReviewClient(ExtractionReviewProperties properties) {
    this(properties, defaultBuilder(properties));
  }

  /** 요청 빌더를 받아요(시험에서 가짜 서버를 붙일 때). */
  public ExtractionReviewClient(ExtractionReviewProperties properties, RestClient.Builder builder) {
    builder.baseUrl(properties.baseUrl());
    if (properties.hasToken()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token());
    }
    this.http = builder.build();
  }

  /** 그 상태(PENDING·APPROVED·REJECTED·SUPERSEDED)의 검수 목록. 응답의 items 배열. */
  public JsonNode list(String status) {
    JsonNode body =
        call(() -> http.get().uri("/reviews?status={s}", status).retrieve().body(JsonNode.class));
    return body == null ? null : body.get("items");
  }

  /** 검수 상세(후보·이슈·받은 이벤트 포함). */
  public JsonNode detail(String jobId) {
    return call(() -> http.get().uri("/reviews/{id}", jobId).retrieve().body(JsonNode.class));
  }

  /** 처리 통계(최근 days일·오늘). */
  public JsonNode stats(int days) {
    return call(
        () -> http.get().uri("/reviews/stats?days={d}", days).retrieve().body(JsonNode.class));
  }

  /**
   * 승인. 검수 API가 그 작업의 결과를 다시 발행해요.
   *
   * @param edits 칸별 수정 [{path, value}]. 비면 그대로 승인
   * @param reviewer 처리 기록에 남길 검수자(로그인 계정 ID)
   */
  public JsonNode approve(
      String jobId, String note, String reviewer, List<Map<String, Object>> edits) {
    Map<String, Object> body = body(note, reviewer);
    if (edits != null && !edits.isEmpty()) {
      body.put("edits", edits);
    }
    return post(jobId, "approve", body);
  }

  /**
   * 이미 발행한 결과를 검수 대기로 다시 열어요(사용자 제보로 공고 값을 고칠 때). 응답: {jobId, reviewStatus, reopened}.
   *
   * @param rawPostId 공고의 지금 원본 글 ID
   */
  public JsonNode reopen(String rawPostId, String reviewer, String note) {
    Map<String, Object> body = body(note, reviewer);
    body.put("rawPostId", rawPostId);
    return call(
        () ->
            http.post()
                .uri("/reviews/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
  }

  /** 반려. 발행하지 않아요. */
  public JsonNode reject(String jobId, String note, String reviewer) {
    return post(jobId, "reject", body(note, reviewer));
  }

  /** 혜택(주거·이주 정책) 검수 목록. 응답의 items 배열. */
  public JsonNode benefitList(String status) {
    JsonNode body =
        call(
            () ->
                http.get()
                    .uri("/benefit-reviews?status={s}", status)
                    .retrieve()
                    .body(JsonNode.class));
    return body == null ? null : body.get("items");
  }

  /** 혜택 검수 상세: payload(정제 결과)·source(받은 원본). */
  public JsonNode benefitDetail(String reviewId) {
    return call(
        () -> http.get().uri("/benefit-reviews/{id}", reviewId).retrieve().body(JsonNode.class));
  }

  /**
   * 혜택 검수 승인(approve)·반려(reject). 검수 API가 고친 결과를 다시 발행해요(반려는 relevant=false).
   *
   * @param edits 칸별 수정 [{path, value}]. 승인에서만 보내요
   */
  public JsonNode benefitDecide(
      String reviewId,
      String action,
      String note,
      String reviewer,
      List<Map<String, Object>> edits) {
    Map<String, Object> body = body(note, reviewer);
    if (edits != null && !edits.isEmpty()) {
      body.put("edits", edits);
    }
    return call(
        () ->
            http.post()
                .uri("/benefit-reviews/{id}/{action}", reviewId, action)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
  }

  /** 정책 제보로 그 정책의 가장 새 정제 결과를 검수 대기로 다시 열어요. 응답: {reviewId, reopened}. */
  public JsonNode benefitReopen(String rawPolicyId, String reviewer, String note) {
    Map<String, Object> body = body(note, reviewer);
    body.put("rawPolicyId", rawPolicyId);
    return call(
        () ->
            http.post()
                .uri("/benefit-reviews/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
  }

  /** 그 정책의 가장 새 정제 결과 요약(제목·출처·relevant). 없으면 {@link ErrorCode#NOTICE_REVIEW_NOT_FOUND}. */
  public JsonNode benefit(String rawPolicyId) {
    return call(
        () -> http.get().uri("/benefits/{id}", rawPolicyId).retrieve().body(JsonNode.class));
  }

  private static Map<String, Object> body(String note, String reviewer) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (note != null) {
      body.put("note", note);
    }
    if (reviewer != null) {
      body.put("reviewer", reviewer);
    }
    return body;
  }

  private JsonNode post(String jobId, String action, Map<String, Object> body) {
    return call(
        () ->
            http.post()
                .uri("/reviews/{id}/{action}", jobId, action)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
  }

  private JsonNode call(Supplier<JsonNode> request) {
    try {
      return request.get();
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      String detail = upstreamError(e);
      switch (status) {
        case 404 -> throw new CustomException(ErrorCode.NOTICE_REVIEW_NOT_FOUND, detail);
        case 409 -> throw new CustomException(ErrorCode.NOTICE_REVIEW_CONFLICT, detail);
        case 400, 422 -> throw new CustomException(ErrorCode.NOTICE_REVIEW_INVALID, detail);
        default -> {
          log.warn("검수 API가 오류로 답했어요 (status {})", status);
          throw new CustomException(ErrorCode.NOTICE_REVIEW_UNAVAILABLE);
        }
      }
    } catch (RestClientException e) {
      log.warn("검수 API에 연결하지 못했어요 ({})", e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.NOTICE_REVIEW_UNAVAILABLE);
    }
  }

  /** 검수 API 오류 본문의 error(한국어 설명). 없으면 null(공통 문구를 써요). */
  private static String upstreamError(RestClientResponseException e) {
    try {
      JsonNode body = e.getResponseBodyAs(JsonNode.class);
      JsonNode error = body == null ? null : body.get("error");
      return error != null && error.isString() ? error.asString() : null;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static RestClient.Builder defaultBuilder(ExtractionReviewProperties properties) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.timeout());
    factory.setReadTimeout(properties.timeout());
    // 본문을 모아 Content-Length를 붙여 보내요. 조각 전송(chunked)이면 파이썬 http.server가 본문을 빈 것으로 읽어 승인·반려·다시 열기가
    // 400이 됐어요(2026-10-25 로컬 실제 흐름에서 발견, 가짜 서버 시험으로는 안 보임)
    return RestClient.builder().requestFactory(new BufferingClientHttpRequestFactory(factory));
  }
}
