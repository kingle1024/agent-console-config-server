package com.kingle.configserver.errorlog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// 앱에서 발생한 에러 1건. 앱(IPC 핸들러)이 실패하면 자동 수집된다. 모든 사용자가 조회 가능.
//  message=에러메시지 / request=요청(IPC 채널 등) / url=관련 URL / reason=에러 이유(스택 등)
@Entity
@Table(name = "error_log")
class ErrorLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(length = 1000, nullable = false)
    var message: String = "",

    @Column(length = 300)
    var request: String? = null,

    @Column(length = 700)
    var url: String? = null,

    @Column(columnDefinition = "TEXT")
    var reason: String? = null,

    // 에러를 만난 사용자(로그인 ID/사번) — 식별용. 없으면 unknown.
    @Column(length = 100)
    var reporter: String? = null,

    @Column(name = "app_version", length = 60)
    var appVersion: String? = null,

    @Column(name = "os_info", length = 160)
    var osInfo: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
