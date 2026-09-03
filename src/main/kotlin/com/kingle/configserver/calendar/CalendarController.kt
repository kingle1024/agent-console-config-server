package com.kingle.configserver.calendar

import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime

// ── DTO ──

data class ShareDto(
    val id: Long,
    val calendarId: Long,
    val granteeUserId: String,
    val granteeEmpNo: String?,
    val granteeNm: String?,
    val role: String,
    val createdAt: String,
)

// mine=본인 소유, role=이 사용자의 권한(OWNER/EDIT/VIEW). shares 는 소유자에게만 채워준다.
data class CalendarDto(
    val id: Long,
    val name: String,
    val color: String,
    val ownerUserId: String,
    val ownerNm: String?,
    val isDefault: Boolean,
    val mine: Boolean,
    val role: String,
    val shares: List<ShareDto>,
)

data class EventDto(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val description: String?,
    val location: String?,
    val allDay: Boolean,
    val start: String,
    val end: String,
    val color: String?,
    val googleEventId: String?,
    val googleCalendarId: String?,
    val googleUpdated: String?,
    val createdBy: String,
    val updatedBy: String?,
    val updatedAt: String,
    // 이 일정을 이 사용자가 고칠 수 있는지 — 앱이 편집 UI 를 열지 말지 판단한다.
    val canEdit: Boolean,
)

data class CalendarReq(
    val userId: String? = null,
    val empNo: String? = null,
    val empNm: String? = null,
    val name: String? = null,
    val color: String? = null,
)

data class ShareReq(
    val userId: String? = null,
    val granteeUserId: String? = null,
    val granteeEmpNo: String? = null,
    val granteeNm: String? = null,
    val role: String? = null,
)

data class EventReq(
    val userId: String? = null,
    val calendarId: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val allDay: Boolean? = null,
    val start: String? = null,
    val end: String? = null,
    val color: String? = null,
    val googleEventId: String? = null,
    val googleCalendarId: String? = null,
    val googleUpdated: String? = null,
)

data class UserIdReq(val userId: String? = null)

/**
 * 개인 캘린더 API — 앱 [나의 캘린더] 화면의 백엔드.
 *
 *  - 캘린더 목록 : GET  /api/calendar/calendars?user=id      (내 것 + 공유받은 것, 기본 캘린더 자동 생성)
 *  - 캘린더 생성 : POST /api/calendar/calendars
 *  - 캘린더 수정 : POST /api/calendar/calendars/{id}/update  (소유자만)
 *  - 캘린더 삭제 : POST /api/calendar/calendars/{id}/delete  (소유자만, 기본 캘린더 제외)
 *  - 공유 추가   : POST /api/calendar/calendars/{id}/shares  (소유자만)
 *  - 공유 해제   : POST /api/calendar/shares/{id}/delete     (소유자 또는 본인)
 *  - 일정 목록   : GET  /api/calendar/events?user=id&from=YYYY-MM-DD&to=YYYY-MM-DD
 *  - 일정 생성   : POST /api/calendar/events                 (소유자/EDIT 공유자)
 *  - 일정 수정   : POST /api/calendar/events/{id}/update
 *  - 일정 삭제   : POST /api/calendar/events/{id}/delete
 *
 * X-Api-Key(ReportApiSecurity)로만 보호 — 신원(userId)은 앱이 NSM 로그인 정보로 넣어준다(마켓과 동일 신뢰 모델).
 */
