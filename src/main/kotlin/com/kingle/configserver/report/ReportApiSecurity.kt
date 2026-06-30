package com.kingle.configserver.report

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 앱 REST API 전용 인증 — 리포트(/api/reports)·방화벽신청(/api/firewalls)·에러로그(/api/errorlogs) 경로 요청의
 * X-Api-Key 헤더가 reports.api-key(환경변수 REPORTS_API_KEY)와 같아야 통과.
 * 키가 비어있으면(로컬·미설정) 검증을 건너뛴다.
 * SecurityConfig 에서 해당 경로들은 permitAll 이라 Config Server Basic 인증과 분리된다.
 */
@Configuration
class ReportApiSecurity(
    @Value("\${reports.api-key:}") private val apiKey: String,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(
                request: HttpServletRequest,
                response: HttpServletResponse,
                handler: Any,
            ): Boolean {
                if (apiKey.isNotBlank() && request.getHeader("X-Api-Key") != apiKey) {
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.contentType = "application/json;charset=UTF-8"
                    response.writer.write("{\"error\":\"invalid api key\"}")
                    return false
                }
                return true
            }
        }).addPathPatterns("/api/reports/**", "/api/firewalls/**", "/api/errorlogs/**", "/api/memo/**", "/api/bakery/**")
    }
}
