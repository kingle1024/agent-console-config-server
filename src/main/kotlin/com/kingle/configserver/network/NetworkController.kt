package com.kingle.configserver.network

import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

// 요청/응답 DTO
data class MemberDto(
    val id: Long? = null,
    val name: String? = null,
    val wiredIp: String? = null,
    val wirelessIp: String? = null,
    val vpnId: String? = null,
    val included: Boolean? = null,
)
data class SaveMembersReq(val members: List<MemberDto>? = null)

data class ServerDto(
    val id: Long? = null,
    val ip: String? = null,
    val name: String? = null,
    val port: String? = null,
)
data class SaveServersReq(val servers: List<ServerDto>? = null)

// IP/PORT 방화벽 신청서 생성용 마스터 데이터 — 구성원(유선/무선/VPN)과 신청 대상 서버(IP/PORT).
// 목록 조회(GET)와 전체 교체 저장(POST)만 제공한다. 소규모 공유 그리드라 개별 행 CRUD 대신
// "그리드 전체를 통째로 저장"(delete-all + save-all) 방식이 편집 UI 와 잘 맞는다. X-Api-Key 로만 보호.
// (앱 UI 노출은 78alswo·ejy10241 로만 제한하지만, 데이터 API 는 다른 API 와 동일하게 키 기반이다.)
@RestController
@RequestMapping("/api/network")
class NetworkController(
    private val members: NetworkMemberRepository,
    private val servers: NetworkServerRepository,
) {
    @GetMapping("/members")
    fun listMembers(): List<Map<String, Any?>> =
        members.findAllByOrderBySortOrderAscIdAsc().map { it.toMap() }

    // 전체 교체 저장. 빈 목록이면 전부 삭제. 순서는 배열 인덱스로 보존(sortOrder).
    @PostMapping("/members")
    @Transactional
    fun saveMembers(@RequestBody req: SaveMembersReq): Map<String, Any?> {
        val incoming = req.members.orEmpty()
        members.deleteAllInBatch()
        val now = LocalDateTime.now()
        val entities = incoming.mapIndexedNotNull { idx, m ->
            val nm = m.name?.trim().orEmpty().take(100)
            if (nm.isEmpty()) return@mapIndexedNotNull null
            NetworkMember(
                name = nm,
                wiredIp = m.wiredIp?.trim()?.take(60)?.ifEmpty { null },
                wirelessIp = m.wirelessIp?.trim()?.take(60)?.ifEmpty { null },
                vpnId = m.vpnId?.trim()?.take(100)?.ifEmpty { null },
                included = m.included ?: true,
                sortOrder = idx,
                updatedAt = now,
            )
        }
        if (entities.isNotEmpty()) members.saveAll(entities)
        return mapOf("saved" to entities.size)
    }

    @GetMapping("/servers")
    fun listServers(): List<Map<String, Any?>> =
        servers.findAllByOrderBySortOrderAscIdAsc().map { it.toMap() }

    @PostMapping("/servers")
    @Transactional
    fun saveServers(@RequestBody req: SaveServersReq): Map<String, Any?> {
        val incoming = req.servers.orEmpty()
        servers.deleteAllInBatch()
        val now = LocalDateTime.now()
        val entities = incoming.mapIndexedNotNull { idx, s ->
            val ip = s.ip?.trim().orEmpty().take(60)
            val nm = s.name?.trim().orEmpty().take(200)
            val port = s.port?.trim().orEmpty().take(300)
            if (ip.isEmpty() && nm.isEmpty() && port.isEmpty()) return@mapIndexedNotNull null
            NetworkServer(
                ip = ip,
                name = nm,
                port = port,
                sortOrder = idx,
                updatedAt = now,
            )
        }
        if (entities.isNotEmpty()) servers.saveAll(entities)
        return mapOf("saved" to entities.size)
    }
}

private fun NetworkMember.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "wiredIp" to (wiredIp ?: ""),
    "wirelessIp" to (wirelessIp ?: ""),
    "vpnId" to (vpnId ?: ""),
    "included" to (included ?: true),
)

private fun NetworkServer.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "ip" to ip,
    "name" to name,
    "port" to port,
)
