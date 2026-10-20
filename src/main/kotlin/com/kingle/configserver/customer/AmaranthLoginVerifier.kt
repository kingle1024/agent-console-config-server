package com.kingle.configserver.customer

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class LoginCheck(val ok: Boolean, val message: String)

// 아마란스(NSM10) 아이디/비밀번호가 맞는지 실제 로그인 API 로 확인한다. 비밀번호는 전달만 하고 남기지 않는다.
//   POST {base}/api/CM/AccountService/login  (form: userid, password, type=main)
//   성공 = state "success" + data.access_token, 실패 = state "error" + error.failCount/totalCount
@Component
class AmaranthLoginVerifier(
    @Value("\${customers.auth-base:https://nsm10.douzone.com}") private val authBase: String,
) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = ObjectMapper()

    fun verify(userId: String, password: String): LoginCheck {
        val form = listOf("userid" to userId, "password" to password, "type" to "main")
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI.create(authBase.trimEnd('/') + "/api/CM/AccountService/login"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(form, Charsets.UTF_8))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return parse(response.body())
    }

    fun parse(body: String): LoginCheck {
        val root = runCatching { mapper.readTree(body) }.getOrNull()
            ?: return LoginCheck(false, "아마란스 로그인 응답을 읽지 못했습니다.")
        val token = root.path("data").path("access_token").asString("")
        if (root.path("state").asString("") == "success" && token.isNotBlank()) {
            return LoginCheck(true, "")
        }
        return LoginCheck(false, failMessage(root.path("error")))
    }

    private fun failMessage(error: tools.jackson.databind.JsonNode): String {
        val failCount = error.path("failCount").asInt(0)
        val totalCount = error.path("totalCount").asInt(0)
        val base = "아이디 또는 비밀번호가 맞지 않습니다."
        if (failCount <= 0 || totalCount <= 0) {
            return base
        }
        return "$base (아마란스 로그인 실패 ${failCount}/${totalCount}회 — 초과 시 계정이 잠깁니다)"
    }
}
