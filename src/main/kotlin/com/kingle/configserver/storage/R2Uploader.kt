package com.kingle.configserver.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cloudflare R2(S3 호환) 업로더 — 방화벽 첨부파일을 서버가 직접 R2 에 올린다.
 *
 * 왜 서버인가:
 *  - 쓰기 자격증명(AccessKeyId/Secret)을 클라이언트(앱)에 배포하지 않고 ★서버 환경변수에만★ 둔다.
 *    그러면 개발자 PC 마다 키를 입력/저장할 필요가 없고, 만료/교체 시 cloudtype env 한 곳만 갱신하면 된다.
 *  - 읽기는 여전히 공개 URL(r2.dev, 무인증)이라 앱은 objectKey 만으로 받아 본다.
 *
 * 설정(application.yml → 환경변수):
 *  - r2.account-id / r2.bucket / r2.public-base : 비밀 아님(공개 URL 자체가 누구나 읽으라고 만든 것). 기본값 내장.
 *  - r2.access-key-id / r2.secret-access-key    : ★비밀★. 환경변수 R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY 로만 주입.
 *
 * 서명은 AWS SigV4(region=auto, service=s3, path-style). 앱(electron r2.ts)의 putObject 를 그대로 포팅.
 */
@Component
class R2Uploader(
    @Value("\${r2.account-id:}") private val accountId: String,
    @Value("\${r2.bucket:}") private val bucket: String,
    @Value("\${r2.public-base:}") private val publicBase: String,
    @Value("\${r2.access-key-id:}") private val accessKeyId: String,
    @Value("\${r2.secret-access-key:}") private val secretAccessKey: String,
) {
    private val http: HttpClient = HttpClient.newHttpClient()

    /** R2 쓰기 자격증명이 서버에 설정돼 있는지. */
    fun isConfigured(): Boolean =
        accountId.isNotBlank() && bucket.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()

    /** 객체 키 → 공개 읽기 URL(앱과 동일 규칙: 세그먼트별 encodeURIComponent). */
    fun publicUrl(key: String): String =
        publicBase.trimEnd('/') + "/" + key.split("/").joinToString("/") { encURIComponent(it) }

    /** S3 PUT 을 SigV4 서명해 R2 에 올린다. path-style 경로 사용. */
    fun putObject(key: String, body: ByteArray, contentType: String) {
        if (!isConfigured()) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "R2 not configured on server (set R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY)"
            )
        }
        val region = "auto"
        val service = "s3"
        val s3Host = "$accountId.r2.cloudflarestorage.com"
        val amzdate = AMZ_DATE.format(Instant.now()) // YYYYMMDDTHHMMSSZ
        val datestamp = amzdate.substring(0, 8)
        val canonicalUri = "/$bucket/" + key.split("/").joinToString("/") { encURIComponent(it) }
        val payloadHash = sha256hex(body)
        val headers = sortedMapOf(
            "content-type" to contentType,
            "host" to s3Host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzdate,
        )
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val canonicalRequest = listOf("PUT", canonicalUri, "", canonicalHeaders, signedHeaders, payloadHash).joinToString("\n")
        val scope = "$datestamp/$region/$service/aws4_request"
        val stringToSign = listOf("AWS4-HMAC-SHA256", amzdate, scope, sha256hex(canonicalRequest.toByteArray(Charsets.UTF_8))).joinToString("\n")
        var signingKey = hmac(("AWS4$secretAccessKey").toByteArray(Charsets.UTF_8), datestamp)
        signingKey = hmac(signingKey, region)
        signingKey = hmac(signingKey, service)
        signingKey = hmac(signingKey, "aws4_request")
        val signature = hmac(signingKey, stringToSign).toHex()
        val authorization =
            "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

        // host 헤더는 HttpClient 가 URI 에서 자동 부여(서명값과 동일). content-length 도 자동.
        val req = HttpRequest.newBuilder(URI.create("https://$s3Host$canonicalUri"))
            .header("content-type", contentType)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzdate)
            .header("Authorization", authorization)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val resp: HttpResponse<String>
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "R2 연결 실패: ${e.message}")
        }
        val sc = resp.statusCode()
        if (sc == 401 || sc == 403) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "R2 인증 실패(HTTP $sc) — AccessKey/Secret 또는 버킷 권한 확인")
        }
        if (sc < 200 || sc >= 300) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "R2 업로드 오류(HTTP $sc): ${resp.body().take(200)}")
        }
    }

    private fun sha256hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    // JS encodeURIComponent 와 동일 규칙(unreserved 외 UTF-8 바이트 %XX). S3 키에 한글 파일명이 와도 안전.
    private fun encURIComponent(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c.toChar() in UNRESERVED) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    companion object {
        private const val UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        private val AMZ_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
