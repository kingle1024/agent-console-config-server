package com.kingle.configserver.customer

import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomerUnlockTest {
    private val verifier = AmaranthLoginVerifier("https://example.invalid", "douzone")

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{}".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test
    fun `로그인 성공은 JWT 에 그룹웨어 자격이 있어야 통과`() {
        val token = jwt("""{"user_id":"ejy10241","auth_a_token":"abc","hash_key":"k"}""")
        val check = verifier.parse(200, """{"access_token":"$token"}""", "EJY10241")
        assertTrue(check.ok)
        assertEquals("ejy10241", check.userId)
    }

    @Test
    fun `토큰은 있어도 그룹웨어 자격이 없으면 실패`() {
        val token = jwt("""{"user_id":"ejy10241"}""")
        assertFalse(verifier.parse(200, """{"access_token":"$token"}""", "ejy10241").ok)
    }

    @Test
    fun `실패 응답은 서버 사유를 그대로`() {
        val check = verifier.parse(401, """{"detail":"계정이 잠겼습니다"}""", "x")
        assertFalse(check.ok)
        assertEquals("계정이 잠겼습니다", check.message)
        assertFalse(verifier.parse(401, """{"detail":[{"msg":"틀림"}]}""", "x").ok)
    }

    @Test
    fun `JSON 이 아니면 실패`() {
        assertFalse(verifier.parse(502, "<html>", "x").ok)
    }

    @Test
    fun `비밀번호 암호문은 RSA키·IV·AES 대문자 HEX 이고 복호화된다`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----"
        val parts = verifier.encryptPassword("p@ss워드", pem).split(".")
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.matches(Regex("[0-9A-F]+")) })
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(
            Cipher.DECRYPT_MODE,
            pair.private,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        val aesKey = rsa.doFinal(HexFormat.of().parseHex(parts[0]))
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(HexFormat.of().parseHex(parts[1])))
        assertEquals("p@ss워드", String(aes.doFinal(HexFormat.of().parseHex(parts[2])), Charsets.UTF_8))
    }

    @Test
    fun `저장 암호화는 왕복되고 평문이 남지 않는다`() {
        val cipher = CustomerCipher("test-key")
        val plain = """{"DB/PW":"secret!비번"}"""
        val sealed = cipher.encrypt(plain)
        assertTrue(sealed.startsWith("v1:"))
        assertFalse(sealed.contains("secret"))
        assertEquals(plain, cipher.decrypt(sealed))
        assertTrue(cipher.encrypt(plain) != sealed) // IV 가 매번 달라야
        assertFalse(runCatching { CustomerCipher("other-key").decrypt(sealed) }.isSuccess)
        assertFalse(CustomerCipher("").enabled)
    }

    @Test
    fun `토큰은 30분 뒤 만료`() {
        val sessions = CustomerUnlockSessions()
        val now = Instant.parse("2026-10-20T00:00:00Z")
        val (token, _) = sessions.issue("ejy10241", now)
        assertNotNull(sessions.find(token, now.plus(Duration.ofMinutes(29))))
        assertNull(sessions.find(token, now.plus(Duration.ofMinutes(31))))
        assertNull(sessions.find("nope", now))
    }

    @Test
    fun `3번 틀리면 10분 차단, 지나면 풀림`() {
        val sessions = CustomerUnlockSessions()
        val now = Instant.parse("2026-10-20T00:00:00Z")
        repeat(3) { sessions.recordFailure("EJY10241", now) }
        assertTrue(sessions.isBlocked("ejy10241", now.plusSeconds(60)))
        assertFalse(sessions.isBlocked("ejy10241", now.plus(Duration.ofMinutes(11))))
    }

    @Test
    fun `성공하면 실패 기록을 지운다`() {
        val sessions = CustomerUnlockSessions()
        val now = Instant.parse("2026-10-20T00:00:00Z")
        repeat(2) { sessions.recordFailure("ejy10241", now) }
        sessions.issue("ejy10241", now)
        sessions.recordFailure("ejy10241", now)
        assertFalse(sessions.isBlocked("ejy10241", now))
        sessions.revoke("x")
        assertEquals(false, sessions.isBlocked("other", now))
    }
}
