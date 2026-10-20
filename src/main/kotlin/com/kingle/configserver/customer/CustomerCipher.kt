package com.kingle.configserver.customer

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// 고객사 접속정보 저장 암호화 — AES-256-GCM. 저장 형식 "v1:" + base64(IV 12바이트 + 암호문·태그).
//   키 = SHA-256(customers.data-key). ★키는 서버 환경변수 CUSTOMERS_DATA_KEY 에만★ 둔다(앱·DB 어디에도 없음).
//   키가 없으면 enabled=false — 저장·열람 API 는 503 으로 막는다(평문으로 쓰지 않는다).
//   키를 잃으면 복호화가 안 되지만 원본은 위키라 [위키 갱신] 한 번으로 다시 채울 수 있다.
@Component
class CustomerCipher(
    @Value("\${customers.data-key:}") private val dataKey: String,
) {
    private val random = SecureRandom()
    private val prefix = "v1:"
    private val key: SecretKeySpec? = dataKey.trim().takeIf { it.isNotEmpty() }?.let {
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)), "AES")
    }

    val enabled: Boolean
        get() = key != null

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireKey(), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return prefix + Base64.getEncoder().encodeToString(iv + sealed)
    }

    fun decrypt(stored: String): String {
        require(stored.startsWith(prefix)) { "암호화되지 않은 값" }
        val raw = Base64.getDecoder().decode(stored.removePrefix(prefix))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(128, raw, 0, 12))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private fun requireKey(): SecretKeySpec = key ?: error("CUSTOMERS_DATA_KEY 미설정")
}
