package com.kingle.configserver.firewall

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

// 방화벽 만료 안내 1건 — 앱이 네이버 메일 "인프라 방화벽 오픈 신청 만료(5일전) 안내" 를 읽어 기록한다.
// 같은 정책(서비스명+도착지+종료일)은 여러 PC(ejy10241·78alswo)에서 잡혀도 1건만 남고,
// 처음 기록한 PC 만 쪽지를 보낸다(created=true 응답). [연장하기] 상신 결과도 여기에 남겨 공유한다.
@Entity
@Table(
    name = "firewall_expiry",
    uniqueConstraints = [UniqueConstraint(name = "uk_fwexpiry_policy", columnNames = ["service_name", "dest_ip", "end_date"])],
)
class FirewallExpiry(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 서비스명 = 원 결재 문서번호(예: 더존비즈온-FI개발1Cell-25-00484)
    @Column(name = "service_name", length = 200, nullable = false)
    var serviceName: String = "",

    @Column(name = "src_ip", length = 200)
    var srcIp: String? = null,

    @Column(name = "dest_ip", length = 500, nullable = false)
    var destIp: String = "",

    @Column(length = 300)
    var port: String? = null,

    @Column(name = "start_date", length = 20)
    var startDate: String? = null,

    @Column(name = "end_date", length = 20, nullable = false)
    var endDate: String = "",

    // 원 결재 문서 제목/ID(아마란스 수신참조문서함 조회 결과, 못 찾으면 null)
    @Column(name = "doc_title", length = 500)
    var docTitle: String? = null,

    @Column(name = "doc_id", length = 50)
    var docId: String? = null,

    @Column(name = "mail_date", length = 40)
    var mailDate: String? = null,

    @Column(name = "detected_by", length = 100)
    var detectedBy: String? = null,

    // 연장 상신 결과 — 상신 전이면 null
    @Column(name = "extend_doc_id", length = 50)
    var extendDocId: String? = null,

    @Column(name = "extend_start_date", length = 20)
    var extendStartDate: String? = null,

    @Column(name = "extend_end_date", length = 20)
    var extendEndDate: String? = null,

    @Column(name = "extended_by", length = 100)
    var extendedBy: String? = null,

    @Column(name = "extended_at")
    var extendedAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
