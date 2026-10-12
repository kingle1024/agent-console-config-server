package com.kingle.configserver.firewall

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 케이스 = 앱(v0.2.180) 파서 검증 케이스 + 실제 신청 동의서 7건의 OCR 원문 표기.
class ConsentPeriodParserTest {
    private fun check(text: String, raw: String, end: String?, service: Boolean) {
        assertEquals(ConsentPeriod(raw, end, service), ConsentPeriodParser.parse(text))
    }

    @Test
    fun `스캔본 OCR - 글자 사이 공백`() = check("6. 접 근 기 간 : 2022.09.26 ~2025.08.31", "2022.09.26 ~2025.08.31", "2025-08-31", false)

    @Test
    fun `서비스 종료 시까지`() = check("6. 접근 기간 : 2026.10.12 ~ 서비스 종료 시까지", "2026.10.12 ~ 서비스 종료 시까지", null, true)

    @Test
    fun `년월일 표기`() = check("접근기간: 2026년 10월 1일 ~ 2027년 3월 31일", "2026년 10월 1일 ~ 2027년 3월 31일", "2027-03-31", false)

    @Test
    fun `다음 줄로 넘어간 기간`() = check("6. 접근 기간 :\n2026-11-01 ~ 2029-12-31", "2026-11-01 ~ 2029-12-31", "2029-12-31", false)

    @Test
    fun `시작일만 있으면 종료일 없음`() = check("6. 접근 기간 : 2026.10.12 ~", "2026.10.12 ~", null, false)

    @Test
    fun `계약 종료 시까지`() = check("접근 기간 : 계약 종료 시까지", "계약 종료 시까지", null, true)

    @Test
    fun `두 자리 연도`() = check("사용기간 : 26.10.12 ~ 27.02.28", "26.10.12 ~ 27.02.28", "2027-02-28", false)

    @Test
    fun `까지 없는 종료시 + 앞 잡음`() = check("4. 접근기간 、 2026. 04. 10 ~ 종료시", "2026. 04. 10 ~ 종료시", null, true)

    @Test
    fun `OCR 오타 + 뒤 잡음`() = check("6. 접근 기간 : 서비스 종료시까저> |", "서비스 종료시까저", null, true)

    // 서버 tesseract CLI 실측 출력(글자마다 공백) — 다음 줄을 끌어오지 않아야 한다.
    @Test
    fun `글자마다 공백 - 서비스 종료`() =
        check("6. 접 근 기 간 : 서 비 스 종 료 시 까 지\n위 내 용 에 대 해 확 인 하 였 으 며", "서 비 스 종 료 시 까 지", null, true)

    @Test
    fun `글자마다 공백 - 종료시`() = check("6. 접근 기간 : 2026. 04. 10 ~ 종 료 시", "2026. 04. 10 ~ 종 료 시", null, true)

    @Test
    fun `라벨 없으면 null`() = assertNull(ConsentPeriodParser.parse("제목 없음 문서"))
}
