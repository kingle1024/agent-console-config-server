package com.kingle.configserver.customer

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

// 고객사 접속정보 1건 — 위키 "ERP10 업체 접속" 패널 하나(브라우저/VPN/DB 접속정보, 비밀번호 포함).
//
// 예전엔 각 PC userData/customers.json 에 자격증명이 평문으로 남았다. 이제 서버가 원본을 들고,
// 이름 목록만 누구나 받고 내용(fieldsJson)은 아마란스 비밀번호를 확인한 세션에 1건씩만 내려준다(CustomerController).
//  - name       : 위키 업체명 원본(예 "그립컴퍼니(20311)"). 앱의 정규화 매칭은 클라이언트가 한다.
//  - fieldsJson : {"섹션/항목": 값} JSON 을 AES-256-GCM 으로 암호화한 값("v1:..." — CustomerCipher).
@Entity
@Table(
    name = "customer_info",
    indexes = [Index(name = "uk_customer_info_name", columnList = "name", unique = true)],
)
class CustomerInfo(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "name", length = 200, nullable = false)
    var name: String = "",

    @Column(name = "fields_json", columnDefinition = "MEDIUMTEXT", nullable = false)
    var fieldsJson: String = "{}",

    @Column(name = "updated_by", length = 100)
    var updatedBy: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

// 누가 언제 접속정보를 열었는지/바꿨는지 — 자격증명을 서버로 모은 대신 열람 흔적을 남긴다.
@Entity
@Table(
    name = "customer_access_log",
    indexes = [Index(name = "idx_customer_access_log_at", columnList = "created_at")],
)
class CustomerAccessLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", length = 100, nullable = false)
    var userId: String = "",

    // unlock_ok / unlock_fail / unlock_blocked / unlock_denied / view / sync
    @Column(name = "action", length = 30, nullable = false)
    var action: String = "",

    // view 일 때 열어본 고객사명
    @Column(name = "customer_name", length = 200)
    var customerName: String? = null,

    @Column(name = "detail", length = 500)
    var detail: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)

interface CustomerInfoRepository : JpaRepository<CustomerInfo, Long> {
    fun findAllByOrderByNameAsc(): List<CustomerInfo>
    fun findByName(name: String): CustomerInfo?
}

interface CustomerAccessLogRepository : JpaRepository<CustomerAccessLog, Long> {
    fun findTop300ByOrderByIdDesc(): List<CustomerAccessLog>
    fun findTop300ByCustomerNameOrderByIdDesc(customerName: String): List<CustomerAccessLog>
}

// 고객사 정보 화면 전체 설정 1건(키-값) — 지금은 "lock_enabled"(아마란스 비밀번호 열람 잠금 ON/OFF) 하나.
// 행이 없으면 기본값(잠금 ON)이다. 앱 [설정]에서 슈퍼관리자가 바꾸고, 모든 사용자에게 바로 적용된다.
@Entity
@Table(name = "customer_setting")
class CustomerSetting(
    @Id
    @Column(name = "setting_key", length = 60)
    var settingKey: String = "",

    @Column(name = "setting_value", length = 200, nullable = false)
    var settingValue: String = "",

    @Column(name = "updated_by", length = 100)
    var updatedBy: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

interface CustomerSettingRepository : JpaRepository<CustomerSetting, String>
