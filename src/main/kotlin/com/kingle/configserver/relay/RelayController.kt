package com.kingle.configserver.relay

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

interface RelayNodeRepository : JpaRepository<RelayNode, Long> {
    fun findByUserId(userId: String): RelayNode?
    fun findAllByOrderByUpdatedAtDesc(): List<RelayNode>
}

data class RelayAddrDto(val kind: String, val ip: String)

data class RelayUpReq(
    val userId: String? = null,
    val empNm: String? = null,
    val addrs: List<RelayAddrDto>? = null,
    val port: Int? = null,
    val targetHost: String? = null,
    val appVersion: String? = null,
)

data class RelayOffReq(val userId: String? = null)

data class RelayNodeDto(
    val userId: String,
    val empNm: String?,
    val addrs: List<RelayAddrDto>,
    val port: Int,
    val targetHost: String?,
    val appVersion: String?,
    val updatedAt: String,
    val ageMin: Long, // 마지막 heartbeat 로부터 지난 분
    val alive: Boolean, // maxAgeMin 안에 갱신됐는지
)

// NSM DB 중계 노드 레지스트리 — 중계를 열어둔 PC 가 자기 주소를 등록(heartbeat)하고,
// 직접 접속 권한이 없는 자리가 목록을 받아 붙는다. X-Api-Key(ReportApiSecurity)로 보호.
@RestController
@RequestMapping("/api/relays")
class RelayController(private val nodes: RelayNodeRepository) {
    // 등록/갱신(heartbeat) — 사용자당 1행 upsert.
    @PostMapping
    fun up(@RequestBody req: RelayUpReq): Map<String, Any?> {
        val userId = req.userId?.trim().orEmpty()
        if (userId.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required")
        val addrs = req.addrs.orEmpty()
            .filter { it.ip.isNotBlank() }
            .joinToString("|") { "${it.kind.ifBlank { "other" }}:${it.ip.trim()}" }
            .take(500)
        if (addrs.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "addrs required")
        val port = req.port ?: 15253
        if (port !in 1..65535) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad port")
        val node = nodes.findByUserId(userId) ?: RelayNode(userId = userId.take(100))
        node.empNm = req.empNm?.trim()?.take(100)?.ifEmpty { null } ?: node.empNm
        node.addrs = addrs
        node.port = port
        node.targetHost = req.targetHost?.trim()?.take(200)?.ifEmpty { null }
        node.appVersion = req.appVersion?.trim()?.take(60)?.ifEmpty { null }
        node.updatedAt = LocalDateTime.now()
        nodes.save(node)
        return mapOf("ok" to true, "userId" to userId)
    }

    // 해제 — 중계를 끄면 목록에서 즉시 사라지게. (DELETE 가 프록시에서 막히는 환경 대비 POST)
    @PostMapping("/off")
    fun off(@RequestBody req: RelayOffReq): Map<String, Any?> {
        val userId = req.userId?.trim().orEmpty()
        if (userId.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required")
        val node = nodes.findByUserId(userId)
        if (node != null) nodes.delete(node)
        return mapOf("ok" to true, "deleted" to (node != null))
    }

    // 목록 — 최근 갱신 순. maxAgeMin(기본 30) 안에 heartbeat 가 있으면 alive=true.
    // 죽은 노드도 함께 내려준다(앱이 회색으로 보여주고, 자동 시도에서는 alive 우선).
    @GetMapping
    fun list(@RequestParam(required = false) maxAgeMin: Int?): List<RelayNodeDto> {
        val limit = (maxAgeMin ?: 30).coerceIn(1, 10080)
        val now = LocalDateTime.now()
        return nodes.findAllByOrderByUpdatedAtDesc().map { n ->
            val age = Duration.between(n.updatedAt, now).toMinutes().coerceAtLeast(0)
            RelayNodeDto(
                userId = n.userId,
                empNm = n.empNm,
                addrs = n.addrs.split("|").mapNotNull { part ->
                    val i = part.indexOf(':')
                    if (i <= 0 || i == part.length - 1) null
                    else RelayAddrDto(kind = part.substring(0, i), ip = part.substring(i + 1))
                },
                port = n.port,
                targetHost = n.targetHost,
                appVersion = n.appVersion,
                updatedAt = n.updatedAt.toString(),
                ageMin = age,
                alive = age <= limit,
            )
        }
    }
}
