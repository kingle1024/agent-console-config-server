package com.kingle.configserver.weeklyauto

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataAccessException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime

interface WeeklyAutoRegRepository : JpaRepository<WeeklyAutoReg, Long> {
    fun findByUserIdAndHost(userId: String, host: String): List<WeeklyAutoReg>
    fun findAllByOrderByEnabledDescUpdatedAtDesc(): List<WeeklyAutoReg>
}

data class WeeklyAutoReportReq(
    val userId: String? = null,
    val host: String? = null,
    val empNo: String? = null,
    val empNm: String? = null,
    val wikiName: String? = null,
    val enabled: Boolean? = null,
    val weekday: Int? = null,
    val time: String? = null,
    val criteria: String? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
    val lastAttemptAt: String? = null,
    val lastDoneDate: String? = null,
    val appVersion: String? = null,
)

data class WeeklyAutoRegDto(
    val userId: String,
    val host: String,
    val empNo: String?,
    val empNm: String?,
    val wikiName: String?,
    val enabled: Boolean,
    val weekday: Int,
    val time: String,
    val criteria: String?,
    val lastResult: String?,
    val lastError: String?,
    val lastAttemptAt: String?,
    val lastDoneDate: String?,
    val appVersion: String?,
    val updatedAt: String,
    val ageMin: Long, // 마지막 보고로부터 지난 분(서버 시계)
)

// 금주처리현황 위키 자동 등록 사용 현황 — 앱이 자기 설정/결과를 보고(upsert)하고, 관리자 앱이 목록을 본다.
// X-Api-Key(ReportApiSecurity)로만 보호 — 목록 노출 제한(ejy10241 만)은 앱 쪽 슈퍼관리자 판정이 맡는다.
@RestController
@RequestMapping("/api/weeklyauto")
class WeeklyAutoRegController(
    private val repo: WeeklyAutoRegRepository,
    private val jdbc: JdbcTemplate,
) {
    // 옛 테이블(로그인ID 1행) → 새 테이블(로그인ID+PC 1행) 최초 1회 복사. 옛 행은 host 빈값으로 들어가고,
    // 그 사용자가 새 앱으로 보고하면 report() 가 빈값 행을 지운다. 옛 테이블이 없는(새 DB) 경우는 건너뛴다.
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun migrateLegacyRows() {
        if (repo.count() > 0) return
        try {
            val copied = jdbc.update(
                "INSERT INTO weekly_auto_pc (user_id, host, emp_no, emp_nm, wiki_name, enabled, weekday, check_time, criteria," +
                    " last_result, last_error, last_attempt_at, last_done_date, app_version, created_at, updated_at)" +
                    " SELECT user_id, '', emp_no, emp_nm, wiki_name, enabled, weekday, check_time, criteria," +
                    " last_result, last_error, last_attempt_at, last_done_date, app_version, created_at, updated_at FROM weekly_auto_reg",
            )
            log.info("weekly_auto_reg → weekly_auto_pc {}행 복사", copied)
        } catch (e: DataAccessException) {
            log.info("weekly_auto_reg 복사 건너뜀: {}", e.mostSpecificCause.message)
        }
    }

    // 보고 — 로그인ID+PC 당 1행 upsert. 값이 안 온 칸은 기존 값 유지(결과만 보고하는 호출도 있다).
    @PostMapping
    @Transactional
    fun report(@RequestBody req: WeeklyAutoReportReq): Map<String, Any?> {
        val userId = req.userId?.trim().orEmpty()
        if (userId.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required")
        val host = req.host?.trim().orEmpty().take(100)
        val now = LocalDateTime.now()
        // 경합으로 중복 행이 생겼어도 첫 행만 갱신하고 나머지는 지워 키를 하나로 되돌린다.
        val rows = repo.findByUserIdAndHost(userId, host)
        val row = rows.firstOrNull() ?: WeeklyAutoReg(userId = userId.take(100), host = host, createdAt = now)
        if (rows.size > 1) repo.deleteAll(rows.drop(1))
        // PC 를 밝힌 보고가 오면 옛 앱(또는 복사분)의 PC 미상 행은 같은 사람의 중복이라 지운다.
        if (host.isNotEmpty()) repo.deleteAll(repo.findByUserIdAndHost(userId, ""))
        row.empNo = trimOr(req.empNo, 40) ?: row.empNo
        row.empNm = trimOr(req.empNm, 100) ?: row.empNm
        row.wikiName = trimOr(req.wikiName, 100) ?: row.wikiName
        row.enabled = req.enabled ?: row.enabled
        row.weekday = req.weekday?.takeIf { it in 0..6 } ?: row.weekday
        row.time = trimOr(req.time, 5) ?: row.time
        row.criteria = trimOr(req.criteria, 10) ?: row.criteria
        if (req.lastResult != null) {
            row.lastResult = trimOr(req.lastResult, 20)
            row.lastError = trimOr(req.lastError, 500)
        }
        row.lastAttemptAt = trimOr(req.lastAttemptAt, 20) ?: row.lastAttemptAt
        row.lastDoneDate = trimOr(req.lastDoneDate, 10) ?: row.lastDoneDate
        row.appVersion = trimOr(req.appVersion, 60) ?: row.appVersion
        row.updatedAt = now
        repo.save(row)
        return mapOf("ok" to true, "userId" to userId)
    }

    // 목록 — 사용 중인 사람 먼저, 그 안에서 최근 보고 순.
    @GetMapping
    fun list(): List<WeeklyAutoRegDto> {
        val now = LocalDateTime.now()
        return repo.findAllByOrderByEnabledDescUpdatedAtDesc().map { it.toDto(now) }
    }

    private fun trimOr(value: String?, max: Int): String? = value?.trim()?.take(max)?.ifEmpty { null }
}

private val log = LoggerFactory.getLogger(WeeklyAutoRegController::class.java)

private fun WeeklyAutoReg.toDto(now: LocalDateTime) = WeeklyAutoRegDto(
    userId = userId,
    host = host,
    empNo = empNo,
    empNm = empNm,
    wikiName = wikiName,
    enabled = enabled,
    weekday = weekday,
    time = time,
    criteria = criteria,
    lastResult = lastResult,
    lastError = lastError,
    lastAttemptAt = lastAttemptAt,
    lastDoneDate = lastDoneDate,
    appVersion = appVersion,
    updatedAt = updatedAt.toString(),
    ageMin = Duration.between(updatedAt, now).toMinutes().coerceAtLeast(0),
)
