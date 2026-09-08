package com.kingle.configserver.memo

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class MemoReq(
    val sender: String? = null,
    val recipients: List<String>? = null,
    val content: String? = null,
)

/** 첨부 1건 — 바이트는 서버가 R2 공개 URL 에서 직접 받아온다(앱이 다시 올리지 않는다). */
data class MemoFileReq(val filename: String? = null, val url: String? = null)

/**
 * 첨부 포함 쪽지 요청. recipients 는 로그인ID 인데 P247 은 ★사번★을 요구하므로,
 * 앱이 아는 사번(recipientEmpSeqs, loginId→사번)을 함께 받고 모자란 것은 서버 설정 맵으로 채운다.
 * 하나라도 못 채우면 409 → 앱이 링크 본문(api02A03)으로 폴백한다.
 */
data class MemoAttachReq(
    val sender: String? = null,
    val recipients: List<String>? = null,
    val recipientEmpSeqs: Map<String, String>? = null,
    val content: String? = null,
    val files: List<MemoFileReq>? = null,
)

// 첨부 상한 — 그룹웨어에 큰 파일을 밀어넣지 않도록 서버에서 잘라 둔다.
private const val MAX_FILES = 5
private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024

/**
 * 아마란스 쪽지 발송 프록시 — 서버가 보관한 토큰(apiproxy redis 에 등록된 값)으로 발신자 대신 보낸다.
 *
 * 왜 프록시인가:
 *  - 쪽지 게이트웨이(gwa /apiproxy/api02A03)는 NSM qrLogin 토큰을 받지 않고(152),
 *    별도 redis 에 등록된 토큰만 받는다. 그 토큰은 발신자에 묶이지 않아 누구 발신이든 통과한다.
 *  - 그 토큰을 클라이언트에 배포하지 않고 서버에만 두면(env), 만료 시 한 곳만 갱신하면 된다.
 *
 * 토큰은 환경변수 AMARANT_ACCESS_TOKEN / AMARANT_SECRET_KEY 로 주입(미설정이면 503).
 * 인증은 X-Api-Key(ReportApiSecurity) — 리포트/방화벽/에러로그와 동일.
 */
