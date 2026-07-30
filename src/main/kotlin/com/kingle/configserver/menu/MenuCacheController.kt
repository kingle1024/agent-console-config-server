package com.kingle.configserver.menu

import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

interface MenuCacheRepository : JpaRepository<MenuCache, Long> {
    fun findByModuleCd(moduleCd: String): MenuCache?
    fun findAllByOrderByModuleCdAsc(): List<MenuCache>
}

interface MenuNameRepository : JpaRepository<MenuName, Long> {
    fun findAllByModuleCd(moduleCd: String): List<MenuName>
    fun findAllByMenuCd(menuCd: String): List<MenuName>
    fun deleteAllByModuleCd(moduleCd: String)
}

data class MenuCacheUpReq(
    val module: String? = null,
    val moduleNm: String? = null,
    val names: Map<String, String>? = null,
    val userId: String? = null,
)

data class MenuCacheDto(
    val module: String,
    val moduleNm: String?,
    val names: Map<String, String>,
    val nameCnt: Int,
    val updatedAt: String,
)

data class MenuCacheSummaryDto(
    val module: String,
    val moduleNm: String?,
    val nameCnt: Int,
    val updatedAt: String,
)

data class MenuFindDto(
    val module: String,
    val moduleNm: String?,
    val code: String,
    val name: String,
    // 상위 폴더부터 대상까지의 메뉴명 — ["자금수지관리","자료수집"]
    val path: List<String>,
)

/**
 * comet 메뉴명 캐시 — 모듈별 코드→메뉴명 사전.
 * [Gitlab 등록] 이 프로젝트 설명(TR - 자금관리 - 자금수지관리 - 자료수집)을 자동으로 만들 때 쓴다.
 * 앱이 stg comet MenuService 를 모듈당 한 번만 조회해 올리면 그 뒤엔 모두가 이 캐시를 쓴다.
 * X-Api-Key(ReportApiSecurity)로 보호.
 */
@RestController
@RequestMapping("/api/menucache")
class MenuCacheController(
    private val repo: MenuCacheRepository,
    private val names: MenuNameRepository,
) {
    // 등록/갱신 — 모듈당 1행(+메뉴명 행 전체 교체) upsert.
    @PostMapping
    @Transactional
    fun up(@RequestBody req: MenuCacheUpReq): Map<String, Any?> {
        val module = req.module?.trim()?.uppercase().orEmpty()
        if (module.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "module required")
        val incoming = req.names.orEmpty()
            .mapNotNull { (k, v) ->
                val cd = k.trim().uppercase()
                val nm = v.trim()
                if (cd.isEmpty() || nm.isEmpty()) null else cd to nm
            }
            .toMap()
        if (incoming.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "names required")
        val row = repo.findByModuleCd(module) ?: MenuCache(moduleCd = module.take(20))
        row.moduleNm = req.moduleNm?.trim()?.take(200)?.ifEmpty { null } ?: row.moduleNm
        row.nameCnt = incoming.size
        row.userId = req.userId?.trim()?.take(100)?.ifEmpty { null } ?: row.userId
        row.updatedAt = LocalDateTime.now()
        repo.save(row)
        names.deleteAllByModuleCd(module)
        names.saveAll(
            incoming.map { (cd, nm) ->
                MenuName(moduleCd = module.take(20), menuCd = cd.take(60), menuNm = nm.take(300))
            }
        )
        return mapOf("ok" to true, "module" to module, "nameCnt" to incoming.size)
    }

    // 캐시된 모듈 목록(사전 본문 없이 요약만) — 어느 모듈이 이미 캐시됐는지 확인용.
    @GetMapping
    fun list(): List<MenuCacheSummaryDto> =
        repo.findAllByOrderByModuleCdAsc().map {
            MenuCacheSummaryDto(it.moduleCd, it.moduleNm, it.nameCnt, it.updatedAt.toString())
        }

    /**
     * 메뉴코드로 모듈·경로 찾기 — 어느 모듈 소속인지 모를 때(개발완료처리 파일코드 등) 쓴다.
     * 캐시된 모듈에서만 찾는다(없으면 404) — 앱이 모듈을 올릴수록 정확해진다.
     * 경로는 코드 접두사 관계로 만든다(TMFBDM00100 → TMF > TMFBDM > TMFBDM00100).
     */
    @GetMapping("/find")
    fun find(@RequestParam code: String): MenuFindDto {
        val want = code.trim().uppercase()
        if (want.length < 3) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "code too short")
        val hit = names.findAllByMenuCd(want).firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "code not cached: $want")
        val meta = repo.findByModuleCd(hit.moduleCd)
        val path = names.findAllByModuleCd(hit.moduleCd)
            .filter { it.menuCd.length < want.length && want.startsWith(it.menuCd) }
            .sortedBy { it.menuCd.length }
            .map { it.menuNm } + hit.menuNm
        return MenuFindDto(hit.moduleCd, meta?.moduleNm, want, hit.menuNm, path)
    }

    // 모듈 1개의 사전 전체.
    @GetMapping("/{module}")
    fun get(@PathVariable module: String): MenuCacheDto {
        val cd = module.trim().uppercase()
        val row = repo.findByModuleCd(cd)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "not cached: $cd")
        val map = names.findAllByModuleCd(cd).associate { it.menuCd to it.menuNm }
        return MenuCacheDto(row.moduleCd, row.moduleNm, map, row.nameCnt, row.updatedAt.toString())
    }
}