@RestController
@RequestMapping("/api/calendar")
class CalendarController(
    private val calendars: UserCalendarRepository,
    private val events: CalendarEventRepository,
    private val shares: CalendarShareRepository,
) {
    // ── 캘린더 ──

    @GetMapping("/calendars")
    @Transactional
    fun listCalendars(@RequestParam(required = false) user: String?): List<CalendarDto> {
        val userId = requireUser(user)
        val owned = ensureDefault(userId)
        val sharedRows = shares.findByGranteeUserId(userId)
        val sharedCals =
            if (sharedRows.isEmpty()) emptyList() else calendars.findByIdIn(sharedRows.map { it.calendarId }.toSet())
        val roleByCal = sharedRows.associate { it.calendarId to it.role }
        val shareRows = if (owned.isEmpty()) emptyList() else shares.findByCalendarIdIn(owned.mapNotNull { it.id })
        val sharesByCal = shareRows.groupBy { it.calendarId }
        val mine = owned.map { cal -> cal.toDto(true, "OWNER", (sharesByCal[cal.id] ?: emptyList()).map { it.toDto() }) }
        val others =
            sharedCals
                .filter { it.ownerUserId != userId }
                .map { cal -> cal.toDto(false, roleByCal[cal.id] ?: "VIEW", emptyList()) }
        return mine + others.sortedBy { it.ownerUserId + it.name }
    }

    @PostMapping("/calendars")
    @Transactional
    fun createCalendar(@RequestBody req: CalendarReq): CalendarDto {
        val userId = requireUser(req.userId)
        val name = req.name?.trim().orEmpty()
        if (name.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "캘린더 이름이 없습니다")
        }
        val now = LocalDateTime.now()
        val row =
            UserCalendar(
                name = name,
                color = normalizeColor(req.color) ?: "#3b82f6",
                ownerUserId = userId,
                ownerEmpNo = req.empNo?.trim()?.ifBlank { null },
                ownerNm = req.empNm?.trim()?.ifBlank { null },
                isDefault = calendars.findByOwnerUserIdOrderByIdAsc(userId).isEmpty(),
                createdAt = now,
                updatedAt = now,
            )
        return calendars.save(row).toDto(true, "OWNER", emptyList())
    }

    @PostMapping("/calendars/{id}/update")
    @Transactional
    fun updateCalendar(@PathVariable id: Long, @RequestBody req: CalendarReq): CalendarDto {
        val userId = requireUser(req.userId)
        val cal = ownedCalendar(id, userId)
        req.name?.trim()?.ifBlank { null }?.let { cal.name = it }
        normalizeColor(req.color)?.let { cal.color = it }
        cal.updatedAt = LocalDateTime.now()
        val saved = calendars.save(cal)
        return saved.toDto(true, "OWNER", shares.findByCalendarId(id).map { it.toDto() })
    }

    // 캘린더 삭제 — 그 안의 일정·공유도 함께 지운다. 기본 캘린더는 지울 수 없다.
    @PostMapping("/calendars/{id}/delete")
    @Transactional
    fun deleteCalendar(@PathVariable id: Long, @RequestBody req: UserIdReq): Map<String, Any?> {
        val userId = requireUser(req.userId)
        val cal = ownedCalendar(id, userId)
        if (cal.isDefault) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "기본 캘린더는 삭제할 수 없습니다")
        }
        val removed = events.findByCalendarId(id).size
        events.deleteByCalendarId(id)
        shares.deleteByCalendarId(id)
        calendars.delete(cal)
        return mapOf("ok" to true, "deletedEvents" to removed)
    }

    // ── 공유 ──

    @PostMapping("/calendars/{id}/shares")
    @Transactional
    fun addShare(@PathVariable id: Long, @RequestBody req: ShareReq): ShareDto {
        val userId = requireUser(req.userId)
        ownedCalendar(id, userId)
        val grantee = req.granteeUserId?.trim().orEmpty()
        if (grantee.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "공유받을 사람의 로그인ID가 없습니다")
        }
        if (grantee.equals(userId, ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "본인에게는 공유할 수 없습니다")
        }
        val role = if (req.role?.trim()?.uppercase() == "EDIT") "EDIT" else "VIEW"
        // 이미 공유돼 있으면 권한만 바꾼다(중복 행 방지).
        val existing = shares.findByCalendarIdAndGranteeUserId(id, grantee).firstOrNull()
        val row = existing ?: CalendarShare(calendarId = id, granteeUserId = grantee, createdBy = userId)
        row.role = role
        row.granteeEmpNo = req.granteeEmpNo?.trim()?.ifBlank { null } ?: row.granteeEmpNo
        row.granteeNm = req.granteeNm?.trim()?.ifBlank { null } ?: row.granteeNm
        return shares.save(row).toDto()
    }

    // 공유 해제 — 캘린더 소유자, 또는 공유받은 본인(내 목록에서 빼기)만.
    @PostMapping("/shares/{id}/delete")
    @Transactional
    fun deleteShare(@PathVariable id: Long, @RequestBody req: UserIdReq): Map<String, Any?> {
        val userId = requireUser(req.userId)
        val row = shares.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "공유를 찾을 수 없습니다") }
        val cal = calendars.findById(row.calendarId).orElse(null)
        val isOwner = cal != null && cal.ownerUserId.equals(userId, ignoreCase = true)
        if (!isOwner && !row.granteeUserId.equals(userId, ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "공유를 해제할 권한이 없습니다")
        }
        shares.delete(row)
        return mapOf("ok" to true)
    }

    // ── 일정 ──

    // from~to(YYYY-MM-DD, 둘 다 포함)와 겹치는 일정을 접근 가능한 캘린더 전체에서 모아 준다.
    @GetMapping("/events")
    @Transactional
    fun listEvents(
        @RequestParam(required = false) user: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): List<EventDto> {
        val userId = requireUser(user)
        val access = accessible(userId)
        if (access.isEmpty()) {
            return emptyList()
        }
        val fromAt = parseDate(from)?.atStartOfDay() ?: LocalDate.now().withDayOfMonth(1).minusMonths(1).atStartOfDay()
        val toAt = (parseDate(to) ?: fromAt.toLocalDate().plusMonths(2)).atTime(23, 59, 59)
        val rows = events.findByCalendarIdInAndStartAtLessThanEqualAndEndAtGreaterThanEqual(access.keys, toAt, fromAt)
        return rows
            .sortedWith(compareBy({ !it.allDay }, { it.startAt }))
            .map { it.toDto(canWrite(access[it.calendarId])) }
    }

    @PostMapping("/events")
    @Transactional
    fun createEvent(@RequestBody req: EventReq): EventDto {
        val userId = requireUser(req.userId)
        val calendarId = req.calendarId ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "캘린더가 지정되지 않았습니다")
        requireWrite(calendarId, userId)
        val now = LocalDateTime.now()
        val row = CalendarEvent(calendarId = calendarId, createdBy = userId, createdAt = now, updatedAt = now)
        applyEvent(row, req, userId, now)
        return events.save(row).toDto(true)
    }

    @PostMapping("/events/{id}/update")
    @Transactional
    fun updateEvent(@PathVariable id: Long, @RequestBody req: EventReq): EventDto {
        val userId = requireUser(req.userId)
        val row = events.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다") }
        requireWrite(row.calendarId, userId)
        // 캘린더 이동(다른 캘린더로 옮기기)도 허용 — 대상 캘린더에도 쓰기 권한이 있어야 한다.
        req.calendarId?.takeIf { it != row.calendarId }?.let {
            requireWrite(it, userId)
            row.calendarId = it
        }
        applyEvent(row, req, userId, LocalDateTime.now())
        return events.save(row).toDto(true)
    }

    @PostMapping("/events/{id}/delete")
    @Transactional
    fun deleteEvent(@PathVariable id: Long, @RequestBody req: UserIdReq): Map<String, Any?> {
        val userId = requireUser(req.userId)
        val row = events.findById(id).orElse(null) ?: return mapOf("ok" to true, "deleted" to 0)
        requireWrite(row.calendarId, userId)
        events.delete(row)
        return mapOf("ok" to true, "deleted" to 1)
    }

    // ── 내부 ──

    private fun requireUser(user: String?): String {
        val u = user?.trim().orEmpty()
        if (u.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "사용자(userId)가 없습니다 — NSM 로그인 필요")
        }
        return u
    }

    // 소유 캘린더가 하나도 없으면 기본 캘린더를 만들어 준다(첫 진입에 바로 일정을 넣을 수 있게).
    private fun ensureDefault(userId: String): List<UserCalendar> {
        val owned = calendars.findByOwnerUserIdOrderByIdAsc(userId)
        if (owned.isNotEmpty()) {
            return owned
        }
        val now = LocalDateTime.now()
        val created = calendars.save(UserCalendar(ownerUserId = userId, isDefault = true, createdAt = now, updatedAt = now))
        return listOf(created)
    }

    private fun ownedCalendar(id: Long, userId: String): UserCalendar {
        val cal = calendars.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "캘린더를 찾을 수 없습니다") }
        if (!cal.ownerUserId.equals(userId, ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "캘린더 소유자만 할 수 있습니다")
        }
        return cal
    }

    // 이 사용자가 볼 수 있는 캘린더 → 권한(OWNER/EDIT/VIEW) 맵.
    private fun accessible(userId: String): Map<Long, String> {
        val result = LinkedHashMap<Long, String>()
        calendars.findByOwnerUserIdOrderByIdAsc(userId).forEach { cal -> cal.id?.let { result[it] = "OWNER" } }
        shares.findByGranteeUserId(userId).forEach { row ->
            if (!result.containsKey(row.calendarId)) {
                result[row.calendarId] = row.role
            }
        }
        return result
    }

    private fun canWrite(role: String?): Boolean = role == "OWNER" || role == "EDIT"

    private fun requireWrite(calendarId: Long, userId: String) {
        if (!canWrite(accessible(userId)[calendarId])) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "이 캘린더에 일정을 쓸 권한이 없습니다")
        }
    }

    private fun applyEvent(row: CalendarEvent, req: EventReq, userId: String, now: LocalDateTime) {
        req.title?.trim()?.let { row.title = it }
        if (row.title.isBlank()) {
            row.title = "(제목 없음)"
        }
        req.description?.let { row.description = it.trim().ifBlank { null } }
        req.location?.let { row.location = it.trim().ifBlank { null } }
        req.allDay?.let { row.allDay = it }
        parseDateTime(req.start)?.let { row.startAt = it }
        parseDateTime(req.end)?.let { row.endAt = it }
        if (row.endAt.isBefore(row.startAt)) {
            row.endAt = row.startAt
        }
        req.color?.let { row.color = normalizeColor(it) }
        req.googleEventId?.let { row.googleEventId = it.trim().ifBlank { null } }
        req.googleCalendarId?.let { row.googleCalendarId = it.trim().ifBlank { null } }
        req.googleUpdated?.let { row.googleUpdated = it.trim().ifBlank { null } }
        row.updatedBy = userId
        row.updatedAt = now
    }

    private fun parseDate(s: String?): LocalDate? {
        val v = s?.trim().orEmpty()
        if (v.isEmpty()) {
            return null
        }
        return runCatching { LocalDate.parse(v.take(10)) }.getOrNull()
    }

    // "2026-09-03T10:00", "2026-09-03T10:00:00", "2026-09-03" 을 모두 받는다.
    private fun parseDateTime(s: String?): LocalDateTime? {
        val v = s?.trim().orEmpty()
        if (v.isEmpty()) {
            return null
        }
        val parsed = runCatching { LocalDateTime.parse(v) }.getOrNull()
        if (parsed != null) {
            return parsed
        }
        return parseDate(v)?.atStartOfDay()
    }

    // #RRGGBB 만 허용 — 그 외(빈 값 포함)는 null 로 두고 캘린더 색을 따른다.
    private fun normalizeColor(c: String?): String? {
        val v = c?.trim().orEmpty()
        return if (Regex("^#[0-9a-fA-F]{6}$").matches(v)) v.lowercase() else null
    }
}

private fun UserCalendar.toDto(mine: Boolean, role: String, shareDtos: List<ShareDto>) = CalendarDto(
    id = id!!,
    name = name,
    color = color,
    ownerUserId = ownerUserId,
    ownerNm = ownerNm,
    isDefault = isDefault,
    mine = mine,
    role = role,
    shares = shareDtos,
)

private fun CalendarShare.toDto() = ShareDto(
    id = id!!,
    calendarId = calendarId,
    granteeUserId = granteeUserId,
    granteeEmpNo = granteeEmpNo,
    granteeNm = granteeNm,
    role = role,
    createdAt = createdAt.toString(),
)

private fun CalendarEvent.toDto(canEdit: Boolean) = EventDto(
    id = id!!,
    calendarId = calendarId,
    title = title,
    description = description,
    location = location,
    allDay = allDay,
    start = startAt.toString(),
    end = endAt.toString(),
    color = color,
    googleEventId = googleEventId,
    googleCalendarId = googleCalendarId,
    googleUpdated = googleUpdated,
    createdBy = createdBy,
    updatedBy = updatedBy,
    updatedAt = updatedAt.toString(),
    canEdit = canEdit,
)
