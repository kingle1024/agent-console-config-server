package com.kingle.configserver.usagelog

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

// 요청/응답 DTO
data class UsageLogItem(
    val api: String? = null,
    val menu: String? = null,
    val reporter: String? = null,
    val ok: Boolean? = null,
    val durationMs: Long? = null,
    val appVersion: String? = null,
    val osInfo: String? = null,
    // 앱이 측정한 발생 시각(ISO-8601). 배치 전송 지연으로 서버 수신 시각과 어긋날 수 있어 앱 시각을 우선.
    val at: String? = null,
)

// 배치 수집 — 앱이 여러 건을 모아 한 번에 보낸다(무료 플랜 부하/콜드스타트 완화).
data class CreateUsageLogsReq(
    val items: List<UsageLogItem>? = null,
)

data class UsageLogDto(
    val id: Long,
    val api: String,
    val menu: String?,
    val reporter: String?,
    val ok: Boolean,
    val durationMs: Long?,
    val appVersion: String?,
    val osInfo: String?,
    val createdAt: String,
)

// 사용 로그 — 앱이 메뉴/API 사용 시 자동 수집(POST, 배치), 관리자 화면에서 조회(GET). X-Api-Key 로만 보호.
@RestController
@RequestMapping("/api/usagelogs")
class UsageLogController(
    private val repo: UsageLogRepository,
) {
    // 배치 수집. 빈 요청/빈 목록이면 조용히 0건 저장(에러 아님 — fire-and-forget 클라이언트를 막지 않는다).
    @PostMapping
    fun create(@RequestBody req: CreateUsageLogsReq): Map<String, Any?> {
        val items = req.items.orEmpty()
        val entities = items.mapNotNull { it.toEntity() }
        if (entities.isNotEmpty()) repo.saveAll(entities)
        return mapOf("saved" to entities.size)
    }

    @GetMapping
    fun list(): List<UsageLogDto> = repo.findTop1000ByOrderByCreatedAtDesc().map { it.toDto() }
}

// api 가 비면 저장하지 않는다(잘못된 항목 스킵). 각 필드는 컬럼 길이에 맞춰 자른다.
private fun UsageLogItem.toEntity(): UsageLog? {
    val a = api?.trim().orEmpty().take(200)
    if (a.isEmpty()) return null
    val parsedAt = at?.trim()?.ifEmpty { null }?.let { parseLocalDateTime(it) }
    return UsageLog(
        api = a,
        menu = menu?.trim()?.take(200)?.ifEmpty { null },
        reporter = reporter?.trim()?.take(100)?.ifEmpty { null },
        ok = ok ?: true,
        durationMs = durationMs,
        appVersion = appVersion?.trim()?.take(60),
        osInfo = osInfo?.trim()?.take(160),
        createdAt = parsedAt ?: LocalDateTime.now(),
    )
}

// ISO-8601(오프셋/Z 유무 모두) → LocalDateTime(UTC 기준). 파싱 실패면 null → 서버 수신 시각 사용.
private fun parseLocalDateTime(s: String): LocalDateTime? =
    try {
        java.time.OffsetDateTime.parse(s).toInstant()
            .atZone(java.time.ZoneOffset.UTC).toLocalDateTime()
    } catch (_: Exception) {
        try {
            java.time.Instant.parse(s).atZone(java.time.ZoneOffset.UTC).toLocalDateTime()
        } catch (_: Exception) {
            try {
                LocalDateTime.parse(s)
            } catch (_: Exception) {
                null
            }
        }
    }

private fun UsageLog.toDto() = UsageLogDto(
    id = id!!,
    api = api,
    menu = menu,
    reporter = reporter,
    ok = ok,
    durationMs = durationMs,
    appVersion = appVersion,
    osInfo = osInfo,
    createdAt = createdAt.toString(),
)
