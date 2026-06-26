package com.kingle.configserver.report

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// 개선/오류 리포트 1건. type=bug|enhancement, status=접수|처리중|완료.
@Entity
@Table(name = "report")
class Report(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(length = 20, nullable = false)
    var type: String = "bug",

    @Column(length = 300, nullable = false)
    var title: String = "",

    @Column(columnDefinition = "TEXT")
    var body: String = "",

    @Column(length = 100, nullable = false)
    var reporter: String = "",

    // 작성자(요청자) 로그인 ID — 답변 알림(아마란스) 수신자 식별용. reporter(사번)와 별개.
    @Column(name = "reporter_user_id", length = 100)
    var reporterUserId: String? = null,

    @Column(length = 20, nullable = false)
    var status: String = "접수",

    @Column(name = "app_version", length = 60)
    var appVersion: String? = null,

    @Column(name = "os_info", length = 160)
    var osInfo: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

// 리포트 댓글(스레드) 1건. role=reporter(요청자) | admin(관리자).
@Entity
@Table(name = "report_comment")
class ReportComment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "report_id", nullable = false)
    var reportId: Long = 0,

    @Column(length = 100, nullable = false)
    var author: String = "",

    @Column(length = 20, nullable = false)
    var role: String = "reporter",

    @Column(columnDefinition = "TEXT", nullable = false)
    var body: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
