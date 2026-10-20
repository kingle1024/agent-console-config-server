package com.kingle.configserver.customer

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

data class LoginCheck(val ok: Boolean, val message: String, val userId: String = "")

// 아마란스(그룹웨어) 아이디/비밀번호가 맞는지 genai 로그인으로 확인한다. 비밀번호는 전달만 하고 남기지 않는다.
//   ① POST {genai}/api/crypto/publickey            → RSA 공개키
//   ② POST {genai}/api/login {group_seq,user_id,password=RSA(aesKey).IV.AES(pw)}
//      성공 = access_token(JWT) 의 payload 에 그룹웨어 자격(auth_a_token) 이 들어 있음
// 근거: amaranth-api-docs-v2 samples/js/gwweb.js (AnywhereMD genai-auth.js 와 동일 방식)
@Component
class AmaranthLoginVerifier(
    @Value("\${customers.auth-base:https://genai.douzone.com}") private val authBase: String,
    @Value("\${customers.auth-group-seq:douzone}") private val groupSeq: String,
) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = ObjectMapper()
    private val random = SecureRandom()

    fun verify(userId: String, password: String): LoginCheck {
        val base = authBase.trimEnd('/')
        val publicKey = mapper.readTree(post("$base/api/crypto/publickey", "").body())
            .path("public_key").asString("")
        if (publicKey.isBlank()) {
            return LoginCheck(false, "아마란스 인증 서버에서 공개키를 받지 못했습니다.")
        }
        val body = mapper.writeValueAsString(
            mapOf(
                "group_seq" to groupSeq,
                "user_id" to userId,
                "password" to encryptPassword(password, publicKey),
                "auth_type" to null,
                "extra_options" to mapOf("type" to "fd", "version" to "1.0.0.0", "date" to System.currentTimeMillis().toString()),
            ),
        )
        val response = post("$base/api/login", body)
        return parse(response.statusCode(), response.body(), userId)
    }

    fun parse(status: Int, body: String, requestedUserId: String): LoginCheck {
        val root = runCatching { mapper.readTree(body) }.getOrNull()
        val token = root?.path("access_token")?.asString("").orEmpty()
        if (status == 200 && token.isNotBlank()) {
            val payload = jwtPayload(token) ?: return LoginCheck(false, "아마란스 로그인 응답을 읽지 못했습니다.")
            if (payload.path("auth_a_token").asString("").isBlank()) {
                return LoginCheck(false, "아마란스 로그인 응답에 그룹웨어 자격이 없습니다.")
            }
            return LoginCheck(true, "", payload.path("user_id").asString("").ifBlank { requestedUserId })
        }
        return LoginCheck(false, failMessage(root))
    }

    private fun failMessage(root: JsonNode?): String {
        val detail = root?.path("detail")
        val text = when {
            detail == null -> ""
            detail.isArray -> detail.path(0).path("msg").asString("")
            else -> detail.asString("")
        }
        return text.ifBlank { "아마란스 아이디 또는 비밀번호가 맞지 않습니다." }
    }

    private fun jwtPayload(token: String): JsonNode? {
        val parts = token.split(".")
        if (parts.size < 2) {
            return null
        }
        return runCatching { mapper.readTree(Base64.getUrlDecoder().decode(parts[1].trimEnd('='))) }.getOrNull()
    }

    // 전송 형식: RSA-OAEP(SHA-256)(aesKey).IV.AES-256-CBC(password) — 셋 다 대문자 HEX
    fun encryptPassword(password: String, publicKeyPem: String): String {
        val aesKey = ByteArray(32).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val encrypted = aes.doFinal(password.toByteArray(Charsets.UTF_8))
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        rsa.init(Cipher.ENCRYPT_MODE, readPublicKey(publicKeyPem), oaep)
        return hex(rsa.doFinal(aesKey)) + "." + hex(iv) + "." + hex(encrypted)
    }

    private fun readPublicKey(pem: String) = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").trim(),
            ),
        ),
    )

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    private fun post(url: String, json: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, Charsets.UTF_8))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }
}
