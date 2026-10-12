package com.kingle.configserver.firewall

import java.time.DateTimeException
import java.time.LocalDate

// 고객사동의서 "접근 기간" 파싱 결과. endDate 는 "YYYY-MM-DD" — 서비스 종료 시까지/못 읽음이면 null.
data class ConsentPeriod(val raw: String, val endDate: String?, val untilServiceEnd: Boolean)

// OCR/글자 레이어 텍스트에서 "접근 기간" 줄을 찾아 원문·종료일을 뽑는다(앱 v0.2.180 consent-ocr.ts 파서를 이식).
// 결재 종료일 1년 상한 계산은 앱 상세 화면이 적용 시작일 기준으로 한다 — 서버는 동의서에 적힌 값만 저장.
object ConsentPeriodParser {
    private val DATE_RE = Regex("""(\d{4}|\d{2})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})\s*일?""")

    // 실제 동의서 표기: "서비스 종료시까지" / "서비스종료일" / "2026. 04. 10 ~ 종료시" (OCR 오타 "종료시까저" 도 앞부분으로 잡힌다)
    // "서비스 조료시까쩌" — 워터마크가 겹친 6번 실측 OCR 오타(종→조)도 허용.
    private val SERVICE_END_RE = Regex("""(서비스|계약|유지보수|사용|이용)\s*([종조]료|만료|해지)|종료\s*시|별도\s*통보""")

    // OCR 이 글자 사이에 공백을 끼우므로(접 근 기 간) 공백을 지운 줄에서 라벨을 찾는다.
    private val LABEL_RE = Regex("""(접근|접속|사용|이용|허용|적용)기간""")

    // 원문 앞뒤 OCR 잡음("、 2026…", "…까저> |") 제거 — 글자·숫자로 시작하고 끝나게.
    private val EDGE_NOISE_RE = Regex("""^[^0-9A-Za-z가-힣]+|[^0-9A-Za-z가-힣.)~]+$""")

    fun parse(text: String?): ConsentPeriod? {
        val lines = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val idx = lines.indexOfFirst { LABEL_RE.containsMatchIn(it.replace(Regex("""\s+"""), "")) }
        val labeled = if (idx < 0) null else periodScope(lines, idx)
        if (labeled != null && hasPeriodValue(labeled)) return toPeriod(labeled)
        // 라벨 근처에서 값을 못 찾으면 문서 전체에서 찾는다 — tesseract 가 표를 단으로 나눠 읽어 라벨과 값이
        // 떨어져 나오거나(11번), 워터마크로 라벨 자체가 깨지는(6번) 실측 사례. 동의서에 기간 표기는 하나뿐이다.
        val fallback = lines.firstOrNull { it.contains('~') && findDates(it).size >= 2 } ?: lines.firstOrNull { isServiceEnd(it) }
        return (fallback ?: labeled)?.let { toPeriod(it) }
    }

    private fun hasPeriodValue(scope: String): Boolean = findDates(scope).isNotEmpty() || isServiceEnd(scope)

    private fun toPeriod(scope: String): ConsentPeriod {
        val dates = findDates(scope)
        val untilServiceEnd = isServiceEnd(scope)
        // 날짜가 1개뿐이면 시작일("2022.09.26 ~")로 보고 종료일은 비운다 — 결재 종료일은 1년 기본값.
        val endDate = if (untilServiceEnd || dates.size < 2) null else dates.last()
        val raw = scope.replace(Regex("""\s+"""), " ").replace(EDGE_NOISE_RE, "").take(300)
        return ConsentPeriod(raw, endDate, untilServiceEnd)
    }

    // 라벨 줄의 콜론 뒤 값 — 값에 날짜/종료 문구가 없으면 다음 줄까지 이어 본다(줄바꿈된 기간).
    private fun periodScope(lines: List<String>, idx: Int): String {
        val head = lines[idx]
        val colon = head.indexOfFirst { it == ':' || it == '：' }
        val body = (if (colon >= 0) head.substring(colon + 1) else head.replace(Regex("""^.*?기\s*간"""), "")).trim()
        if (findDates(body).isNotEmpty() || isServiceEnd(body)) return body
        return listOf(body, lines.getOrElse(idx + 1) { "" }).joinToString(" ")
    }

    // OCR 이 글자 사이에 공백을 끼워도("서 비 스 종 료 시") 잡히게 공백을 지우고 본다.
    private fun isServiceEnd(text: String): Boolean = SERVICE_END_RE.containsMatchIn(text.replace(Regex("""\s+"""), ""))

    private fun findDates(text: String): List<String> =
        DATE_RE.findAll(text).mapNotNull { toIsoDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()

    // "2025.08.31" / "2025년 8월 31일" / "25.08.31" → "2025-08-31". 달력에 없는 날짜는 null.
    private fun toIsoDate(y: String, m: String, d: String): String? {
        val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
        return try {
            LocalDate.of(year, m.toInt(), d.toInt()).toString()
        } catch (e: DateTimeException) {
            null
        }
    }
}
