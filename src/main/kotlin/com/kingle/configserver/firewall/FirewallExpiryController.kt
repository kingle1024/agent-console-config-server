package com.kingle.configserver.firewall

import org.springframework.dao.DataIntegrityViolationException
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

data class SaveExpiryReq(
    val serviceName: String? = null,
    val srcIp: String? = null,
    val destIp: String? = null,
    val port: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val docTitle: String? = null,
    val docId: String? = null,
    val mailDate: String? = null,
    val detectedBy: String? = null,
)

data class ExtendedReq(
    val extendDocId: String? = null,
    val extendStartDate: String? = null,
    val extendEndDate: String? = null,
    val extendedBy: String? = null,
)

// 방화벽 만료 안내 목록 — 앱(ejy10241·78alswo PC)이 네이버 메일에서 읽어 기록하고 [방화벽 만료 목록]에서 공유 조회.
//  - POST /api/firewalls/expiries              : 기록(같은 정책이면 created=false — 쪽지 중복 방지 기준)
//  - GET  /api/firewalls/expiries              : 목록(종료일 최근순 500건)
//  - POST /api/firewalls/expiries/{id}/extended : [연장하기] 상신 결과 기록
//  - POST /api/firewalls/expiries/{id}/delete   : 삭제
// 경로를 /api/firewalls 하위에 두어 기존 보안 설정(X-Api-Key)을 그대로 탄다.
@RestController
@RequestMapping("/api/firewalls/expiries")
class FirewallExpiryController(
    private val expiries: FirewallExpiryRepository,
) {
    @GetMapping
    fun list(): List<Map<String, Any?>> =
        expiries.findTop500ByOrderByEndDateDesc().map { it.toMap() }

    @PostMapping
    fun create(@RequestBody req: SaveExpiryReq): Map<String, Any?> {
        val serviceName = req.serviceName?.trim().orEmpty().take(200)
        val destIp = req.destIp?.trim().orEmpty().take(500)
        val endDate = req.endDate?.trim().orEmpty().take(20)
        if (serviceName.isEmpty() || destIp.isEmpty() || endDate.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "serviceName, destIp, endDate required")
        }
        val existing = expiries.findByServiceNameAndDestIpAndEndDate(serviceName, destIp, endDate)
        if (existing != null) {
            return fillMissing(existing, req)
        }
        return insertNew(req, serviceName, destIp, endDate)
    }

    // 동시에 두 PC 가 같은 건을 넣으면 유니크 제약으로 한쪽이 실패 — 그쪽은 기존 건(created=false)으로 응답한다.
    private fun insertNew(req: SaveExpiryReq, serviceName: String, destIp: String, endDate: String): Map<String, Any?> {
        val expiry = FirewallExpiry(
            serviceName = serviceName,
            srcIp = req.srcIp.clean(200),
            destIp = destIp,
            port = req.port.clean(300),
            startDate = req.startDate.clean(20),
            endDate = endDate,
            docTitle = req.docTitle.clean(500),
            docId = req.docId.clean(50),
            mailDate = req.mailDate.clean(40),
            detectedBy = req.detectedBy.clean(100),
        )
        try {
            expiries.saveAndFlush(expiry)
        } catch (e: DataIntegrityViolationException) {
            val raced = expiries.findByServiceNameAndDestIpAndEndDate(serviceName, destIp, endDate) ?: throw e
            return mapOf("id" to raced.id, "created" to false)
        }
        return mapOf("id" to expiry.id, "created" to true)
    }

    // 먼저 잡은 PC 가 아마란스에서 문서를 못 찾았으면(수신참조함에 없음) 나중 PC 의 조회 결과로 채운다.
    private fun fillMissing(existing: FirewallExpiry, req: SaveExpiryReq): Map<String, Any?> {
        val title = req.docTitle.clean(500)
        if (existing.docTitle == null && title != null) {
            existing.docTitle = title
            existing.docId = req.docId.clean(50)
            existing.updatedAt = LocalDateTime.now()
            expiries.save(existing)
        }
        return mapOf("id" to existing.id, "created" to false)
    }

    @RequestMapping("/{id}/extended", method = [RequestMethod.POST, RequestMethod.PATCH])
    fun extended(@PathVariable id: Long, @RequestBody req: ExtendedReq): Map<String, Any?> {
        val expiry = expiries.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "not found") }
        expiry.extendDocId = req.extendDocId.clean(50)
        expiry.extendStartDate = req.extendStartDate.clean(20)
        expiry.extendEndDate = req.extendEndDate.clean(20)
        expiry.extendedBy = req.extendedBy.clean(100)
        expiry.extendedAt = LocalDateTime.now()
        expiry.updatedAt = LocalDateTime.now()
        expiries.save(expiry)
        return mapOf("ok" to true)
    }

    @RequestMapping("/{id}/delete", method = [RequestMethod.POST, RequestMethod.DELETE])
    fun delete(@PathVariable id: Long): Map<String, Any?> {
        if (!expiries.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "not found")
        expiries.deleteById(id)
        return mapOf("ok" to true)
    }
}

private fun String?.clean(max: Int): String? = this?.trim()?.take(max)?.ifEmpty { null }

private fun FirewallExpiry.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "serviceName" to serviceName,
    "srcIp" to (srcIp ?: ""),
    "destIp" to destIp,
    "port" to (port ?: ""),
    "startDate" to (startDate ?: ""),
    "endDate" to endDate,
    "docTitle" to (docTitle ?: ""),
    "docId" to (docId ?: ""),
    "mailDate" to (mailDate ?: ""),
    "detectedBy" to (detectedBy ?: ""),
    "extendDocId" to (extendDocId ?: ""),
    "extendStartDate" to (extendStartDate ?: ""),
    "extendEndDate" to (extendEndDate ?: ""),
    "extendedBy" to (extendedBy ?: ""),
    "extendedAt" to (extendedAt?.toString() ?: ""),
    "createdAt" to createdAt.toString(),
    "updatedAt" to updatedAt.toString(),
)
