package com.kingle.configserver.calendar

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * 캘린더 API 오류 응답 — 거부 사유(한글)를 message 로 내려준다.
 *
 * Boot 기본 오류 응답은 {timestamp,status,error,path} 뿐이라 앱이 "실패: " 로만 표시된다
 * (server.error.include-message=always 를 켜도 ResponseStatusException 의 reason 이 실려 오지 않는다).
 * 이 화면은 거부 사유("이 캘린더에 일정을 쓸 권한이 없습니다" 등)를 그대로 보여줘야 쓸 수 있으므로
 * 캘린더 컨트롤러에 한해 사유를 담은 본문을 직접 만든다.
 */
@RestControllerAdvice(assignableTypes = [CalendarController::class])
class CalendarErrorAdvice {
    @ExceptionHandler(ResponseStatusException::class)
    fun handle(e: ResponseStatusException): ResponseEntity<Map<String, Any?>> {
        val status = e.statusCode.value()
        val body = mapOf("status" to status, "error" to e.statusCode.toString(), "message" to (e.reason ?: ""))
        return ResponseEntity.status(status).body(body)
    }
}
