package com.kingle.configserver.network

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// IP/PORT 방화벽 신청서 생성용 팀 구성원 1명 — 이름 + 유선IP/무선IP/VPN 계정.
// FI개발도우미의 [신청>IP/PORT 신청서] 화면에서 편집(그리드)하고, 신청서 HTML 표를 만들 때 사용한다.
// (구 바탕화면 gpt.html 의 peopleData 를 DB 로 옮긴 것. 78alswo·ejy10241 두 명이 공유 편집.)
@Entity
@Table(name = "network_member")
class NetworkMember(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 구성원 이름(예: 엄지용)
    @Column(length = 100, nullable = false)
    var name: String = "",

    // 유선 IP(예: 10.105.1.53). 없으면 빈 문자열.
    @Column(name = "wired_ip", length = 60)
    var wiredIp: String? = null,

    // 무선 IP(예: 10.106.14.160). 없으면 빈 문자열.
    @Column(name = "wireless_ip", length = 60)
    var wirelessIp: String? = null,

    // VPN 계정 ID(예: ejy10241). 없으면 빈 문자열.
    @Column(name = "vpn_id", length = 100)
    var vpnId: String? = null,

    // 신청서 포함 여부(false 면 신청서 표/결재에서 제외). null/미지정(기존 데이터)은 포함(true)으로 본다.
    @Column(name = "included")
    var included: Boolean? = null,

    // 화면 표시 순서(작을수록 위). 그리드 순서 보존용.
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

// 신청 대상 서버 1대 — 도착지 IP + 시스템명 + PORT 목록.
// (구 gpt.html 의 ipData. 신청서 표에서 각 서버마다 유선/VPN/무선 3행을 만든다.)
@Entity
@Table(name = "network_server")
class NetworkServer(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 도착지 IP(예: 172.16.112.75)
    @Column(length = 60, nullable = false)
    var ip: String = "",

    // 시스템명/서버명(예: DEV AP2)
    @Column(length = 200, nullable = false)
    var name: String = "",

    // PORT 목록(콤마 구분 원본, 예: "80,443,1022"). 표시할 때 앱이 줄바꿈으로 편다.
    @Column(length = 300, nullable = false)
    var port: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
