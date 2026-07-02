package com.kingle.configserver.firewall

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

// 방화벽 결재 상신 이력 1건 — 앱의 [결재 상신] 성공 시 자동 기록된다.
// 그룹웨어 결재 문서는 상신자 본인만 볼 수 있으므로, "어느 고객사가 언제 신청됐는지"를
// 모두가 확인할 수 있게 여기 공유 DB 에 남긴다. 조회는 전 사용자, 수정/삭제는 앱에서 관리자만 노출.
@Entity
@Table(name = "firewall_approval", indexes = [Index(name = "idx_fwapproval_created", columnList = "created_at")])
class FirewallApproval(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 고객사명(예: DEV2) — 목록에서 검색하는 기본 키워드
    @Column(length = 200, nullable = false)
    var customer: String = "",

    // 신청 도착지 IP(콤마 구분 여러 개 가능)
    @Column(length = 500, nullable = false)
    var ip: String = "",

    // 신청 PORT(콤마 구분)
    @Column(length = 300, nullable = false)
    var port: String = "",

    // 적용기간(yyyy-MM-dd)
    @Column(name = "start_date", length = 20)
    var startDate: String? = null,

    @Column(name = "end_date", length = 20)
    var endDate: String? = null,

    // 그룹웨어 결재 문서번호(doc_id) — 응답에 없으면 null
    @Column(name = "doc_id", length = 100)
    var docId: String? = null,

    // 상신자(기안자) 이름/로그인 ID
    @Column(name = "drafter_name", length = 100)
    var drafterName: String? = null,

    @Column(name = "drafter_login_id", length = 100)
    var drafterLoginId: String? = null,

    // 관리자 메모(수정 시 비고)
    @Column(length = 500)
    var note: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
