package com.kingle.configserver.firewall

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
import java.time.LocalDateTime

// 요청/응답 DTO
data class FileReq(
    val kind: String? = null,
    val filename: String? = null,
    val objectKey: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
)
data class CreateFirewallReq(
    val ip: String? = null,
    val port: String? = null,
    val reporter: String? = null,
    val reporterUserId: String? = null,
    val appVersion: String? = null,
    val osInfo: String? = null,
    val files: List<FileReq>? = null,
)
data class FwStatusReq(val status: String? = null)

data class FileDto(
    val id: Long,
    val kind: String,
    val filename: String,
    val objectKey: String,
    val contentType: String?,
    val sizeBytes: Long?,
)
data class FwSummaryDto(
    val id: Long,
    val ip: String,
    val port: String,
    val reporter: String,
    val status: String,
    val files: Long,
    val createdAt: String,
    val updatedAt: String,
)
data class FwDetailDto(
    val id: Long,
    val ip: String,
    val port: String,
    val reporter: String,
    val reporterUserId: String?,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val attachments: List<FileDto>,
)

private val FW_STATUSES = listOf("접수", "처리중", "완료")

private const val FIREWALL_PREFIX = "firewall"

@RestController
@RequestMapping("/api/firewalls")
class FirewallController(
    private val requests: FirewallRequestRepository,
    private val files: FirewallFileRepository,
    private val r2: R2Uploader,
) {
    // 첨부 업로드 — 앱이 파일 바이트를 multipart 로 보내면 서버가 R2 에 올린다(쓰기 키는 서버에만).
    // 반환 objectKey 를 앱이 받아 create 의 files[] 에 넣는다. consent/serverlist 가 같은 folder 를 공유한다.
    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) folder: String?,
        @RequestParam("file") file: MultipartFile,
    ): Map<String, Any?> {
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "empty file")
        val k = if (kind == "serverlist") "serverlist" else "consent"
        val fld = folder?.trim().orEmpty().ifEmpty { System.currentTimeMillis().toString(36) }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val filename = (file.originalFilename ?: "file").substringAfterLast('/').substringAfterLast('\\').ifEmpty { "file" }
        val safeName = filename.replace(Regex("[\\\\/\\r\\n\\t]+"), "_").replace(Regex("\\s+"), "_")
        val contentType = file.contentType?.trim()?.ifEmpty { null } ?: "application/octet-stream"
        val key = "$FIREWALL_PREFIX/$fld/$k-$safeName"
        r2.putObject(key, file.bytes, contentType)
        return mapOf(
            "kind" to k,
            "filename" to filename,
            "objectKey" to key,
            "contentType" to contentType,
            "sizeBytes" to file.size,
            "publicUrl" to r2.publicUrl(key),
        )
    }

    // 신청 작성 — IP/PORT 필수, 고객사동의서(consent) 첨부 1개 이상 필수.
    // 파일은 앱이 R2 에 먼저 올리고 objectKey 만 넘긴다(서버는 메타만 저장).
    @PostMapping
    fun create(@RequestBody req: CreateFirewallReq): Map<String, Any?> {
        val ip = req.ip?.trim().orEmpty()
        val port = req.port?.trim().orEmpty()
        if (ip.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ip required")
        if (port.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "port required")
        val incoming = req.files.orEmpty().filter { !it.objectKey.isNullOrBlank() }
        if (incoming.none { it.kind == "consent" }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "consent file required")
        }
        val r = FirewallRequest(
            ip = ip,
            port = port,
            reporter = req.reporter?.trim().orEmpty().ifEmpty { "unknown" },
            reporterUserId = req.reporterUserId?.trim()?.ifEmpty { null },
            appVersion = req.appVersion?.trim(),
            osInfo = req.osInfo?.trim(),
        )
        requests.save(r)
        incoming.forEach { f ->
            files.save(
                FirewallFile(
                    requestId = r.id!!,
                    kind = if (f.kind == "serverlist") "serverlist" else "consent",
                    filename = f.filename?.trim().orEmpty().ifEmpty { "file" },
                    objectKey = f.objectKey!!.trim(),
                    contentType = f.contentType?.trim()?.ifEmpty { null },
                    sizeBytes = f.sizeBytes,
                )
            )
        }
        return mapOf("id" to r.id)
    }

    // 목록 — reporter 지정 시 본인 것만(신청자), 없으면 전체(관리자)
    @GetMapping
    fun list(@RequestParam(required = false) reporter: String?): List<FwSummaryDto> {
        val list = if (reporter.isNullOrBlank()) {
            requests.findAllByOrderByUpdatedAtDesc()
        } else {
            requests.findByReporterOrderByUpdatedAtDesc(reporter)
        }
        return list.map { it.toSummary(files.countByRequestId(it.id!!)) }
    }

    // 상세 + 첨부파일
    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): FwDetailDto {
        val r = requests.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        val fs = files.findByRequestIdOrderByIdAsc(id)
        return r.toDetail(fs)
    }

    // 상태 변경(관리자) — 접수/처리중/완료. POST·PATCH 둘 다 허용(cloudtype 프록시 PATCH 차단 대비).
    @RequestMapping("/{id}/status", method = [RequestMethod.POST, RequestMethod.PATCH])
    fun setStatus(@PathVariable id: Long, @RequestBody req: FwStatusReq): Map<String, Any?> {
        val r = requests.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        val s = req.status?.trim().orEmpty()
        if (s !in FW_STATUSES) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad status")
        r.status = s
        r.updatedAt = LocalDateTime.now()
        requests.save(r)
        return mapOf("ok" to true)
    }
}

private fun FirewallRequest.toSummary(fileCount: Long) = FwSummaryDto(
    id = id!!,
    ip = ip,
    port = port,
    reporter = reporter,
    status = status,
    files = fileCount,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun FirewallRequest.toDetail(fs: List<FirewallFile>) = FwDetailDto(
    id = id!!,
    ip = ip,
    port = port,
    reporter = reporter,
    reporterUserId = reporterUserId,
    status = status,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    attachments = fs.map { FileDto(it.id!!, it.kind, it.filename, it.objectKey, it.contentType, it.sizeBytes) },
)
