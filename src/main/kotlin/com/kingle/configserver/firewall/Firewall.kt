package com.kingle.configserver.firewall

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// 방화벽 신청 1건. IP/PORT + 첨부파일(고객사동의서 필수, 서버리스트 선택)은 별도 테이블(FirewallFile).
// status=접수|처리중|완료. 파일 실체는 Cloudflare R2 에 있고, 여기엔 objectKey(경로)만 보관한다.
@Entity
@Table(name = "firewall_request")
class FirewallRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 신청 IP(여러 개면 콤마/줄바꿈 포함 문자열 그대로)
    @Column(length = 300, nullable = false)
    var ip: String = "",

    // 신청 PORT(예: "80, 443, 1022")
    @Column(length = 300, nullable = false)
    var port: String = "",

    // 신청자(작업대상 개발사원 사번 — 목록 필터/표시용). report.reporter 와 동일 개념.
    @Column(length = 100, nullable = false)
    var reporter: String = "",

    // 신청자 로그인 ID — 알림(아마란스) 수신자 식별용. reporter(사번)와 별개.
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

// 방화벽 신청 첨부파일 1건. kind=consent(고객사동의서) | serverlist(서버리스트).
// 파일 바이트는 R2 에 있고, objectKey 로 공개 URL(앱의 r2.publicUrl)을 구성해 열람한다.
@Entity
@Table(name = "firewall_file")
class FirewallFile(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "request_id", nullable = false)
    var requestId: Long = 0,

    // consent(고객사동의서, 필수) | serverlist(서버리스트, 선택)
    @Column(length = 20, nullable = false)
    var kind: String = "consent",

    // 사용자에게 보여줄 원본 파일명
    @Column(length = 300, nullable = false)
    var filename: String = "",

    // R2 객체 키(예: firewall/<폴더>/consent-xxx.pdf)
    @Column(name = "object_key", length = 500, nullable = false)
    var objectKey: String = "",

    @Column(name = "content_type", length = 120)
    var contentType: String? = null,

    @Column(name = "size_bytes")
    var sizeBytes: Long? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
