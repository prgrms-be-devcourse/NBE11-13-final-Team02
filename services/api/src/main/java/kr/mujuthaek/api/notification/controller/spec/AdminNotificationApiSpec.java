package kr.mujuthaek.api.notification.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.mujuthaek.api.notification.dto.response.AdminNotificationStatsResponse;

@Tag(
    name = "알림 발송 통계",
    description = "관리자 「알림」 화면의 발송 결과와 봤는지·눌렀는지 지표예요. 권한 TRAFFIC이 있는 관리자만 부를 수 있어요.")
public interface AdminNotificationApiSpec {

  @Operation(
      summary = "최근 며칠 알림 통계",
      description =
          "최근 days일의 알림 서버 발송 결과(delivery)와 알림함의 종류별 지표(engagement)를 줘요. 회원별 값은 없고 종류별 합계만 있어요. "
              + "알림 서버에 닿지 못하면 delivery만 null이에요.")
  @ApiResponse(responseCode = "200", description = "알림 통계")
  @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED — days가 1~90 밖이에요")
  AdminNotificationStatsResponse stats(
      @Parameter(description = "최근 며칠 (기본 7, 1~90). 알림함이 90일만 남아서 90일까지예요", example = "7")
          int days);
}
