package com.kingle.configserver.customer

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

data class CustomerDto(val name: String, val fields: Map<String, String>)

data class CustomerUnlockReq(val userId: String? = null, val password: String? = null)

data class CustomerSyncReq(
    val customers: List<CustomerDto>? = null,
    val source: String? = null,
    // 건수가 절반 아래로 줄어드는 동기화는 위키 파싱 사고로 보고 막는다 — 정말 지운 거면 force=true.
    val force: Boolean? = null,
)

// 고객사 접속정보 — 이름 목록은 누구나(X-Api-Key), 내용은 아마란스 비밀번호를 확인한 세션만.
//
//   GET    /api/customers/names   이름 목록(자격증명 없음)
//   POST   /api/customers/unlock  {userId,password} → 아마란스 로그인 확인 → 30분 열람 토큰
//   DELETE /api/customers/unlock  토큰 폐기(잠그기)
//   GET    /api/customers         전체 내용 (X-Customer-Token)
//   PUT    /api/customers         위키에서 다시 읽은 전체 목록으로 교체 (X-Customer-Token)
@RestController
@RequestMapping("/api/customers")
class CustomerController(
    private val repo: CustomerInfoRepository,
    private val accessLogs: CustomerAccessLogRepository,
    private val verifier: AmaranthLoginVerifier,
    private val sessions: CustomerUnlockSessions,
) {
    private val mapper = ObjectMapper()
    private val fieldsType = object : TypeReference<Map<String, String>>() {}

    @GetMapping("/names")
    fun names(): Map<String, Any?> {
        val rows = repo.findAllByOrderByNameAsc()
        return mapOf(
            "count" to rows.size,
            "updatedAt" to rows.maxOfOrNull { it.updatedAt }?.toString(),
            "names" to rows.map { it.name },
        )
    }

    @PostMapping("/unlock")
    fun unlock(@RequestBody req: CustomerUnlockReq): Map<String, Any?> {
        val userId = req.userId?.trim().orEmpty()
        val password = req.password.orEmpty()
        if (userId.isEmpty() || password.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "아마란스 아이디와 비밀번호를 입력해 주세요.")
        }
        if (sessions.isBlocked(userId)) {
            log(userId, "unlock_blocked", null)
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "비밀번호를 연속으로 틀려 10분 동안 확인을 멈춥니다(아마란스 계정 잠금 방지).",
            )
        }
        val check = runCatching { verifier.verify(userId, password) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "아마란스 로그인 서버에 연결하지 못했습니다: ${it.message}")
        }
        if (!check.ok) {
            sessions.recordFailure(userId)
            log(userId, "unlock_fail", check.message)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, check.message)
        }
        val (token, session) = sessions.issue(userId)
        log(userId, "unlock_ok", null)
        return mapOf("token" to token, "userId" to userId, "expiresAt" to session.expiresAt.toString())
    }

    @DeleteMapping("/unlock")
    fun lock(@RequestHeader("X-Customer-Token", required = false) token: String?): Map<String, Any?> {
        sessions.revoke(token)
        return mapOf("ok" to true)
    }

    @GetMapping
    fun list(@RequestHeader("X-Customer-Token", required = false) token: String?): Map<String, Any?> {
        val session = requireSession(token)
        val rows = repo.findAllByOrderByNameAsc()
        log(session.userId, "list", "${rows.size}건")
        return mapOf(
            "count" to rows.size,
            "updatedAt" to rows.maxOfOrNull { it.updatedAt }?.toString(),
            "expiresAt" to session.expiresAt.toString(),
            "customers" to rows.map { CustomerDto(it.name, readFields(it.fieldsJson)) },
        )
    }

    @PutMapping
    @Transactional
    fun sync(
        @RequestHeader("X-Customer-Token", required = false) token: String?,
        @RequestBody req: CustomerSyncReq,
    ): Map<String, Any?> {
        val session = requireSession(token)
        val incoming = req.customers.orEmpty()
            .filter { it.name.isNotBlank() }
            .associateBy { it.name.trim() }
        if (incoming.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "고객사 목록이 비어 있습니다.")
        }
        val existing = repo.findAll().associateBy { it.name }
        if (req.force != true && incoming.size * 2 < existing.size) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "고객사가 ${existing.size}건 → ${incoming.size}건으로 절반 넘게 줄어듭니다. 위키 내용을 확인해 주세요.",
            )
        }
        val now = LocalDateTime.now()
        var changed = 0
        incoming.forEach { (name, dto) ->
            val json = mapper.writeValueAsString(dto.fields)
            val row = existing[name] ?: CustomerInfo(name = name)
            if (row.id != null && row.fieldsJson == json) {
                return@forEach
            }
            row.fieldsJson = json
            row.updatedBy = session.userId
            row.updatedAt = now
            repo.save(row)
            changed++
        }
        val removed = existing.values.filter { it.name !in incoming }
        repo.deleteAll(removed)
        log(session.userId, "sync", "${req.source.orEmpty()} 총 ${incoming.size}건, 변경 $changed, 삭제 ${removed.size}")
        return mapOf("count" to incoming.size, "changed" to changed, "removed" to removed.size)
    }

    private fun requireSession(token: String?): UnlockSession =
        sessions.find(token)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "아마란스 비밀번호 확인이 필요합니다.")

    private fun readFields(json: String): Map<String, String> =
        runCatching { mapper.readValue(json, fieldsType) }.getOrDefault(emptyMap())

    private fun log(userId: String, action: String, detail: String?) {
        accessLogs.save(CustomerAccessLog(userId = userId.take(100), action = action, detail = detail?.take(500)))
    }
}

// 거부 사유(한글)를 message 로 내려준다 — 앱이 비밀번호 창에 그대로 보여준다(CalendarErrorAdvice 와 같은 이유).
@RestControllerAdvice(assignableTypes = [CustomerController::class])
class CustomerErrorAdvice {
    @ExceptionHandler(ResponseStatusException::class)
    fun handle(e: ResponseStatusException): ResponseEntity<Map<String, Any?>> {
        val status = e.statusCode.value()
        val body = mapOf("status" to status, "error" to e.statusCode.toString(), "message" to (e.reason ?: ""))
        return ResponseEntity.status(status).body(body)
    }
}
