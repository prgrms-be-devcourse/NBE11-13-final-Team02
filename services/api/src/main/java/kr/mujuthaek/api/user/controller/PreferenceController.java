package kr.mujuthaek.api.user.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.mujuthaek.api.common.dto.response.CursorPageResponse;
import kr.mujuthaek.api.user.dto.request.InterestRequests;
import kr.mujuthaek.api.user.dto.request.NotificationSettingsRequest;
import kr.mujuthaek.api.user.dto.response.FavoriteResponse;
import kr.mujuthaek.api.user.dto.response.InterestResponses;
import kr.mujuthaek.api.user.dto.response.NotificationSettingsResponse;
import kr.mujuthaek.api.user.repository.InterestRepository.Business;
import kr.mujuthaek.api.user.repository.InterestRepository.Housing;
import kr.mujuthaek.api.user.repository.InterestRepository.Support;
import kr.mujuthaek.api.user.repository.NotificationSettingRepository.Settings;
import kr.mujuthaek.api.user.service.FavoriteService;
import kr.mujuthaek.api.user.service.InterestService;
import kr.mujuthaek.api.user.service.LoginUser;
import kr.mujuthaek.api.user.service.NotificationSettingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관심 공고·관심 조건·알림 설정 (contracts/schemas/user-preferences.draft.md 2장). 로그인한 회원만 ({@code
 * /api/users/**}). 알림 대상자를 고르는 데 써요.
 */
@RestController
@RequestMapping("/api/users/me")
class PreferenceController {

  private final FavoriteService favorites;
  private final InterestService interests;
  private final NotificationSettingService settings;

  PreferenceController(
      FavoriteService favorites, InterestService interests, NotificationSettingService settings) {
    this.favorites = favorites;
    this.interests = interests;
    this.settings = settings;
  }

  /** 관심 공고·정책 저장 (이미 있어도 204). 없는 대상은 404, 200개를 넘으면 409 */
  @PutMapping("/favorites/{targetType}/{targetId}")
  ResponseEntity<Void> addFavorite(
      @AuthenticationPrincipal LoginUser user,
      @PathVariable String targetType,
      @PathVariable UUID targetId) {
    favorites.add(user.userId(), targetType, targetId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/favorites/{targetType}/{targetId}")
  ResponseEntity<Void> removeFavorite(
      @AuthenticationPrincipal LoginUser user,
      @PathVariable String targetType,
      @PathVariable UUID targetId) {
    favorites.remove(user.userId(), targetType, targetId);
    return ResponseEntity.noContent().build();
  }

  /** 최근 저장한 것부터. targetType으로 거를 수 있어요 */
  @GetMapping("/favorites")
  CursorPageResponse<FavoriteResponse> favorites(
      @AuthenticationPrincipal LoginUser user,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return favorites.list(user.userId(), targetType, cursor, size);
  }

  @GetMapping("/interests/housing")
  InterestResponses.Housing housing(@AuthenticationPrincipal LoginUser user) {
    return interests
        .housing(user.userId())
        .map(h -> housing(true, h))
        .orElse(new InterestResponses.Housing(false, List.of(), true, List.of()));
  }

  @PutMapping("/interests/housing")
  InterestResponses.Housing saveHousing(
      @AuthenticationPrincipal LoginUser user, @RequestBody InterestRequests.Housing body) {
    return housing(
        true,
        interests.saveHousing(
            user.userId(),
            new Housing(
                body.regionCodes(), nationwide(body.includeNationwide()), body.noticeTypes())));
  }

  @GetMapping("/interests/business")
  InterestResponses.Business business(@AuthenticationPrincipal LoginUser user) {
    return interests
        .business(user.userId())
        .map(b -> business(true, b))
        .orElse(new InterestResponses.Business(false, List.of(), true, List.of(), List.of()));
  }

  @PutMapping("/interests/business")
  InterestResponses.Business saveBusiness(
      @AuthenticationPrincipal LoginUser user, @RequestBody InterestRequests.Business body) {
    return business(
        true,
        interests.saveBusiness(
            user.userId(),
            new Business(
                body.regionCodes(),
                nationwide(body.includeNationwide()),
                body.industryCodes(),
                body.startupStages())));
  }

  @GetMapping("/interests/support")
  InterestResponses.Support support(@AuthenticationPrincipal LoginUser user) {
    return interests
        .support(user.userId())
        .map(s -> support(true, s))
        .orElse(new InterestResponses.Support(false, List.of(), true, List.of()));
  }

  @PutMapping("/interests/support")
  InterestResponses.Support saveSupport(
      @AuthenticationPrincipal LoginUser user, @RequestBody InterestRequests.Support body) {
    return support(
        true,
        interests.saveSupport(
            user.userId(),
            new Support(
                body.regionCodes(), nationwide(body.includeNationwide()), body.supportKinds())));
  }

  /** 그 종류의 새 소식 알림을 그만 받아요 (kind: housing · business · support) */
  @DeleteMapping("/interests/{kind}")
  ResponseEntity<Void> deleteInterest(
      @AuthenticationPrincipal LoginUser user, @PathVariable String kind) {
    interests.delete(user.userId(), kind);
    return ResponseEntity.noContent().build();
  }

  /** 저장한 적 없으면 기본값 (모두 켬, 조용한 시간 끔) */
  @GetMapping("/notification-settings")
  NotificationSettingsResponse notificationSettings(@AuthenticationPrincipal LoginUser user) {
    return response(settings.get(user.userId()));
  }

  @PutMapping("/notification-settings")
  NotificationSettingsResponse saveNotificationSettings(
      @AuthenticationPrincipal LoginUser user, @Valid @RequestBody NotificationSettingsRequest b) {
    return response(
        settings.save(
            user.userId(),
            new Settings(
                b.newNotice(),
                b.newSupport(),
                b.deadline(),
                b.correction(),
                b.followUp(),
                b.quietEnabled())));
  }

  private static boolean nationwide(Boolean value) {
    return value == null || value;
  }

  private static InterestResponses.Housing housing(boolean saved, Housing h) {
    return new InterestResponses.Housing(
        saved, h.regionCodes(), h.includeNationwide(), h.noticeTypes());
  }

  private static InterestResponses.Business business(boolean saved, Business b) {
    return new InterestResponses.Business(
        saved, b.regionCodes(), b.includeNationwide(), b.industryCodes(), b.startupStages());
  }

  private static InterestResponses.Support support(boolean saved, Support s) {
    return new InterestResponses.Support(
        saved, s.regionCodes(), s.includeNationwide(), s.supportKinds());
  }

  private static NotificationSettingsResponse response(Settings s) {
    return new NotificationSettingsResponse(
        s.newNotice(),
        s.newSupport(),
        s.deadline(),
        s.correction(),
        s.followUp(),
        s.quietEnabled());
  }
}
