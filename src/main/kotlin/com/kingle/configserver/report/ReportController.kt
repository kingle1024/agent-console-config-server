package com.kingle.configserver.report

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

// 요청/응답 DTO
data class CreateReq(
    val type: String? = null,
    val title: String? = null,
    val body: String? = null,
    val reporter: String? = null,
    val appVersion: String? = null,
    val osInfo: String? = null,
)
data class CommentReq(val author: String? = null, val role: String? = null, val body: String? = null)
data class StatusReq(val status: String? = null)

data class SummaryDto(
    val id: Long,
    val type: String,
    val title: String,
    val reporter: String,
    val status: String,
    val comments: Long,
    val createdAt: String,
    val updatedAt: String,
)
data class CommentDto(val author: String, val role: String, val body: String, val createdAt: String)
data class DetailDto(
    val id: Long,
    val type: String,
    val title: String,
    val body: String,
    val reporter: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val thread: List<CommentDto>,
)

private val STATUSES = listOf("접수", "처리중", "완료")

@RestController
@RequestMapping("/api/reports")
class ReportController(
    private val reports: ReportRepository,
    private val comments: ReportCommentRepository,
) {
    // 작성 → Issue 생성
    @PostMapping
    fun create(@RequestBody req: CreateReq): Map<String, Any?> {
        val title = req.title?.trim().orEmpty()
        if (title.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "title required")
        val r = Report(
            type = if (req.type == "enhancement") "enhancement" else "bug",
            title = title,
            body = req.body?.trim().orEmpty(),
            reporter = req.reporter?.trim().orEmpty().ifEmpty { "unknown" },
            appVersion = req.appVersion?.trim(),
            osInfo = req.osInfo?.trim(),
        )
        reports.save(r)
        return mapOf("id" to r.id)
    }

    // 목록 — reporter 지정 시 본인 것만(요청자), 없으면 전체(관리자)
    @GetMapping
    fun list(@RequestParam(required = false) reporter: String?): List<SummaryDto> {
        val list = if (reporter.isNullOrBlank()) {
            reports.findAllByOrderByUpdatedAtDesc()
        } else {
            reports.findByReporterOrderByUpdatedAtDesc(reporter)
        }
        return list.map { it.toSummary(comments.countByReportId(it.id!!)) }
    }

    // 상세 + 댓글 스레드
    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): DetailDto {
        val r = reports.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        val cs = comments.findByReportIdOrderByCreatedAtAsc(id)
        return r.toDetail(cs)
    }

    // 답글(요청자/관리자 공통). role 로 작성자 구분.
    @PostMapping("/{id}/comments")
    fun addComment(@PathVariable id: Long, @RequestBody req: CommentReq): Map<String, Any?> {
        val r = reports.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        val text = req.body?.trim().orEmpty()
        if (text.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "body required")
        comments.save(
            ReportComment(
                reportId = id,
                author = req.author?.trim().orEmpty().ifEmpty { "unknown" },
                role = if (req.role == "admin") "admin" else "reporter",
                body = text,
            )
        )
        r.updatedAt = LocalDateTime.now()
        reports.save(r)
        return mapOf("ok" to true)
    }

    // 상태 변경(관리자) — 접수/처리중/완료
    @PatchMapping("/{id}/status")
    fun setStatus(@PathVariable id: Long, @RequestBody req: StatusReq): Map<String, Any?> {
        val r = reports.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        val s = req.status?.trim().orEmpty()
        if (s !in STATUSES) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad status")
        r.status = s
        r.updatedAt = LocalDateTime.now()
        reports.save(r)
        return mapOf("ok" to true)
    }
}

private fun Report.toSummary(commentCount: Long) = SummaryDto(
    id = id!!,
    type = type,
    title = title,
    reporter = reporter,
    status = status,
    comments = commentCount,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun Report.toDetail(cs: List<ReportComment>) = DetailDto(
    id = id!!,
    type = type,
    title = title,
    body = body,
    reporter = reporter,
    status = status,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    thread = cs.map { CommentDto(it.author, it.role, it.body, it.createdAt.toString()) },
)
