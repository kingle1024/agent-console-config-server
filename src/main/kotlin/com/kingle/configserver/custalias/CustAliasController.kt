package com.kingle.configserver.custalias

import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

data class CustAliasDto(
    val id: Long,
    val section: String,
    val labelNorm: String,
    val labelRaw: String?,
    val field: String,
    val part: String,
    val createdBy: String?,
    val updatedAt: String,
)

data class CustAliasSaveReq(
    val section: String? = null,
    val labelNorm: String? = null,
    val labelRaw: String? = null,
    val field: String? = null,
    val part: String? = null,
    val userId: String? = null,
)

// 고객사 접속정보 프리필 학습 사전 — 앱 [＋ 위키에 추가] 폼의 "미매칭 항목" 지정이 여기에 쌓인다.
// 한 사람이 지정한 라벨을 모두가 쓰도록 공유하는 것이 목적이라 별도 권한 없이 X-Api-Key 로만 보호한다.
@RestController
@RequestMapping("/api/custalias")
class CustAliasController(
    private val repo: CustAliasRepository,
) {
    // 전체 목록 — 앱이 시작/폼 오픈 시 한 번 받아 캐시한다(수십~수백 건 규모).
    @GetMapping
    fun list(): List<CustAliasDto> = repo.findAllByOrderBySectionAscLabelNormAsc().map { it.toDto() }

    // 등록/수정 — (section, labelNorm) 이 같으면 덮어쓴다(멱등).
    @PostMapping
    @Transactional
    fun save(@RequestBody req: CustAliasSaveReq): CustAliasDto {
        val labelNorm = req.labelNorm?.trim()?.lowercase().orEmpty()
        if (labelNorm.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "labelNorm 이 없습니다")
        }
        val section = req.section?.trim()?.lowercase().orEmpty()
        val now = LocalDateTime.now()
        // 중복 행이 생겼더라도(경합) 첫 행만 갱신하고 나머지는 지워 키를 하나로 되돌린다.
        val rows = repo.findBySectionAndLabelNorm(section, labelNorm)
        val row = rows.firstOrNull() ?: CustAlias(section = section, labelNorm = labelNorm, createdAt = now)
        if (rows.size > 1) repo.deleteAll(rows.drop(1))
        row.labelRaw = req.labelRaw?.trim()?.ifBlank { null } ?: row.labelRaw
        row.field = req.field?.trim().orEmpty() // "" = 무시 학습
        row.part = req.part?.trim()?.lowercase().orEmpty()
        row.createdBy = req.userId?.trim()?.ifBlank { null } ?: row.createdBy
        row.updatedAt = now
        return repo.save(row).toDto()
    }

    // 잘못 학습된 라벨 삭제 — 없는 id 여도 성공(멱등).
    @DeleteMapping("/{id}")
    @Transactional
    fun delete(@PathVariable id: Long): Map<String, Any?> {
        val exists = repo.existsById(id)
        if (exists) repo.deleteById(id)
        return mapOf("deleted" to if (exists) 1 else 0)
    }
}

private fun CustAlias.toDto() = CustAliasDto(
    id = id!!,
    section = section,
    labelNorm = labelNorm,
    labelRaw = labelRaw,
    field = field,
    part = part,
    createdBy = createdBy,
    updatedAt = updatedAt.toString(),
)
