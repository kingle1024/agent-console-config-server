package com.kingle.configserver.memo

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** P247 로 보낼 첨부 1건 — 바이트는 R2 공개 URL 에서 서버가 직접 받아온다. */
class MemoAttachment(val filename: String, val bytes: ByteArray)

/** P076 로그인으로 얻은 메신저 세션. token/hashKey 는 로그에 남기지 않는다(문서 01 §5). */
private class MessengerSession(
    val token: String,
    val hashKey: String,
    val empSeq: String,
    val compSeq: String,
    val bizSeq: String,
    val deptSeq: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 아마란스 메신저(P076 로그인 → P247 첨부 쪽지) 클라이언트.
 *
 * 왜 별도인가: 기존 /api/memo 가 쓰는 apiproxy(api02A03)는 ★첨부를 지원하지 않는다★.
 * 첨부까지 보내려면 메신저 세션(P076)으로 P247 multipart 를 써야 하고, 그러려면
 * DZEncrypt 로 미리 암호화한 로그인 자격(EncId/EncPwd/kNum)이 필요하다 —
 * 그래서 클라이언트가 아니라 서버(env)에 두고 여기서만 쓴다.
 *
 * 자격이 없으면 enabled=false → 호출측(MemoController)이 503 을 내고 앱은 링크 본문으로 폴백한다.
 * 근거: amaranth-api-docs-v2 01-auth.md / 02-note-send.md (2026-09-04 실측).
 */
@Component
class AmaranthMessenger(
    @Value("\${amaranth.messenger.login-id:}") private val loginId: String,
    @Value("\${amaranth.messenger.enc-id:}") private val encId: String,
    @Value("\${amaranth.messenger.enc-pwd:}") private val encPwd: String,
    @Value("\${amaranth.messenger.k-num:3}") private val kNum: String,
    @Value("\${amaranth.messenger.host:https://gwa.douzone.com}") private val host: String,
    @Value("\${amaranth.messenger.caller-name:NSM10}") private val callerName: String,
) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    // 세션 캐시 — 수명 미확인이라 20분만 재사용하고, 152(세션 죽음)면 딱 한 번 재로그인한다.
    // ★재로그인을 반복하면 같은 계정의 메신저 세션을 계속 밀어낸다★(문서 01 §5).
    private val lock = Any()
    private var cached: MessengerSession? = null
    private val sessionTtlMs = 20 * 60 * 1000L

    // ★appType 은 12★ — 11(모바일)·13(PC)로 로그인하면 사용자가 쓰던 메신저가 끊긴다(실측 확정).
    private val appType = "12"
    private val osType = "03"

    val enabled: Boolean
        get() = loginId.isNotBlank() && encId.isNotBlank() && encPwd.isNotBlank()

    /**
     * 첨부 포함 쪽지 발송. recvEmpSeq 는 ★사번★(로그인ID 가 아니다 — 문서 02 §2-2).
     * 반환은 (성공여부, 진단 문자열).
     */
    fun sendWithFiles(recvEmpSeq: List<String>, content: String, files: List<MemoAttachment>): Pair<Boolean, String> {
        check(enabled) { "messenger credentials not configured" }
        val first = send(session(false), recvEmpSeq, content, files)
        // 152 = 세션이 죽었거나 그 경로용 토큰이 아님 → 재로그인 후 한 번만 다시 시도.
        if (first.first || !first.second.contains("\"resultCode\":152")) return first
        return send(session(true), recvEmpSeq, content, files)
    }

    private fun send(
        s: MessengerSession,
        recvEmpSeq: List<String>,
        content: String,
        files: List<MemoAttachment>,
    ): Pair<Boolean, String> {
        val path = protocolPath("P247")
        val header = "{\"pId\":\"P247\",\"mobileId\":\"douzone\",\"loginId\":\"" + esc(loginId) + "\"," +
            "\"tId\":\"" + UUID.randomUUID() + "\",\"osType\":\"" + osType + "\",\"appType\":\"" + appType + "\"}"
        val inner = "{\"companyInfo\":{\"compSeq\":\"" + esc(s.compSeq) + "\",\"bizSeq\":\"" + esc(s.bizSeq) +
            "\",\"deptSeq\":\"" + esc(s.deptSeq) + "\"}," +
            "\"recvEmpSeq\":[" + recvEmpSeq.joinToString(",") { "\"" + esc(it) + "\"" } + "]," +
            "\"content\":\"" + esc(content) + "\",\"contentType\":\"1\",\"secuYn\":\"N\",\"receiptYn\":\"\"," +
            "\"reserveDate\":\"\",\"linkMsgId\":\"\",\"file\":[]}"
        val json = "{\"header\":" + header + ",\"body\":" + inner + "}"

        val boundary = "----AgentConsole" + UUID.randomUUID().toString().replace("-", "")
        val body = multipart(boundary, json, files)
        val tx = UUID.randomUUID().toString().replace("-", "")
        val ts = System.currentTimeMillis().toString()
        val req = HttpRequest.newBuilder(URI.create(host + path))
            .header("transaction-id", tx)
            .header("timestamp", ts)
            .header("Authorization", "Bearer " + s.token)
            .header("wehago-sign", hmacBase64(s.hashKey, s.token + tx + ts + path))
            .header("groupSeq", "duzon")
            .header("callerName", callerName)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val text = resp.body()
        val ok = resp.statusCode() == 200 && text.contains("\"resultCode\":0")
        return ok to ("P247 HTTP " + resp.statusCode() + " " + text.take(500))
    }

    /**
     * multipart 본문을 직접 만든다 — 순서와 형식이 중요하다(문서 02 §2-1).
     *  - 파트 순서: body(JSON) → pathSeq(810) → file* (첨부가 없으면 file 파트를 아예 뺀다)
     *  - ★각 파트에 Content-Transfer-Encoding 이 없으면 500★ (실측)
     *  - 파일명은 raw UTF-8 그대로 보낸다(httpmime BROWSER_COMPATIBLE 과 동일).
     *    RFC2047 로 인코딩하면 파일명 끝에 ___ 가 붙는다.
     */
    private fun multipart(boundary: String, json: String, files: List<MemoAttachment>): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(StandardCharsets.UTF_8))
        write("--" + boundary + "\r\n")
        write("Content-Disposition: form-data; name=\"body\"\r\n")
        write("Content-Type: application/json; charset=UTF-8\r\n")
        write("Content-Transfer-Encoding: 8bit\r\n\r\n")
        write(json)
        write("\r\n--" + boundary + "\r\n")
        write("Content-Disposition: form-data; name=\"pathSeq\"\r\n")
        write("Content-Type: application/octet-stream\r\n")
        write("Content-Transfer-Encoding: binary\r\n\r\n")
        write("810") // 실측 고정값
        for (f in files) {
            val name = f.filename.replace("\"", "_").replace("\r", "").replace("\n", "")
            write("\r\n--" + boundary + "\r\n")
            write("Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n")
            write("Content-Type: application/octet-stream\r\n")
            write("Content-Transfer-Encoding: binary\r\n\r\n")
            out.write(f.bytes)
        }
        write("\r\n--" + boundary + "--\r\n")
        return out.toByteArray()
    }

    private fun session(force: Boolean): MessengerSession {
        synchronized(lock) {
            val c = cached
            if (!force && c != null && System.currentTimeMillis() - c.createdAt < sessionTtlMs) return c
            val fresh = login()
            cached = fresh
            return fresh
        }
    }

    /** P076 로그인 — header.loginId 는 평문, body.loginId 는 암호화 값(가장 헷갈리는 지점, 문서 01 §4). */
    private fun login(): MessengerSession {
        val path = protocolPath("P076")
        val tk = getToken(path)
        val tx = tk.third
        val header = "{\"pId\":\"P076\",\"mobileId\":\"douzone\",\"loginId\":\"" + esc(loginId) + "\"," +
            "\"tId\":\"" + UUID.randomUUID() + "\",\"osType\":\"" + osType + "\",\"appType\":\"" + appType + "\"}"
        val inner = "{\"mobileId\":\"douzone\",\"loginId\":\"" + esc(encId) + "\",\"passwd\":\"" + esc(encPwd) + "\"," +
            "\"kNum\":\"" + esc(kNum) + "\",\"appType\":\"" + appType + "\",\"osType\":\"" + osType + "\"," +
            "\"deviceId\":\"AA-BB-CC-DD-EE-FF\",\"ipAddress\":\"127.0.0.1\",\"appVer\":\"1.4.407.0\"," +
            "\"fidoLoginYn\":\"N\",\"fidoType\":\"\",\"fidoSeq\":\"\",\"authToken\":\"\"," +
            "\"fidoPasswdLoginYn\":\"N\",\"ssoUUID\":\"\"}"
        val body = "{\"header\":" + header + ",\"body\":" + inner + "}"
        val req = HttpRequest.newBuilder(URI.create(host + path))
            .header("transaction-id", tx)
            .header("signature", sha256Base64(tk.first + tk.second + tx + path))
            .header("timestamp", System.currentTimeMillis().toString())
            .header("Content-Type", "application/json;charset=utf-8")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val text = resp.body()
        val code = jsonStr(text, "resultCode")
        if (code != "0") {
            error("P076 login failed (HTTP " + resp.statusCode() + ", resultCode=" + code + ")")
        }
        val token = jsonStr(text, "token").orEmpty()
        val hashKey = jsonStr(text, "hashKey").orEmpty()
        if (token.isBlank() || hashKey.isBlank()) error("P076 login response missing token/hashKey")
        return MessengerSession(
            token = token,
            hashKey = hashKey,
            empSeq = jsonStr(text, "empSeq").orEmpty(),
            compSeq = jsonStr(text, "compSeq").orEmpty().ifBlank { "6" },
            bizSeq = jsonStr(text, "bizSeq").orEmpty(),
            deptSeq = jsonStr(text, "deptSeq").orEmpty(),
        )
    }

    /**
     * 프로토콜 ID → URL 경로. ★코드에 박지 말 것★ — URL 의 숫자 순서가 protocolId 순서와 달라
     * 유추할 수 없고 그룹웨어가 바꿀 수 있다(문서 01 §4-①). 로그인과 무관한 목록이라 프로세스 수명 동안 캐시한다.
     */
    @Volatile
    private var protocolCache: Map<String, String> = emptyMap()

    private fun protocolPath(protocolId: String): String {
        protocolCache[protocolId]?.let { return it }
        val listPath = "/proxymgw/proxymgw01A01"
        val tk = getToken(listPath)
        val tx = tk.third
        val qs = "mobileId=douzone&loginId=" + urlEnc(loginId) +
            "&appType=13&appVer=1.4.407.0&osType=03&osVer=&model="
        val req = HttpRequest.newBuilder(URI.create(host + listPath + "?" + qs))
            .header("transaction-id", tx)
            .header("signature", sha256Base64(tk.first + tk.second + tx + listPath))
            .header("timestamp", System.currentTimeMillis().toString())
            .header("Content-Type", "application/json;charset=utf-8")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
            .build()
        val text = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body()
        val map = Regex("\"protocolId\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"protocolUrl\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(text)
            .associate { it.groupValues[1] to URI.create(it.groupValues[2]).path }
        if (map.isNotEmpty()) protocolCache = map
        return map[protocolId] ?: error("protocol " + protocolId + " not found in protocolList")
    }

    /** get_token → (token, cur_date, transactionId). transactionId 는 서명과 헤더에 ★같은 값★을 써야 한다. */
    private fun getToken(urlPath: String): Triple<String, String, String> {
        val tx = UUID.randomUUID().toString().replace("-", "")
        val req = HttpRequest.newBuilder(URI.create(host + "/get_token/?url=" + urlPath))
            .header("transaction-id", tx)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val text = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body()
        val token = jsonStr(text, "token") ?: error("get_token: no token")
        val curDate = jsonStr(text, "cur_date") ?: error("get_token: no cur_date")
        return Triple(token, curDate, tx)
    }

    // 필요한 값이 스칼라 몇 개뿐이라 정규식으로 뽑는다(키 이름이 응답 안에서 유일해 중첩 resultData 도 그대로 잡힌다).
    private fun jsonStr(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([0-9]+))")
            .find(json)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

    private fun esc(s: String) = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun urlEnc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun sha256Base64(data: String): String = Base64.getEncoder()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(data.toByteArray(StandardCharsets.UTF_8)))

    private fun hmacBase64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)))
    }
}
