package com.kingle.configserver.customer

import com.kingle.configserver.menuadmin.MenuAdminRepository
import org.springframework.beans.factory.annotation.Value
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

data class CustomerDto(val name: String, val fields: Map<String, String>)

data class CustomerUnlockReq(val userId: String? = null, val password: String? = null)

// customer_setting 의 열람 잠금 키("true"/"false", 행 없으면 잠금 ON)
private const val LOCK_KEY = "lock_enabled"

data class CustomerLockReq(val enabled: Boolean? = null, val updatedBy: String? = null)

data class CustomerSyncReq(
    val customers: List<CustomerDto>? = null,
    val source: String? = null,
    // 건수가 절반 아래로 줄어드는 동기화는 위키 파싱 사고로 보고 막는다 — 정말 지운 거면 force=true.
    val force: Boolean? = null,
)

data class CustomerAccessLogDto(
    val userId: String,
    val action: String,
    val customerName: String?,
    val detail: String?,
    val createdAt: String,
)

// 고객사 접속정보 — 이름 목록은 누구나(X-Api-Key), 내용은 아마란스 비밀번호를 확인한 세션만 1건씩.
//
//   GET    /api/customers/names          이름 목록(자격증명 없음)
//   POST   /api/customers/unlock         {userId,password} → 아마란스(genai) 로그인 확인 → 30분 열람 토큰
//   DELETE /api/customers/unlock         토큰 폐기(잠그기)
//   GET    /api/customers/detail?name=   고객사 1건 내용 — ★열 때마다 (누가, 어느 고객사, 언제) 기록★
//   PUT    /api/customers                위키에서 다시 읽은 전체 목록으로 교체
//   GET    /api/customers/logs[?name=]   열람·변경 기록(최근 300건)
//   GET    /api/customers/lock           열람 잠금 설정 {enabled, updatedBy, updatedAt}
//   PUT    /api/customers/lock           {enabled, updatedBy} — 앱 [설정]의 슈퍼관리자 토글(게이팅은 앱)
//   (detail·PUT·logs 는 X-Customer-Token 필요)
//
// 열람 잠금이 꺼져 있으면(lock_enabled=false) unlock 이 비밀번호 확인 없이 토큰을 준다 — 권한 목록 확인과
// 열람 기록(누가·어느 고객사)은 그대로 남긴다. 기본값은 잠금 ON(행이 없을 때).
//
// 열람(unlock)은 [기타>관리자 설정]의 "고객사 정보 검색"(menu_admin.menu_key=customer_search)에 지정된 사람만 —
// ★슈퍼관리자도 목록에 있어야★ 하고, 목록이 비면 아무도 못 연다(목록에 "*" 가 있으면 전체 공개). 앱 메뉴 숨김만으론 API 직접 호출을 못 막으므로 서버에서도 확인하고,
// 열람 중 토큰도 요청마다 다시 확인해 목록에서 빠지면 그 즉시 막는다.
//
// 내용은 DB 에 AES-256-GCM 으로 암호화해 두고(CustomerCipher) detail 에서만 복호화한다.
// 전체 내용을 한 번에 내려주는 API 는 일부러 두지 않는다 — 누가 어떤 고객사를 봤는지 남길 수 없게 되므로.
@RestController
@RequestMapping("/api/customers")
class CustomerController(
    private val repo: CustomerInfoRepository,
    private val accessLogs: CustomerAccessLogRepository,
    private val verifier: AmaranthLoginVerifier,
    private val sessions: CustomerUnlockSessions,
    private val cipher: CustomerCipher,
    private val menuAdmins: MenuAdminRepository,
    private val settings: CustomerSettingRepository,
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
            "lockEnabled" to lockEnabled(),
        )
    }

    @GetMapping("/lock")
    fun lockSetting(): Map<String, Any?> {
        val row = settings.findById(LOCK_KEY).orElse(null)
        return mapOf(
            "enabled" to lockEnabled(),
            "updatedBy" to row?.updatedBy,
            "updatedAt" to row?.updatedAt?.toString(),
        )
    }

    @PutMapping("/lock")
    fun saveLockSetting(@RequestBody req: CustomerLockReq): Map<String, Any?> {
        val enabled = req.enabled
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled(true/false)가 필요합니다.")
        val by = req.updatedBy?.trim()?.lowercase()?.take(100)?.ifEmpty { null }
        settings.save(CustomerSetting(LOCK_KEY, enabled.toString(), by, LocalDateTime.now()))
        if (enabled) sessions.revokePasswordless() // 다시 켜면 비밀번호 없이 열린 세션은 바로 닫는다
        log(by ?: "-", if (enabled) "lock_on" else "lock_off")
        return lockSetting()
    }

    @PostMapping("/unlock")
    fun unlock(@RequestBody req: CustomerUnlockReq): Map<String, Any?> {
        val userId = req.userId?.trim().orEmpty()
        val password = req.password.orEmpty()
        if (!lockEnabled()) return unlockWithoutPassword(userId)
        if (userId.isEmpty() || password.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "아마란스 아이디와 비밀번호를 입력해 주세요.")
        }
        if (!allowed(userId)) {
            log(userId, "unlock_denied")
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "고객사 정보 열람 권한이 없습니다 — [기타>관리자 설정]의 '고객사 정보 검색'에 지정돼야 합니다.",
            )
        }
        if (sessions.isBlocked(userId)) {
            log(userId, "unlock_blocked")
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
            log(userId, "unlock_fail", detail = check.message)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, check.message)
        }
        val verifiedUserId = check.userId.ifBlank { userId }
        val (token, session) = sessions.issue(verifiedUserId)
        log(verifiedUserId, "unlock_ok")
        return mapOf("token" to token, "userId" to verifiedUserId, "expiresAt" to session.expiresAt.toString())
    }

    @DeleteMapping("/unlock")
    fun lock(@RequestHeader("X-Customer-Token", required = false) token: String?): Map<String, Any?> {
        sessions.revoke(token)
        return mapOf("ok" to true)
    }

    @GetMapping("/detail")
    fun detail(
        @RequestHeader("X-Customer-Token", required = false) token: String?,
        @RequestParam name: String,
    ): Map<String, Any?> {
        val session = requireSession(token)
        requireCipher()
        val row = repo.findByName(name.trim())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "'$name' 고객사가 서버에 없습니다.")
        val fields = runCatching { mapper.readValue(cipher.decrypt(row.fieldsJson), fieldsType) }.getOrElse {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "복호화 실패 — 서버 키가 바뀌었으면 [위키 갱신]으로 다시 채워 주세요.")
        }
        log(session.userId, "view", customerName = row.name)
        return mapOf(
            "name" to row.name,
            "fields" to fields,
            "updatedAt" to row.updatedAt.toString(),
            "expiresAt" to session.expiresAt.toString(),
        )
    }

    @PutMapping
    @Transactional
    fun sync(
        @RequestHeader("X-Customer-Token", required = false) token: String?,
        @RequestBody req: CustomerSyncReq,
    ): Map<String, Any?> {
        val session = requireSession(token)
        requireCipher()
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
            if (row.id != null && sameContent(row.fieldsJson, json)) {
                return@forEach
            }
            row.fieldsJson = cipher.encrypt(json)
            row.updatedBy = session.userId
            row.updatedAt = now
            repo.save(row)
            changed++
        }
        val removed = existing.values.filter { it.name !in incoming }
        repo.deleteAll(removed)
        log(session.userId, "sync", detail = "${req.source.orEmpty()} 총 ${incoming.size}건, 변경 $changed, 삭제 ${removed.size}")
        return mapOf("count" to incoming.size, "changed" to changed, "removed" to removed.size)
    }

    @GetMapping("/logs")
    fun logs(
        @RequestHeader("X-Customer-Token", required = false) token: String?,
        @RequestParam(required = false) name: String?,
    ): List<CustomerAccessLogDto> {
        requireSession(token)
        val rows = name?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { accessLogs.findTop300ByCustomerNameOrderByIdDesc(it) }
            ?: accessLogs.findTop300ByOrderByIdDesc()
        return rows.map { CustomerAccessLogDto(it.userId, it.action, it.customerName, it.detail, it.createdAt.toString()) }
    }

    // 열람 잠금 OFF — 비밀번호 확인만 건너뛴다(권한 목록 확인·토큰 30분·열람 기록은 잠금 ON 과 같다).
    private fun unlockWithoutPassword(userId: String): Map<String, Any?> {
        if (userId.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "아마란스 아이디가 필요합니다.")
        }
        if (!allowed(userId)) {
            log(userId, "unlock_denied")
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "고객사 정보 열람 권한이 없습니다 — [기타>관리자 설정]의 '고객사 정보 검색'에 지정돼야 합니다.",
            )
        }
        val id = userId.lowercase()
        val (token, session) = sessions.issue(id, passwordless = true)
        log(id, "unlock_nopw")
        return mapOf("token" to token, "userId" to id, "expiresAt" to session.expiresAt.toString())
    }

    private fun lockEnabled(): Boolean =
        settings.findById(LOCK_KEY).map { it.settingValue != "false" }.orElse(true)

    // 같은 내용이면 다시 쓰지 않는다(updatedAt·updatedBy 보존). 복호화가 안 되는 옛 값은 바뀐 것으로 본다.
    private fun sameContent(stored: String, json: String): Boolean =
        runCatching { cipher.decrypt(stored) == json }.getOrDefault(false)

    private fun allowed(userId: String): Boolean {
        val id = userId.trim().lowercase()
        if (id.isEmpty()) return false
        // 목록에 "*" 가 있으면 전체 공개(앱 [관리자 설정]의 "전체 공개" 체크) — 열람은 여전히 아마란스 비밀번호 확인을 거친다.
        return menuAdmins.existsByMenuKeyAndLoginId("customer_search", "*") ||
            menuAdmins.existsByMenuKeyAndLoginId("customer_search", id)
    }

    private fun requireSession(token: String?): UnlockSession {
        val session = sessions.find(token)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "아마란스 비밀번호 확인이 필요합니다.")
        if (!allowed(session.userId)) {
            sessions.revoke(token)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "고객사 정보 열람 권한이 해제됐습니다.")
        }
        return session
    }

    private fun requireCipher() {
        if (!cipher.enabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "서버 암호화 키(CUSTOMERS_DATA_KEY)가 설정되지 않았습니다.")
        }
    }

    private fun log(userId: String, action: String, customerName: String? = null, detail: String? = null) {
        accessLogs.save(
            CustomerAccessLog(
                userId = userId.take(100),
                action = action,
                customerName = customerName?.take(200),
                detail = detail?.take(500),
            ),
        )
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
