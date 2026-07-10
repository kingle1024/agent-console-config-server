package com.kingle.configserver.meal

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MealItem(
    val corner: String,
    val name: String,
    val side: String,
    val kcal: Int,
    val thumbnailUrl: String,
)

data class MealResult(
    val morning: List<MealItem>,
    val lunch: List<MealItem>,
    val dinner: List<MealItem>,
)

/**
 * 오늘식단(cjfreshmeal) 프록시 + 서버측 캐시.
 *
 * 왜 서버 캐시인가:
 *  - 식단은 하루 단위로만 바뀐다. 각 앱 인스턴스가 개별로 cjfreshmeal 을 치는 대신
 *    서버가 캠퍼스·날짜별로 한 번만 가져와 전 사용자에게 공유하면 새로 켠 앱도 즉시 응답한다.
 *  - 앱은 이 프록시를 우선 쓰되, 서버 콜드스타트/다운 시 cjfreshmeal 직접 fetch 로 폴백한다(하이브리드).
 *
 * 인증은 X-Api-Key(ReportApiSecurity) — 리포트/방화벽/에러로그 등과 동일.
 */
@RestController
@RequestMapping("/api/meal")
class MealController {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .build()
    private val mapper = ObjectMapper()

    // 강촌(storeIdx=6486) / 을지(seoul, 5924)
    private val stores = mapOf("gangchon" to "6486", "eulji" to "5924")
    private val kstToday: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val seoul = ZoneId.of("Asia/Seoul")

    // 캠퍼스별 당일 캐시(프로세스 메모리). 컨테이너 재시작 시 소멸하지만 하루 단위라 무해.
    private data class Cached(val date: String, val meals: MealResult)
    private val cache = mutableMapOf<String, Cached>()

    @GetMapping
    fun today(
        @RequestParam(required = false) campus: String?,
        @RequestParam(required = false, defaultValue = "false") force: Boolean,
    ): Map<String, Any?> {
        val camp = if (campus == "eulji") "eulji" else "gangchon" // 기본 강촌
        val storeIdx = stores[camp] ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown campus")
        val today = LocalDate.now(seoul).format(kstToday)

        // 캐시 적중(같은 날) — force 가 아니면 그대로 반환
        if (!force) {
            val hit = synchronized(cache) { cache[camp] }
            if (hit != null && hit.date == today) {
                return mapOf("ok" to true, "campus" to camp, "meals" to hit.meals, "cached" to true)
            }
        }

        val meals = try {
            fetchOnce(storeIdx)
        } catch (e: Exception) {
            // 실패해도 같은 날 캐시가 있으면 폴백으로 반환
            val hit = synchronized(cache) { cache[camp] }
            if (hit != null && hit.date == today) {
                return mapOf("ok" to true, "campus" to camp, "meals" to hit.meals, "cached" to true)
            }
            return mapOf("ok" to false, "campus" to camp, "error" to (e.message ?: "조회 실패"))
        }
        synchronized(cache) { cache[camp] = Cached(today, meals) }
        return mapOf("ok" to true, "campus" to camp, "meals" to meals, "cached" to false)
    }

    private fun fetchOnce(storeIdx: String): MealResult {
        val url = "https://front.cjfreshmeal.co.kr/meal/v1/today-all-meal?storeIdx=$storeIdx"
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("accept", "application/json")
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw RuntimeException("HTTP ${resp.statusCode()}")
        }
        val data = mapper.readTree(resp.body()).path("data")
        // 1=조식, 2=중식, 3=석식
        return MealResult(pick(data, "1"), pick(data, "2"), pick(data, "3"))
    }

    private fun pick(data: JsonNode, key: String): List<MealItem> {
        val arr = data.path(key)
        if (!arr.isArray) return emptyList()
        val out = ArrayList<MealItem>()
        for (d in arr) {
            out.add(
                MealItem(
                    corner = d.path("corner").asString(""),
                    name = d.path("name").asString(""),
                    side = d.path("side").asString(""),
                    kcal = d.path("kcal").asInt(0),
                    thumbnailUrl = d.path("thumbnailUrl").asString(""),
                )
            )
        }
        return out
    }
}
