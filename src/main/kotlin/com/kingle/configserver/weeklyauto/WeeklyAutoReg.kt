package com.kingle.configserver.weeklyauto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

// 금주처리현황 위키 자동 등록 사용 현황 1행 = 앱 사용자 1명(로그인ID 당 1행 upsert).
// 자동 등록은 각자 PC 의 로컬 설정(opt-in)이라 서버는 누가 켜뒀는지 알 수 없다.
// 그래서 앱이 설정 저장·앱 시작·자동 등록 시도 때마다 자기 상태를 보고하고, 관리자(ejy10241)가 목록으로 본다.
// 담기는 값은 로그인ID·이름·설정값·마지막 결과뿐 — 위키 계정/비밀번호는 담지 않는다.
@Entity
@Table(name = "weekly_auto_reg", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id"])])
class WeeklyAutoReg(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", length = 100, nullable = false)
    var userId: String = "",

    @Column(name = "emp_no", length = 40)
    var empNo: String? = null,

    @Column(name = "emp_nm", length = 100)
    var empNm: String? = null,

    // 위키 개인 페이지 이름(위키 계정 displayName). 인사 이름과 다를 수 있어 따로 둔다.
    @Column(name = "wiki_name", length = 100)
    var wikiName: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = false,

    // 자동 등록 요일(0=일 .. 6=토) / 확인 시작 시각 "HH:mm".
    @Column(nullable = false)
    var weekday: Int = 4,

    @Column(name = "check_time", length = 5, nullable = false)
    var time: String = "09:00",

    // 본문 생성 기준(nsm / daily).
    @Column(length = 10)
    var criteria: String? = null,

    // 마지막 자동 등록 결과(registered / exists / skip / fail)와 진단용 메시지·시각.
    @Column(name = "last_result", length = 20)
    var lastResult: String? = null,

    @Column(name = "last_error", length = 500)
    var lastError: String? = null,

    @Column(name = "last_attempt_at", length = 20)
    var lastAttemptAt: String? = null,

    @Column(name = "last_done_date", length = 10)
    var lastDoneDate: String? = null,

    @Column(name = "app_version", length = 60)
    var appVersion: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    // 마지막 보고 시각 — 오래된 행은 앱을 안 켜는 사람(자동 등록도 안 도는 사람)이다.
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
