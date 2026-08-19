package com.kingle.configserver.devprogress

import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime

// ── DTO ──

data class DevProgressDto(
    val id: Long,
    val devNo: String,
    val reqnNo: String?,
    val fileCd: String?,
    val fileNm: String?,
    val moduleNm: String?,
    val partnerNm: String?,
    val summary: String?,
    val empNo: String,
    val userId: String,
    val empNm: String?,
    val status: String,
    val startedAt: String,
    val completedAt: String?,
    val elapsedMinutes: Long?,
    // 진행중이면 지금까지, 완료면 완료까지 걸린 분 — ★서버 시계로 계산★해 앱이 타임존 없이 바로 표시한다.
    val runningMinutes: Long,
)

data class StartReq(
    val devNo: String? = null,
    val reqnNo: String? = null,
    val fileCd: String? = null,
    val fileNm: String? = null,
    val moduleNm: String? = null,
    val partnerNm: String? = null,
    val summary: String? = null,
    val empNo: String? = null,
    val userId: String? = null,
    val empNm: String? = null,
)

// 완료/취소/재개 공통 — 대상은 (devNo, userId) 의 진행건.
data class DevNoReq(
    val devNo: String? = null,
    val userId: String? = null,
)

// 개발진행 추적 — [개발완료처리] 화면의 [개발진행] 버튼이 착수를 기록하고, 개발완료처리가 완료시각/소요시간을 채운다.
// 진행중(IN_PROGRESS) 건은 앱이 일일보고 "1. 진행중인 업무"에 자동으로 넣는다.
// X-Api-Key(ReportApiSecurity)로만 보호 — 신원(userId/empNo)은 앱이 NSM 로그인 정보로 넣어준다(마켓과 동일 신뢰 모델).
@RestController
@RequestMapping("/api/devprogress")
class DevProgressController(
    private val repo: DevProgressRepository,
) {
    // 목록 — user 로 본인 것만, status 로 IN_PROGRESS/DONE/CANCELED 필터. dev 를 주면 그 개발건 이력 전체(처리시간 추적용).
    @GetMapping
    fun list(
        @RequestParam(required = false) user: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) dev: String?,
    ): List<DevProgressDto> {
        val u = user?.trim().orEmpty()
        val st = status?.trim()?.uppercase().orEmpty()
        val d = dev?.trim().orEmpty()
        val rows =
            when {
                d.isNotEmpty() -> repo.findByDevNoOrderByStartedAtDesc(d)
                u.isNotEmpty() && st.isNotEmpty() -> repo.findByUserIdAndStatusOrderByStartedAtDesc(u, st)
                u.isNotEmpty() -> repo.findByUserIdOrderByStartedAtDesc(u)
                st.isNotEmpty() -> repo.findByStatusOrderByStartedAtDesc(st)
                else -> repo.findAll().sortedByDescending { it.startedAt }
            }
        return rows.filter { st.isEmpty() || it.status == st }.map { it.toDto() }
    }

    // 개발진행(착수) — 이미 진행중이면 스냅샷만 갱신하고 그 건을 그대로 돌려준다(멱등: 착수시각 보존).
    @PostMapping("/start")
    @Transactional
    fun start(@RequestBody req: StartReq): DevProgressDto {
        val devNo = req.devNo?.trim().orEmpty()
        val userId = req.userId?.trim().orEmpty()
        val empNo = req.empNo?.trim().orEmpty()
        if (devNo.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "개발번호(devNo)가 없습니다")
        }
        if (userId.isEmpty() || empNo.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "진행자 정보(userId/empNo)가 없습니다 — NSM 로그인 필요")
        }
        val now = LocalDateTime.now()
        val existing = repo.findByDevNoAndUserIdAndStatus(devNo, userId, "IN_PROGRESS").firstOrNull()
        val row = existing ?: DevProgress(devNo = devNo, userId = userId, empNo = empNo, startedAt = now, createdAt = now)
        row.reqnNo = req.reqnNo?.trim()?.ifBlank { null } ?: row.reqnNo
        row.fileCd = req.fileCd?.trim()?.ifBlank { null } ?: row.fileCd
        row.fileNm = req.fileNm?.trim()?.ifBlank { null } ?: row.fileNm
        row.moduleNm = req.moduleNm?.trim()?.ifBlank { null } ?: row.moduleNm
        row.partnerNm = req.partnerNm?.trim()?.ifBlank { null } ?: row.partnerNm
        row.summary = req.summary?.trim()?.ifBlank { null } ?: row.summary
        row.empNm = req.empNm?.trim()?.ifBlank { null } ?: row.empNm
        row.empNo = empNo
        row.status = "IN_PROGRESS"
        row.updatedAt = now
        return repo.save(row).toDto()
    }

    // 개발완료 — 진행중 건에 완료시각 + 소요시간(분)을 채운다. 진행 기록이 없으면 completed=0 (에러 아님).
    // ★일일보고 진행중 업무에서 빠지는 지점이 여기다.★
    @PostMapping("/complete")
    @Transactional
    fun complete(@RequestBody req: DevNoReq): Map<String, Any?> {
        val rows = targetRows(req)
        val now = LocalDateTime.now()
        val done =
            rows.map { row ->
                row.status = "DONE"
                row.completedAt = now
                row.elapsedMinutes = Duration.between(row.startedAt, now).toMinutes()
                row.updatedAt = now
                repo.save(row).toDto()
            }
        return mapOf("completed" to done.size, "items" to done)
    }

    // 개발진행 취소 — 잘못 누른 착수 기록 무효화(소요시간 집계 대상에서 제외).
    @PostMapping("/cancel")
    @Transactional
    fun cancel(@RequestBody req: DevNoReq): Map<String, Any?> {
        val now = LocalDateTime.now()
        val canceled =
            targetRows(req).map { row ->
                row.status = "CANCELED"
                row.updatedAt = now
                repo.save(row).toDto()
            }
        return mapOf("canceled" to canceled.size, "items" to canceled)
    }

    // 재개 — 개발완료 취소(완료 해제) 시 다시 진행중으로. 착수시각은 원래 값을 유지한다(누적 소요시간 기준).
    @PostMapping("/reopen")
    @Transactional
    fun reopen(@RequestBody req: DevNoReq): Map<String, Any?> {
        val devNo = req.devNo?.trim().orEmpty()
        val userId = req.userId?.trim().orEmpty()
        if (devNo.isEmpty() || userId.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "devNo/userId 가 없습니다")
        }
        val now = LocalDateTime.now()
        val reopened =
            repo.findByDevNoAndUserIdAndStatus(devNo, userId, "DONE").map { row ->
                row.status = "IN_PROGRESS"
                row.completedAt = null
                row.elapsedMinutes = null
                row.updatedAt = now
                repo.save(row).toDto()
            }
        return mapOf("reopened" to reopened.size, "items" to reopened)
    }

    // 완료/취소 대상 = (devNo, userId) 의 진행중 건. userId 를 안 주면 그 개발건의 진행중 건 전부(담당 이관 등).
    private fun targetRows(req: DevNoReq): List<DevProgress> {
        val devNo = req.devNo?.trim().orEmpty()
        if (devNo.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "개발번호(devNo)가 없습니다")
        }
        val userId = req.userId?.trim().orEmpty()
        if (userId.isNotEmpty()) {
            return repo.findByDevNoAndUserIdAndStatus(devNo, userId, "IN_PROGRESS")
        }
        return repo.findByDevNoOrderByStartedAtDesc(devNo).filter { it.status == "IN_PROGRESS" }
    }
}

private fun DevProgress.toDto() = DevProgressDto(
    id = id!!,
    devNo = devNo,
    reqnNo = reqnNo,
    fileCd = fileCd,
    fileNm = fileNm,
    moduleNm = moduleNm,
    partnerNm = partnerNm,
    summary = summary,
    empNo = empNo,
    userId = userId,
    empNm = empNm,
    status = status,
    startedAt = startedAt.toString(),
    completedAt = completedAt?.toString(),
    elapsedMinutes = elapsedMinutes,
    runningMinutes = Duration.between(startedAt, completedAt ?: LocalDateTime.now()).toMinutes(),
)
