package com.kingle.configserver.usagelog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

// 앱 사용 로그 1건 — 사용자가 메뉴를 쓸 때마다(=IPC/API 호출) 자동 수집된다. 전 사용자 대상 수집, 조회는 관리자만.
//  menu=사용한 메뉴/화면(있으면) / api=호출한 API·IPC 채널명 / reporter=사용자(로그인 ID/사번)
//  ok=성공 여부 / durationMs=처리 시간(ms). ★페이로드(파라미터/비밀번호/토큰)는 절대 기록하지 않는다★
@Entity
@Table(
    name = "usage_log",
    indexes = [
        Index(name = "idx_usage_created", columnList = "created_at"),
        Index(name = "idx_usage_reporter", columnList = "reporter"),
    ],
)
class UsageLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 호출한 API/IPC 채널명 (예: api:call, report:list). 페이로드는 포함하지 않는다.
    @Column(length = 200, nullable = false)
    var api: String = "",

    // 사용한 메뉴/화면 라벨(앱이 알려주면). 없으면 null.
    @Column(length = 200)
    var menu: String? = null,

    // 사용자(로그인 ID/사번) — 식별용. 없으면 unknown.
    @Column(length = 100)
    var reporter: String? = null,

    // 호출 성공 여부(핸들러가 throw/ok:false 면 false).
    @Column(nullable = false)
    var ok: Boolean = true,

    // 처리 시간(ms). 측정 불가면 null.
    @Column(name = "duration_ms")
    var durationMs: Long? = null,

    @Column(name = "app_version", length = 60)
    var appVersion: String? = null,

    @Column(name = "os_info", length = 160)
    var osInfo: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
