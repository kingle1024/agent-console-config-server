package com.kingle.configserver.custalias

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

// 고객사 접속정보 프리필 — 요청내용(INQ_CNTN)의 "라벨 : 값" 줄에서 라벨을 위키 칸으로 보내는 학습 사전 1건.
//
// 요청 작성자마다 라벨 표기가 달라서(Host / DB ip / 호스트 …) 정규식을 코드에 계속 추가할 수 없다.
// 그래서 앱이 못 맞춘 라벨을 사용자가 한 번 지정하면 여기에 쌓이고, 다음부터 모든 사용자에게 자동 적용된다.
//
//  - labelNorm : 정규화된 라벨(공백/불릿/괄호/기호 제거 + 소문자). 매칭 키.
//  - section   : 라벨 위에 있던 문맥 헤더("db"/"vpn"/"web"/""). 같은 "계정"을 DB/VPN 중 어디로 보낼지 가른다.
//  - field     : 위키 칸 키("DB/IP" 처럼 "섹션/항목"). 빈 문자열이면 "무시"(프리필 후보에서 제외) 학습.
//  - part      : ID/PW 처럼 두 줄이 한 칸을 이루는 경우 "id"/"pw". 비우면 값 전체.
@Entity
@Table(
    name = "cust_alias",
    indexes = [
        Index(name = "idx_cust_alias_key", columnList = "section,label_norm"),
    ],
)
class CustAlias(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "section", length = 20, nullable = false)
    var section: String = "",

    @Column(name = "label_norm", length = 120, nullable = false)
    var labelNorm: String = "",

    // 학습 당시 원문 라벨(사람이 목록에서 알아보기 위한 표시용).
    @Column(name = "label_raw", length = 200)
    var labelRaw: String? = null,

    @Column(name = "field", length = 120, nullable = false)
    var field: String = "",

    @Column(name = "part", length = 10, nullable = false)
    var part: String = "",

    @Column(name = "created_by", length = 100)
    var createdBy: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
