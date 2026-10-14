package com.kingle.configserver.llm

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime

interface LlmNodeRepository : JpaRepository<LlmNode, Long> {
    fun findByUserId(userId: String): LlmNode?
    fun findAllByOrderByUpdatedAtDesc(): List<LlmNode>
}

data class LlmAddrDto(val kind: String, val ip: String)

data class LlmUpReq(
    val userId: String? = null,
    val empNm: String? = null,
    val addrs: List<LlmAddrDto>? = null,
    val port: Int? = null,
    val model: String? = null,
    val appVersion: String? = null,
)

data class LlmOffReq(val userId: String? = null)

data class LlmNodeDto(
    val userId: String,
    val empNm: String?,
    val addrs: List<LlmAddrDto>,
    val port: Int,
    val model: String?,
    val appVersion: String?,
    val updatedAt: String,
    val ageMin: Long, // 마지막 heartbeat 로부터 지난 분
    val alive: Boolean, // maxAgeMin 안에 갱신됐는지
)

// 로컬 LLM 공유 노드 레지스트리 — LLM 게이트를 열어둔 PC 가 자기 주소를 등록(heartbeat)하고,
// 일일보고를 만드는 앱이 목록을 받아 문구 다듬기를 요청한다. X-Api-Key(ReportApiSecurity)로 보호.
@RestController
@RequestMapping("/api/llmnodes")
class LlmNodeController(private val nodes: LlmNodeRepository) {
    // 등록/갱신(heartbeat) — 사용자당 1행 upsert.
    @PostMapping
    fun up(@RequestBody req: LlmUpReq): Map<String, Any?> {
        val userId = req.userId?.trim().orEmpty()
        if (userId.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required")
        val addrs = req.addrs.orEmpty()
            .filter { it.ip.isNotBlank() }
            .joinToString("|") { "${it.kind.ifBlank { "other" }}:${it.ip.trim()}" }
            .take(500)
        if (addrs.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "addrs required")
        val port = req.port ?: 11435
        if (port !in 1..65535) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad port")
        val node = nodes.findByUserId(userId) ?: LlmNode(userId = userId.take(100))
        node.empNm = req.empNm?.trim()?.take(100)?.ifEmpty { null } ?: node.empNm
        node.addrs = addrs
        node.port = port
        node.model = req.model?.trim()?.take(100)?.ifEmpty { null }
        node.appVersion = req.appVersion?.trim()?.take(60)?.ifEmpty { null }
        node.updatedAt = LocalDateTime.now()
        nodes.save(node)
        return mapOf("ok" to true, "userId" to userId)
    }

    // 해제 — 공유를 끄면 목록에서 즉시 사라지게. (DELETE 가 프록시에서 막히는 환경 대비 POST)
    @PostMapping("/off")
    fun off(@RequestBody req: LlmOffReq): Map<String, Any?> {
        val userId = req.userId?.trim().orEmpty()
        if (userId.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required")
        val node = nodes.findByUserId(userId)
        if (node != null) nodes.delete(node)
        return mapOf("ok" to true, "deleted" to (node != null))
    }

    // 목록 — 최근 갱신 순. maxAgeMin(기본 30) 안에 heartbeat 가 있으면 alive=true.
    @GetMapping
    fun list(@RequestParam(required = false) maxAgeMin: Int?): List<LlmNodeDto> {
        val limit = (maxAgeMin ?: 30).coerceIn(1, 10080)
        val now = LocalDateTime.now()
        return nodes.findAllByOrderByUpdatedAtDesc().map { node ->
            val age = Duration.between(node.updatedAt, now).toMinutes().coerceAtLeast(0)
            LlmNodeDto(
                userId = node.userId,
                empNm = node.empNm,
                addrs = node.addrs.split("|").mapNotNull { part ->
                    val colon = part.indexOf(':')
                    if (colon <= 0 || colon == part.length - 1) null
                    else LlmAddrDto(kind = part.substring(0, colon), ip = part.substring(colon + 1))
                },
                port = node.port,
                model = node.model,
                appVersion = node.appVersion,
                updatedAt = node.updatedAt.toString(),
                ageMin = age,
                alive = age <= limit,
            )
        }
    }
}
