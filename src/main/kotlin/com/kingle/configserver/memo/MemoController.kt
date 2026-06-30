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
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class MemoReq(
    val sender: String? = null,
    val recipients: List<String>? = null,
    val content: String? = null,
)

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
