package com.kingle.configserver.errorlog

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

// 요청/응답 DTO
data class CreateErrorLogReq(
    val message: String? = null,
    val request: String? = null,
    val url: String? = null,
    val reason: String? = null,
    val reporter: String? = null,
    val appVersion: String? = null,
    val osInfo: String? = null,
)

data class ErrorLogDto(
    val id: Long,
    val message: String,
    val request: String?,
    val url: String?,
    val reason: String?,
    val reporter: String?,
    val appVersion: String?,
    val osInfo: String?,
    val createdAt: String,
)

// 에러로그 — 앱이 실패 시 자동 수집(POST), 누구나 조회(GET). X-Api-Key 로만 보호(SecurityConfig permitAll).
@RestController
@RequestMapping("/api/errorlogs")
class ErrorLogController(
    private val repo: ErrorLogRepository,
) {
    @PostMapping
    fun create(@RequestBody req: CreateErrorLogReq): Map<String, Any?> {
        val message = req.message?.trim().orEmpty().take(1000)
        if (message.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "message required")
        val e = ErrorLog(
            message = message,
            request = req.request?.trim()?.take(300)?.ifEmpty { null },
            url = req.url?.trim()?.take(700)?.ifEmpty { null },
            reason = req.reason?.trim()?.ifEmpty { null },
            reporter = req.reporter?.trim()?.take(100)?.ifEmpty { null },
            appVersion = req.appVersion?.trim()?.take(60),
            osInfo = req.osInfo?.trim()?.take(160),
        )
        repo.save(e)
        return mapOf("id" to e.id)
    }

    @GetMapping
    fun list(): List<ErrorLogDto> = repo.findTop500ByOrderByCreatedAtDesc().map { it.toDto() }
}

private fun ErrorLog.toDto() = ErrorLogDto(
    id = id!!,
    message = message,
    request = request,
    url = url,
    reason = reason,
    reporter = reporter,
    appVersion = appVersion,
    osInfo = osInfo,
    createdAt = createdAt.toString(),
)
