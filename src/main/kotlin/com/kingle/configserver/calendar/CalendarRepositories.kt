package com.kingle.configserver.calendar

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface UserCalendarRepository : JpaRepository<UserCalendar, Long> {
    fun findByOwnerUserIdOrderByIdAsc(ownerUserId: String): List<UserCalendar>
    fun findByIdIn(ids: Collection<Long>): List<UserCalendar>
}

interface CalendarEventRepository : JpaRepository<CalendarEvent, Long> {
    // 구간이 겹치는 일정 — (start <= to) AND (end >= from). 종일 일정도 같은 규칙으로 걸린다.
    fun findByCalendarIdInAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
        calendarIds: Collection<Long>,
        to: LocalDateTime,
        from: LocalDateTime,
    ): List<CalendarEvent>

    fun findByCalendarId(calendarId: Long): List<CalendarEvent>
    fun findByCalendarIdAndGoogleEventId(calendarId: Long, googleEventId: String): List<CalendarEvent>
    fun deleteByCalendarId(calendarId: Long)
}

interface CalendarShareRepository : JpaRepository<CalendarShare, Long> {
    fun findByCalendarId(calendarId: Long): List<CalendarShare>
    fun findByCalendarIdIn(calendarIds: Collection<Long>): List<CalendarShare>
    fun findByGranteeUserId(granteeUserId: String): List<CalendarShare>
    fun findByCalendarIdAndGranteeUserId(calendarId: Long, granteeUserId: String): List<CalendarShare>
    fun deleteByCalendarId(calendarId: Long)
}
