package com.kingle.configserver

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

// Config Server 보안: 헬스체크만 공개, 나머지(설정 조회·/encrypt 등)는 Basic 인증 필요.
// 사용자/비밀번호는 application.yml 의 spring.security.user.* (환경변수 CONFIG_USER/CONFIG_PASSWORD).
@Configuration
class SecurityConfig {
	@Bean
	fun filterChain(http: HttpSecurity): SecurityFilterChain {
		http
			// 설정 조회는 상태 없는 API 호출이라 CSRF 비활성화
			.csrf { it.disable() }
			.authorizeHttpRequests {
				it.requestMatchers("/actuator/health", "/actuator/info").permitAll()
				// 리포트·방화벽신청·에러로그·쪽지 API 는 X-Api-Key 필터(ReportApiSecurity)로 별도 보호 → Basic 인증 제외
				it.requestMatchers("/api/reports/**", "/api/firewalls/**", "/api/errorlogs/**", "/api/memo/**").permitAll()
				it.anyRequest().authenticated()
			}
			.httpBasic { }
			.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
		return http.build()
	}
}
