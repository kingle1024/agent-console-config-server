package com.kingle.configserver.firewall

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

// 방화벽 신청 1건. IP/PORT + 첨부파일(고객사동의서 필수, 서버리스트 선택)은 별도 테이블(FirewallFile).
// status=접수|처리중|완료. 파일 실체는 Cloudflare R2 에 있고, 여기엔 objectKey(경로)만 보관한다.
@Entity
@Table(name = "firewall_request")
class FirewallRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 고객사 이름(결재 문서 제목 접두 [<고객사>]). 신청 시 필수 입력. 기존 데이터는 null.
    @Column(length = 200)
    var customer: String? = null,

    // 신청 IP(여러 개면 콤마/줄바꿈 포함 문자열 그대로)
    @Column(length = 300, nullable = false)
    var ip: String = "",

    // 신청 PORT(예: "80, 443, 1022")
    @Column(length = 300, nullable = false)
    var port: String = "",

    // 신청자가 남긴 비고(자유 입력, 선택). 관리자·결재 참고용. 기존 데이터는 null.
    @Column(length = 1000)
    var note: String? = null,

    // 신청 구성원 이름 목록(콤마 구분, 예: "김인영,엄지용"). null/빈 값 = 부서원 전체(기본).
    @Column(length = 2000)
    var members: String? = null,

    // 도착지 IP별 시스템 구분(AP/DB 등). IP 순서대로 '|' 구분(예: "AP | DB"). 기존 데이터는 null.
    @Column(name = "sys_names", length = 500)
    var sysNames: String? = null,

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

// 고객사별 방화벽 종료 기간 1건(customer 당 1행 upsert). 관리자가 신청을 '완료'로 바꿀 때 자동 기록되고,
// [고객사 정보 검색] 상세에서 모든 사용자가 조회/수기 수정한다.
@Entity
@Table(name = "firewall_customer_end", uniqueConstraints = [UniqueConstraint(columnNames = ["customer"])])
class FirewallCustomerEnd(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(length = 200, nullable = false)
    var customer: String = "",

    // 방화벽 정책 종료일 "YYYY-MM-DD"
    @Column(name = "end_date", length = 20, nullable = false)
    var endDate: String = "",

    // 마지막 수정자(로그인 ID 또는 이름) — 수기 수정 추적용
    @Column(name = "updated_by", length = 100)
    var updatedBy: String? = null,

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
