package kr.mujuthaek.api.notification.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.common.exception.CustomException;
import kr.mujuthaek.api.common.exception.ErrorCode;
import kr.mujuthaek.api.notification.dto.request.PushSubscribeRequest;
import kr.mujuthaek.api.notification.dto.request.PushUnsubscribeRequest;
import kr.mujuthaek.api.notification.dto.response.PushDeviceResponse;
import kr.mujuthaek.api.notification.dto.response.PushPublicKeyResponse;
import kr.mujuthaek.api.notification.service.internal.PushSubscriptionService;
import kr.mujuthaek.api.user.service.LoginUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웹 푸시 구독 (contracts/schemas/web-push.draft.md 2장). 공개키는 누구나, 기기 등록·해제는 로그인한 회원만 ({@code
 * /api/users/**}).
 */
@RestController
class PushSubscriptionController {

  static final String ME = "/api/users/me/push-subscriptions";

  private final PushSubscriptionService subscriptions;

  PushSubscriptionController(PushSubscriptionService subscriptions) {
    this.subscriptions = subscriptions;
  }

  /** VAPID 공개키. 서버에 설정이 없으면 503이에요 */
  @GetMapping("/api/v1/push/public-key")
  PushPublicKeyResponse publicKey() {
    return new PushPublicKeyResponse(subscriptions.publicKey());
  }

  /** 이 기기 등록·갱신. PWA는 앱을 열 때마다 현재 구독을 보내요 */
  @PutMapping(ME)
  ResponseEntity<Void> subscribe(
      @AuthenticationPrincipal LoginUser user, @Valid @RequestBody PushSubscribeRequest request) {
    subscriptions.subscribe(
        user.userId(),
        request.endpoint(),
        request.keys().p256dh(),
        request.keys().auth(),
        request.userAgent());
    return ResponseEntity.noContent().build();
  }

  /**
   * 이 기기 해제 (본문의 endpoint), 또는 {@code ?scope=all}이면 내 기기 모두 해제. 없어도 204예요.
   *
   * @param scope {@code all}만 돼요
   */
  @DeleteMapping(ME)
  ResponseEntity<Void> unsubscribe(
      @AuthenticationPrincipal LoginUser user,
      @RequestParam(required = false) String scope,
      @RequestBody(required = false) PushUnsubscribeRequest request) {
    if (scope != null) {
      if (!"all".equals(scope)) {
        throw new CustomException(ErrorCode.VALIDATION_FAILED, "scope: all만 돼요");
      }
      subscriptions.unsubscribeAll(user.userId());
    } else {
      if (request == null || request.endpoint() == null || request.endpoint().isBlank()) {
        throw new CustomException(ErrorCode.VALIDATION_FAILED, "endpoint: 꼭 있어야 해요");
      }
      subscriptions.unsubscribe(user.userId(), request.endpoint());
    }
    return ResponseEntity.noContent().build();
  }

  /** 내 기기 목록 (최근에 쓴 기기부터) */
  @GetMapping(ME)
  List<PushDeviceResponse> devices(@AuthenticationPrincipal LoginUser user) {
    return subscriptions.devices(user.userId()).stream()
        .map(d -> new PushDeviceResponse(d.id(), d.userAgent(), d.createdAt(), d.updatedAt()))
        .toList();
  }

  /** 목록에서 고른 기기 해제. 내 기기가 아니면 404예요 */
  @DeleteMapping(ME + "/{id}")
  ResponseEntity<Void> remove(@AuthenticationPrincipal LoginUser user, @PathVariable UUID id) {
    subscriptions.remove(user.userId(), id);
    return ResponseEntity.noContent().build();
  }
}
