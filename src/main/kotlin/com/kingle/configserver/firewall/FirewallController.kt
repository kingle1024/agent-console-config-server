package com.kingle.configserver.firewall

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
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

@RestController
@RequestMapping("/api/firewalls")
class FirewallController(
    private val requests: FirewallRequestRepository,
    private val files: FirewallFileRepository,
) {
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
