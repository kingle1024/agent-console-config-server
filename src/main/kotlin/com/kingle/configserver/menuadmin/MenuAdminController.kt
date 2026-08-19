package com.kingle.configserver.menuadmin

import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

// 메뉴 하나의 관리자 목록 전체 교체 요청.
data class SaveMenuAdminsReq(
    val menuKey: String? = null,
    val loginIds: List<String>? = null,
    val updatedBy: String? = null,
)

/**
 * 메뉴별 관리자 — 앱(FI개발도우미)의 메뉴 노출·관리자 기능 게이팅에 쓰는 공유 목록.
 * 조회는 전 사용자(앱이 시작할 때 1회), 저장은 [기타>관리자 설정] 화면(슈퍼관리자 전용, 앱에서 게이팅)에서만 호출한다.
 *  - 목록 : GET  /api/menuadmins           → { "firewall": ["ejy10241","78alswo"], ... }
 *  - 저장 : POST /api/menuadmins           (메뉴 1개 단위 전체 교체)
 * 다른 앱 API 와 동일하게 X-Api-Key 로만 보호한다(사내 도구 · 권한 판정은 앱이 수행).
 */
@RestController
@RequestMapping("/api/menuadmins")
class MenuAdminController(
    private val repo: MenuAdminRepository,
) {
    // 메뉴키 → 로그인ID 목록. 한 번도 저장하지 않은 메뉴는 키 자체가 없다(앱이 내장 기본값으로 폴백).
    @GetMapping
    fun list(): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (row in repo.findAllByOrderByMenuKeyAscSortOrderAscIdAsc()) {
            out.getOrPut(row.menuKey) { mutableListOf() }.add(row.loginId)
        }
        return out
    }

    // 메뉴 1개의 관리자 목록 전체 교체(delete + insert). 빈 목록이면 그 메뉴 기록을 지운다(= 앱 기본값으로 복귀).
    @PostMapping
    @Transactional
    fun save(@RequestBody req: SaveMenuAdminsReq): Map<String, Any?> {
        val key = req.menuKey?.trim()?.lowercase().orEmpty()
        if (key.isEmpty() || !key.matches(Regex("^[a-z0-9_-]{1,60}$"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "menuKey required (a-z0-9_-)")
        }
        val ids = req.loginIds.orEmpty()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it.length <= 100 }
            .distinct()
        repo.deleteAllByMenuKey(key)
        val now = LocalDateTime.now()
        val by = req.updatedBy?.trim()?.lowercase()?.take(100)?.ifEmpty { null }
        val entities = ids.mapIndexed { idx, id ->
            MenuAdmin(menuKey = key, loginId = id, sortOrder = idx, updatedBy = by, updatedAt = now)
        }
        if (entities.isNotEmpty()) repo.saveAll(entities)
        return mapOf("menuKey" to key, "saved" to entities.size)
    }
}
