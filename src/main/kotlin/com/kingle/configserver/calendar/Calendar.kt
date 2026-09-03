package com.kingle.configserver.calendar

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

// 개인 캘린더 — 앱 [나의 캘린더] 화면이 쓰는 일정 저장소.
//  - UserCalendar : 캘린더 1개(소유자 1명). 사용자는 여러 개를 만들 수 있고 색으로 구분한다.
//  - CalendarEvent: 일정 1건. 캘린더에 속한다.
//  - CalendarShare: 캘린더 공유 1건(구글 캘린더의 "사용자와 공유" 와 같은 개념. VIEW/EDIT).
// 신원(userId=아마란스 로그인ID)은 앱이 NSM 로그인 정보로 넣어준다 — 마켓/개발진행과 동일한 사내 신뢰 모델.
@Entity
@Table(
    name = "calendar_cal",
    indexes = [
        Index(name = "idx_calendar_cal_owner", columnList = "owner_user_id"),
    ],
)
class UserCalendar(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(length = 100, nullable = false)
    var name: String = "내 캘린더",

    // 캘린더 기본 색(#RRGGBB) — 일정에 색이 없으면 이 색으로 그린다.
    @Column(length = 20, nullable = false)
    var color: String = "#3b82f6",

    @Column(name = "owner_user_id", length = 100, nullable = false)
    var ownerUserId: String = "",

    @Column(name = "owner_emp_no", length = 40)
    var ownerEmpNo: String? = null,

    @Column(name = "owner_nm", length = 100)
    var ownerNm: String? = null,

    // 로그인 직후 자동 생성되는 기본 캘린더 — 삭제 대상에서 제외한다(마지막 하나가 사라지면 일정을 못 넣는다).
    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

// 일정 1건.
// ★종일(allDay) 일정의 endAt 은 "마지막 날 00:00"(포함, inclusive)★ — 구글 API 의 end.date 는
//   배타적(exclusive)이라 앱이 구글과 주고받을 때 하루를 더하고 뺀다. 서버는 항상 inclusive 로 본다.
@Entity
@Table(
    name = "calendar_event",
    indexes = [
        Index(name = "idx_calendar_event_cal", columnList = "calendar_id"),
        Index(name = "idx_calendar_event_start", columnList = "start_at"),
        Index(name = "idx_calendar_event_gid", columnList = "google_event_id"),
    ],
)
class CalendarEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "calendar_id", nullable = false)
    var calendarId: Long = 0,

    @Column(length = 300, nullable = false)
    var title: String = "",

    @Column(length = 2000)
    var description: String? = null,

    @Column(length = 300)
    var location: String? = null,

    @Column(name = "all_day", nullable = false)
    var allDay: Boolean = false,

    @Column(name = "start_at", nullable = false)
    var startAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "end_at", nullable = false)
    var endAt: LocalDateTime = LocalDateTime.now(),

    // 일정별 색(#RRGGBB). 비면 캘린더 색을 따른다.
    @Column(length = 20)
    var color: String? = null,

    // ── 구글 캘린더 연동 ──
    // 구글에서 내려온(또는 앱이 구글에 만든) 일정이면 그 식별자를 남겨 다음 동기화 때 짝을 찾는다.
    @Column(name = "google_event_id", length = 200)
    var googleEventId: String? = null,

    @Column(name = "google_calendar_id", length = 200)
    var googleCalendarId: String? = null,

    // 구글이 준 updated(RFC3339) — 마지막으로 반영한 구글 버전. 이 값과 다르면 구글이 더 최신이다.
    @Column(name = "google_updated", length = 40)
    var googleUpdated: String? = null,

    @Column(name = "created_by", length = 100, nullable = false)
    var createdBy: String = "",

    @Column(name = "updated_by", length = 100)
    var updatedBy: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

// 캘린더 공유 1건 — granteeUserId(아마란스 로그인ID)에게 VIEW(보기) 또는 EDIT(편집) 권한을 준다.
@Entity
@Table(
    name = "calendar_share",
    indexes = [
        Index(name = "idx_calendar_share_cal", columnList = "calendar_id"),
        Index(name = "idx_calendar_share_grantee", columnList = "grantee_user_id"),
    ],
)
class CalendarShare(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "calendar_id", nullable = false)
    var calendarId: Long = 0,

    @Column(name = "grantee_user_id", length = 100, nullable = false)
    var granteeUserId: String = "",

    @Column(name = "grantee_emp_no", length = 40)
    var granteeEmpNo: String? = null,

    @Column(name = "grantee_nm", length = 100)
    var granteeNm: String? = null,

    @Column(length = 10, nullable = false)
    var role: String = "VIEW",

    @Column(name = "created_by", length = 100, nullable = false)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
