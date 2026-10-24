package kr.mujuthaek.api.user.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 화면 연결 설정 (application.yml의 app.*).
 *
 * @param frontendUrl 로그인 뒤 돌아갈 프론트 주소. CORS로 허용하는 주소이기도 해요
 */
@ConfigurationProperties("app")
record LoginProperties(String frontendUrl) {}
