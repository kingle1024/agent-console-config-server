package com.kingle.configserver.devprogress

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

// 개발진행(NSM 건별 착수~완료) 1건 — 앱 [개발완료처리] 화면의 [개발진행] 버튼이 만든다.
//  - status=IN_PROGRESS  : 일일보고 "1. 진행중인 업무"에 자동으로 들어간다.
//  - status=DONE         : 개발완료처리 시점에 completedAt/elapsedMinutes 를 채우고 진행중에서 빠진다.
//  - status=CANCELED     : 잘못 누른 진행 취소(착수 기록 무효).
// devNo/fileNm 등은 착수 시점의 NSM 스냅샷(표시용) — 원본은 NSM DB 가 진실이다.
// ★소요시간(elapsedMinutes)은 startedAt~completedAt 벽시계 분. NSM 건별 처리시간 추적용으로 남긴다.★
@Entity
@Table(
    name = "dev_progress",
    indexes = [
        Index(name = "idx_dev_progress_dev", columnList = "dev_no"),
        Index(name = "idx_dev_progress_user", columnList = "user_id"),
        Index(name = "idx_dev_progress_status", columnList = "status"),
    ],
)
class DevProgress(
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

    // 일일보고 진행중 줄에 찍을 한 줄 요약(앱이 요청내용/업데이트내역에서 뽑아 넣는다).
    @Column(name = "summary", length = 300)
    var summary: String? = null,

    // 진행 주체 — 로그인ID 가 신원 키, 사번은 NSM 개발담당자(DEV_EMP_CD) 대조용.
    @Column(name = "emp_no", length = 40, nullable = false)
    var empNo: String = "",

    @Column(name = "user_id", length = 100, nullable = false)
    var userId: String = "",

    @Column(name = "emp_nm", length = 100)
    var empNm: String? = null,

    @Column(length = 20, nullable = false)
    var status: String = "IN_PROGRESS",

    @Column(name = "started_at", nullable = false)
    var startedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,

    // 착수~완료 소요시간(분). 완료 시에만 채워진다(재개→재완료 시 startedAt 기준으로 다시 계산).
    @Column(name = "elapsed_minutes")
    var elapsedMinutes: Long? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
