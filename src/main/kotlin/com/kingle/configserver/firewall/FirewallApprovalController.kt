package com.kingle.configserver.firewall

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

// 요청/응답 DTO
data class SaveApprovalReq(
    val customer: String? = null,
    val ip: String? = null,
    val port: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val docId: String? = null,
    val drafterName: String? = null,
    val drafterLoginId: String? = null,
    val note: String? = null,
)

// 방화벽 결재 상신 이력 — 앱의 [결재 상신] 성공 직후 자동 기록(POST), 목록(GET)은 전 사용자 공유.
// 수정/삭제는 앱에서 관리자에게만 버튼을 노출한다(서버는 다른 API 와 동일하게 X-Api-Key 만 검증).
// 경로를 /api/firewalls 하위에 두어 기존 보안 설정(SecurityConfig·ReportApiSecurity)을 그대로 탄다.
@RestController
@RequestMapping("/api/firewalls/approvals")
class FirewallApprovalController(
    private val approvals: FirewallApprovalRepository,
) {
    @GetMapping
    fun list(): List<Map<String, Any?>> =
        approvals.findTop1000ByOrderByCreatedAtDesc().map { it.toMap() }

    // 상신 이력 기록 — customer 필수, 나머지는 있는 값만 저장.
    @PostMapping
    fun create(@RequestBody req: SaveApprovalReq): Map<String, Any?> {
        val customer = req.customer?.trim().orEmpty()
        if (customer.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "customer required")
        val a = FirewallApproval(
            customer = customer.take(200),
            ip = req.ip?.trim().orEmpty().take(500),
            port = req.port?.trim().orEmpty().take(300),
            startDate = req.startDate?.trim()?.take(20)?.ifEmpty { null },
            endDate = req.endDate?.trim()?.take(20)?.ifEmpty { null },
            docId = req.docId?.trim()?.take(100)?.ifEmpty { null },
            drafterName = req.drafterName?.trim()?.take(100)?.ifEmpty { null },
            drafterLoginId = req.drafterLoginId?.trim()?.take(100)?.ifEmpty { null },
        )
        approvals.save(a)
        return mapOf("id" to a.id)
    }

    // 수정(관리자) — 넘어온 필드만 교체. POST·PATCH 둘 다 허용(cloudtype 프록시 PATCH 차단 대비).
    @RequestMapping("/{id}", method = [RequestMethod.POST, RequestMethod.PATCH])
    fun update(@PathVariable id: Long, @RequestBody req: SaveApprovalReq): Map<String, Any?> {
        val a = approvals.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        req.customer?.let { v -> v.trim().ifEmpty { null }?.let { a.customer = it.take(200) } }
        req.ip?.let { a.ip = it.trim().take(500) }
        req.port?.let { a.port = it.trim().take(300) }
        req.startDate?.let { a.startDate = it.trim().take(20).ifEmpty { null } }
        req.endDate?.let { a.endDate = it.trim().take(20).ifEmpty { null } }
        req.docId?.let { a.docId = it.trim().take(100).ifEmpty { null } }
        req.note?.let { a.note = it.trim().take(500).ifEmpty { null } }
        a.updatedAt = LocalDateTime.now()
        approvals.save(a)
        return mapOf("ok" to true)
    }

    // 삭제(관리자) — DELETE 차단 프록시 대비 POST /{id}/delete 사용.
    @RequestMapping("/{id}/delete", method = [RequestMethod.POST, RequestMethod.DELETE])
    fun delete(@PathVariable id: Long): Map<String, Any?> {
        if (!approvals.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "not found")
        approvals.deleteById(id)
        return mapOf("ok" to true)
    }
}

private fun FirewallApproval.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "customer" to customer,
    "ip" to ip,
    "port" to port,
    "startDate" to (startDate ?: ""),
    "endDate" to (endDate ?: ""),
    "docId" to (docId ?: ""),
    "drafterName" to (drafterName ?: ""),
    "drafterLoginId" to (drafterLoginId ?: ""),
    "note" to (note ?: ""),
    "createdAt" to createdAt.toString(),
    "updatedAt" to updatedAt.toString(),
)
