package com.kingle.configserver.market

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

// NSM 마켓 판매 물건 1건 — 개발완료처리 목록의 NSM(개발건)을 다른 개발자에게 넘기기 위해 올린 것.
// devNo/reqnNo 등은 앱이 판매 시점에 넣어준 NSM 스냅샷(표시용) — 원본은 NSM DB 가 진실이다.
// status: ON_SALE(판매중) / SOLD(판매완료) / CANCELED(판매취소)
@Entity
@Table(
    name = "market_item",
    indexes = [
        Index(name = "idx_market_item_dev", columnList = "dev_no"),
        Index(name = "idx_market_item_seller", columnList = "seller_user_id"),
        Index(name = "idx_market_item_status", columnList = "status"),
    ],
)
class MarketItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "dev_no", length = 40, nullable = false)
    var devNo: String = "",

    @Column(name = "reqn_no", length = 40)
    var reqnNo: String? = null,

    @Column(name = "file_cd", length = 80)
    var fileCd: String? = null,

    @Column(name = "file_nm", length = 300)
    var fileNm: String? = null,

    @Column(name = "module_nm", length = 100)
    var moduleNm: String? = null,

    @Column(name = "partner_nm", length = 200)
    var partnerNm: String? = null,

    // 판매자(현재 개발담당자) — 로그인ID 가 신원 키, 사번은 개발담당자 변경(DEV_EMP_CD)용.
    @Column(name = "seller_emp_no", length = 40, nullable = false)
    var sellerEmpNo: String = "",

    @Column(name = "seller_user_id", length = 100, nullable = false)
    var sellerUserId: String = "",

    @Column(name = "seller_nm", length = 100)
    var sellerNm: String? = null,

    @Column(length = 20, nullable = false)
    var status: String = "ON_SALE",

    // 판매완료(SOLD) 시 확정된 구매자
    @Column(name = "buyer_emp_no", length = 40)
    var buyerEmpNo: String? = null,

    @Column(name = "buyer_user_id", length = 100)
    var buyerUserId: String? = null,

    @Column(name = "buyer_nm", length = 100)
    var buyerNm: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

// 구매요청 1건 — 판매 물건(market_item)에 대한 구매 의사. 선착순 표시를 위해 createdAt 오름차순이 순번.
// status: REQUESTED(요청중) / APPROVED(승인=구매완료) / REJECTED(거절·다른 사람 승인) / CANCELED(요청취소)
@Entity
@Table(
    name = "market_purchase_request",
    indexes = [
        Index(name = "idx_market_req_item", columnList = "item_id"),
        Index(name = "idx_market_req_buyer", columnList = "buyer_user_id"),
    ],
)
class MarketPurchaseRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "item_id", nullable = false)
    var itemId: Long = 0,

    // 구매자 — 승인 시 이 사번/로그인ID 로 NSM 개발담당자(DEV_EMP_CD/DEV_USER_ID)가 교체된다.
    @Column(name = "buyer_emp_no", length = 40, nullable = false)
    var buyerEmpNo: String = "",

    @Column(name = "buyer_user_id", length = 100, nullable = false)
    var buyerUserId: String = "",

    @Column(name = "buyer_nm", length = 100)
    var buyerNm: String? = null,

    @Column(length = 20, nullable = false)
    var status: String = "REQUESTED",

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
