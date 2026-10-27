package kr.mujuthaek.api.traffic.controller;

import kr.mujuthaek.api.traffic.dto.BotTrafficResponse;
import kr.mujuthaek.api.traffic.dto.RealtimeResponse;
import kr.mujuthaek.api.traffic.dto.TrafficResponse;
import kr.mujuthaek.api.traffic.service.TrafficService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 「트래픽」 화면 (ADMIN만, SecurityConfig의 /api/admin/**).
 *
 * <pre>
 * GET /api/admin/traffic?days=7        개요·유입·이용 흐름(GA)과 서버 숫자(가입·로그인·사람/봇 요청). days 1~90
 * GET /api/admin/traffic/realtime       지금 접속자: GA 실시간 최근 30분·5분 활성 사용자, 기기별 (30초 다시 씀)
 * GET /api/admin/traffic/bots?days=7   봇·보안: 보낸 쪽별 요청, 요청 많은 곳(가린 IP), 봇이 많이 온 경로, 거절·오류, 관리자 접근, 로그인 실패. days 1~30
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/traffic")
class AdminTrafficController {

  private final TrafficService traffic;

  AdminTrafficController(TrafficService traffic) {
    this.traffic = traffic;
  }

  @GetMapping
  TrafficResponse traffic(@RequestParam(defaultValue = "7") int days) {
    return traffic.traffic(Math.clamp(days, 1, 90));
  }

  @GetMapping("/realtime")
  RealtimeResponse realtime() {
    return traffic.realtime();
  }

  @GetMapping("/bots")
  BotTrafficResponse bots(@RequestParam(defaultValue = "7") int days) {
    return traffic.bots(Math.clamp(days, 1, 30));
  }
}
