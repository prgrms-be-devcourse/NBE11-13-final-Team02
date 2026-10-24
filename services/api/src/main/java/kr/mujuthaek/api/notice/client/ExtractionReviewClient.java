package kr.mujuthaek.api.notice.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

  /** 승인. 검수 API가 그 작업의 결과를 다시 발행해요. */
  public JsonNode approve(String jobId, String note) {
    return post(jobId, "approve", note);
  }

  /** 반려. 발행하지 않아요. */
  public JsonNode reject(String jobId, String note) {
    return post(jobId, "reject", note);
  }

  private JsonNode post(String jobId, String action, String note) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (note != null) {
      body.put("note", note);
    }
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
    return RestClient.builder().requestFactory(factory);
  }
}