@RestController
@RequestMapping("/api/memo")
class MemoController(
    @Value("\${amaranth.access-token:}") private val accessToken: String,
    @Value("\${amaranth.secret-key:}") private val secretKey: String,
    @Value("\${amaranth.group-seq:duzon}") private val groupSeq: String,
    // 로그인ID → 사번(P247 recvEmpSeq). "ejy10241:503965,78alswo:123456" 형식.
    @Value("\${amaranth.messenger.emp-seq-map:}") private val empSeqMapRaw: String,
    // 첨부 다운로드를 허용할 출처 — 우리 R2 공개 URL 만(임의 URL 을 서버가 받아오지 않게 하는 SSRF 가드).
    @Value("\${r2.public-base:}") private val r2PublicBase: String,
    private val messenger: AmaranthMessenger,
) {
    private val http: HttpClient = HttpClient.newHttpClient()
    private val rnd = SecureRandom()

    @PostMapping
    fun send(@RequestBody req: MemoReq): Map<String, Any?> {
        if (accessToken.isBlank() || secretKey.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "amaranth token not configured (set AMARANT_ACCESS_TOKEN / AMARANT_SECRET_KEY)"
            )
        }
        val sender = req.sender?.trim().orEmpty()
        if (sender.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "sender required")
        val recipients = req.recipients?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (recipients.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "recipients required")
        val content = req.content.orEmpty()

        val path = "/apiproxy/api02A03"
        val tId = "adfsadfwerwerfcvcx"
        val tx = randomAlpha(30)
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sign = hmacBase64(secretKey, accessToken + tx + ts + path)
        val recvArr = recipients.joinToString(", ") { "\"" + esc(it) + "\"" }
        val body =
            """{"header":{"empSeq":"${esc(sender)}","groupSeq":"${esc(groupSeq)}","tId":"$tId","pId":""},""" +
                """"body":{"recvloginId":[$recvArr],"content":"${esc(content)}","contentType":"0","secuYn":"N","file":[],"callerName":"NSM10"}}"""

        val httpReq = HttpRequest.newBuilder(URI.create("https://gwa.douzone.com$path"))
            .header("empSeq", sender)
            .header("groupSeq", groupSeq)
            .header("tId", tId)
            .header("pId", "")
            .header("authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("timestamp", ts)
            .header("transaction-id", tx)
            .header("wehago-sign", sign)
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()
        val resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        val ok = resp.statusCode() == 200 && resp.body().contains("\"resultCode\":0")
        return mapOf("ok" to ok, "status" to resp.statusCode(), "body" to resp.body().take(600))
    }

    /**
     * 첨부 포함 쪽지(P247) — 리포트 등록·방화벽 신청처럼 파일이 함께 가야 하는 알림용.
     *
     * 이 경로가 못 쓰이는 상황(자격 미설정 503 / 수신자 사번 미상 409 / 첨부 없음 400)에서는
     * ★앱이 기존 /api/memo(본문에 다운로드 링크) 로 폴백★한다. 그래서 실패를 조용히 삼키지 않고
     * 상태코드로 구분해 돌려준다.
     */
    @PostMapping("/attach")
    fun sendWithFiles(@RequestBody req: MemoAttachReq): Map<String, Any?> {
        if (!messenger.enabled) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "messenger credentials not configured (set AMARANTH_MESSENGER_LOGIN_ID / _ENC_ID / _ENC_PWD / _K_NUM)"
            )
        }
        val recipients = req.recipients?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (recipients.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "recipients required")
        val files = req.files.orEmpty().filter { !it.url.isNullOrBlank() && !it.filename.isNullOrBlank() }
        if (files.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "files required")
        if (files.size > MAX_FILES) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "too many files (max $MAX_FILES)")

        // 수신자 로그인ID → 사번. 앱이 준 값이 우선, 없으면 서버 설정 맵. 하나라도 못 채우면 409(폴백 신호).
        val fromApp = req.recipientEmpSeqs.orEmpty().entries
            .associate { it.key.trim().lowercase() to it.value.trim() }
            .filterValues { it.isNotEmpty() }
        val empSeqs = recipients.map { id ->
            val key = id.lowercase()
            fromApp[key] ?: empSeqMap[key]
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "empSeq unknown for recipient '$id'")
        }

        // 첨부 바이트는 ★우리 R2 공개 URL 에서만★ 받아온다 — 임의 URL 을 서버가 대신 긁게 두지 않는다.
        val base = r2PublicBase.trimEnd('/')
        var total = 0L
        val loaded = files.map { f ->
            val url = f.url!!.trim()
            if (base.isEmpty() || !url.startsWith("$base/")) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "file url not allowed (must be under r2.public-base)")
            }
            val resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            )
            if (resp.statusCode() !in 200..299) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "attachment download failed (HTTP ${resp.statusCode()})")
            }
            total += resp.body().size
            if (total > MAX_TOTAL_BYTES) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "attachments too large")
            MemoAttachment(f.filename!!.trim(), resp.body())
        }

        val (ok, text) = messenger.sendWithFiles(empSeqs, req.content.orEmpty(), loaded)
        return mapOf("ok" to ok, "files" to loaded.size, "body" to text.take(600))
    }

    // "id:empSeq,id2:empSeq2" → { id(소문자) : empSeq }
    private val empSeqMap: Map<String, String> by lazy {
        empSeqMapRaw.split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val id = parts[0].trim().lowercase()
                val seq = parts[1].trim()
                if (id.isEmpty() || seq.isEmpty()) null else id to seq
            }
            .toMap()
    }

    private fun esc(s: String) = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun randomAlpha(n: Int): String {
        val al = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder(n)
        repeat(n) { sb.append(al[rnd.nextInt(al.length)]) }
        return sb.toString()
    }

    private fun hmacBase64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }
}
