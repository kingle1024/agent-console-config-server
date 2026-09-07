package com.kingle.configserver.report

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import com.kingle.configserver.storage.R2Uploader
import java.time.LocalDateTime

// 요청/응답 DTO
data class CreateReq(
    val type: String? = null,
    val title: String? = null,
    val body: String? = null,
    val reporter: String? = null,
    val reporterUserId: String? = null,
    val appVersion: String? = null,
    val osInfo: String? = null,
    // 첨부 메타 — 앱이 /upload 로 먼저 올리고 받은 objectKey 를 넣는다(없으면 첨부 없음)
    val files: List<ReportFileReq>? = null,
)
data class ReportFileReq(
    val filename: String? = null,
    val objectKey: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
)
data class ReportFileDto(
    val id: Long,
    val filename: String,
    val objectKey: String,
    val contentType: String?,
    val sizeBytes: Long?,
    val publicUrl: String,
)
data class CommentReq(val author: String? = null, val role: String? = null, val body: String? = null)
data class StatusReq(val status: String? = null)
data class DeleteReq(val reporter: String? = null, val admin: Boolean? = null)

data class SummaryDto(
    val id: Long,
    val type: String,
    val title: String,
    val reporter: String,
    val status: String,
    val comments: Long,
    val files: Long,
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
    val reporterUserId: String?,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val thread: List<CommentDto>,
    val attachments: List<ReportFileDto>,
)

private val STATUSES = listOf("접수", "처리중", "완료")
private const val REPORT_PREFIX = "reports"

@RestController
@RequestMapping("/api/reports")
class ReportController(
    private val reports: ReportRepository,
    private val comments: ReportCommentRepository,
    private val files: ReportFileRepository,
    private val r2: R2Uploader,
) {
    // 첨부 업로드 — 앱이 파일 바이트를 multipart 로 보내면 서버가 R2 에 올린다(쓰기 키는 서버에만).
    // 반환 objectKey 를 앱이 create 의 files[] 에 넣는다. 한 리포트의 첨부는 같은 folder 에 모인다.
    // R2 키 미설정이면 R2Uploader 가 503 을 낸다.
    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestParam(required = false) folder: String?,
        @RequestParam("file") file: MultipartFile,
    ): Map<String, Any?> {
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "empty file")
        val fld = folder?.trim().orEmpty().ifEmpty { System.currentTimeMillis().toString(36) }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val filename = (file.originalFilename ?: "file").substringAfterLast('/').substringAfterLast('\\').ifEmpty { "file" }
        val safeName = filename.replace(Regex("[\\\\/\\r\\n\\t]+"), "_").replace(Regex("\\s+"), "_")
        val contentType = file.contentType?.trim()?.ifEmpty { null } ?: "application/octet-stream"
        val key = "$REPORT_PREFIX/$fld/$safeName"
        r2.putObject(key, file.bytes, contentType)
        return mapOf(
            "filename" to filename,
            "objectKey" to key,
            "contentType" to contentType,
            "sizeBytes" to file.size,
            "publicUrl" to r2.publicUrl(key),
        )
    }

    // 작성 → Issue 생성. 첨부는 앱이 /upload 로 먼저 올리고 objectKey 만 넘긴다(서버는 메타만 저장).
    @PostMapping
    fun create(@RequestBody req: CreateReq): Map<String, Any?> {
        val title = req.title?.trim().orEmpty()
        if (title.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "title required")
        val r = Report(
            type = if (req.type == "enhancement") "enhancement" else "bug",
            title = title,
            body = req.body?.trim().orEmpty(),
            reporter = req.reporter?.trim().orEmpty().ifEmpty { "unknown" },
            reporterUserId = req.reporterUserId?.trim()?.ifEmpty { null },
            appVersion = req.appVersion?.trim(),
            osInfo = req.osInfo?.trim(),
        )
        reports.save(r)
        req.files.orEmpty().filter { !it.objectKey.isNullOrBlank() }.forEach { f ->
            files.save(
                ReportFile(
                    reportId = r.id!!,
                    filename = f.filename?.trim().orEmpty().ifEmpty { "file" },
                    objectKey = f.objectKey!!.trim(),
                    contentType = f.contentType?.trim()?.ifEmpty { null },
                    sizeBytes = f.sizeBytes,
                )
            )
        }
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
        return list.map { it.toSummary(comments.countByReportId(it.id!!), files.countByReportId(it.id!!)) }
    }

    // 상세 + 댓글 스레드
    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): DetailDto {
        val r = reports.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        val cs = comments.findByReportIdOrderByCreatedAtAsc(id)
        val fs = files.findByReportIdOrderByIdAsc(id)
        return r.toDetail(cs, fs.map { ReportFileDto(it.id!!, it.filename, it.objectKey, it.contentType, it.sizeBytes, r2.publicUrl(it.objectKey)) })
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

    // 상태 변경(관리자) — 접수/처리중/완료.
    // ★POST·PATCH 둘 다 허용★: cloudtype 프록시가 PATCH 를 막는 환경이 있어 앱은 POST 로 보낸다(하위호환).
    @RequestMapping("/{id}/status", method = [RequestMethod.POST, RequestMethod.PATCH])
    fun setStatus(@PathVariable id: Long, @RequestBody req: StatusReq): Map<String, Any?> {
        val r = reports.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        val s = req.status?.trim().orEmpty()
        if (s !in STATUSES) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad status")
        r.status = s
        r.updatedAt = LocalDateTime.now()
        reports.save(r)
        return mapOf("ok" to true)
    }

    // 삭제 — 작성자 본인이 (아직 답변이 없거나 '접수' 상태일 때) 스스로 삭제. 관리자는 제한 없이 삭제.
    // ★POST★ 로 받는다: cloudtype 프록시가 DELETE 를 막는 환경이 있어 상태변경과 동일하게 POST 로 통일.
    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: Long, @RequestBody(required = false) req: DeleteReq?): Map<String, Any?> {
        val r = reports.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        val hasAdminReply = comments.findByReportIdOrderByCreatedAtAsc(id).any { it.role == "admin" }
        val isAdmin = req?.admin == true
        val requester = req?.reporter?.trim().orEmpty()
        val isOwner = requester.isNotEmpty() && requester == r.reporter
        // 삭제 가능: 관리자거나, (본인 글이면서 아직 관리자 답변이 없거나 '접수' 상태)
        val deletable = isAdmin || (isOwner && (r.status == "접수" || !hasAdminReply))
        if (!deletable) throw ResponseStatusException(HttpStatus.FORBIDDEN, "not deletable")
        comments.deleteByReportId(id)
        files.deleteByReportId(id) // R2 객체는 남긴다(공개 URL 참조만 끊김)
        reports.delete(r)
        return mapOf("ok" to true)
    }
}

private fun Report.toSummary(commentCount: Long, fileCount: Long) = SummaryDto(
    id = id!!,
    type = type,
    title = title,
    reporter = reporter,
    status = status,
    comments = commentCount,
    files = fileCount,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun Report.toDetail(cs: List<ReportComment>, fs: List<ReportFileDto>) = DetailDto(
    id = id!!,
    type = type,
    title = title,
    body = body,
    reporter = reporter,
    reporterUserId = reporterUserId,
    status = status,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    thread = cs.map { CommentDto(it.author, it.role, it.body, it.createdAt.toString()) },
    attachments = fs,
)
