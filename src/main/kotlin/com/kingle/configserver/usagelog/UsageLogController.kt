package com.kingle.configserver.usagelog

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
    // 호출이 일어난 메뉴(앱이 보고 있던 사이드바 메뉴 라벨). 없으면 NULL — 나중에 /menu-labels 로 채울 수 있다.
    val menuLabel: String? = null,
)

// 라벨 채우기 — API 채널 → 메뉴 라벨. 이미 라벨이 있는 행은 건드리지 않는다.
data class MenuLabelItem(val api: String? = null, val label: String? = null)
data class FillMenuLabelsReq(val items: List<MenuLabelItem>? = null)
data class ResetMenuLabelsReq(val items: List<MenuLabelItem>? = null, val before: String? = null)

data class MenuUsageDto(
    val menu: String,
    val reporter: String,
    val sessions: Long,
    val calls: Long,
    val lastAt: String?,
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
    private val labels: UsageMenuLabelRepository,
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

    // 사용 순위 — (키, 사용자)별 건수. by=menu: 메뉴 열기 기준 / by=api: API 호출 기준.
    // days<=0 이면 전체 기간. 집계·정렬은 앱이 한다(사용자별로 펼쳐 보기 위해 키×사용자로 내려준다).
    // 라벨이 비어 있는 API 채널 — 앱이 이 목록을 메뉴로 매핑해 /menu-labels 로 보낸다.
    @GetMapping("/unlabeled-apis")
    fun unlabeledApis(): List<String> = labels.unlabeledApis()

    // 기존 로그 메뉴 라벨 채우기(멱등 — NULL 인 행만). menu:open 행은 menu 값을 그대로 라벨로.
    @PostMapping("/menu-labels")
    fun fillMenuLabels(@RequestBody req: FillMenuLabelsReq): Map<String, Any?> {
        var updated = labels.fillMenuOpenLabels()
        for (item in req.items.orEmpty()) {
            val api = item.api?.trim().orEmpty()
            val label = item.label?.trim()?.take(100).orEmpty()
            if (api.isNotEmpty() && label.isNotEmpty()) updated += labels.fillLabel(api, label)
        }
        return mapOf("updated" to updated)
    }

    // 잘못 채운 라벨 되돌리기(관리용) — before(ISO, UTC) 이전 행 중 api·label 이 일치하는 것만 NULL 로.
    @PostMapping("/menu-labels/reset")
    fun resetMenuLabels(@RequestBody req: ResetMenuLabelsReq): Map<String, Any?> {
        val before = req.before?.trim()?.let { parseLocalDateTime(it) } ?: return mapOf("updated" to 0, "error" to "before 필요")
        var updated = 0
        for (item in req.items.orEmpty()) {
            val api = item.api?.trim().orEmpty()
            val label = item.label?.trim().orEmpty()
            if (api.isNotEmpty() && label.isNotEmpty()) updated += labels.resetLabel(api, label, before)
        }
        return mapOf("updated" to updated)
    }

    // 메뉴 사용 순위 — (메뉴, 사용자)별 사용 횟수(시간대)·호출 수. days<=0 이면 전체 기간.
    @GetMapping("/menu-rank")
    fun menuRank(@RequestParam(defaultValue = "30") days: Long): List<MenuUsageDto> {
        val since = if (days > 0) LocalDateTime.now().minusDays(days) else LocalDateTime.of(2000, 1, 1, 0, 0)
        return labels.rankByMenu(since).map {
            MenuUsageDto(it.menuLabel.orEmpty(), it.reporter.orEmpty(), it.sessions.toLong(), it.calls.toLong(), it.lastAt?.toString())
        }
    }

    @GetMapping("/rank")
    fun rank(
        @RequestParam(defaultValue = "menu") by: String,
        @RequestParam(defaultValue = "30") days: Long,
    ): List<UsageRankDto> {
        val since = if (days > 0) LocalDateTime.now().minusDays(days) else LocalDateTime.of(2000, 1, 1, 0, 0)
        val rows = if (by == "api") repo.rankApiCalls(since) else repo.rankMenuOpens(since)
        return rows.map { UsageRankDto(it.rankKey.orEmpty(), it.reporter.orEmpty(), it.cnt.toLong(), it.lastAt?.toString()) }
    }
}

data class UsageRankDto(
    val key: String,
    val reporter: String,
    val count: Long,
    val lastAt: String?,
)

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
        menuLabel = menuLabel?.trim()?.take(100)?.ifEmpty { null },
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
