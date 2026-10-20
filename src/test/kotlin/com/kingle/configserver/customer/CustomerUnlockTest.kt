package com.kingle.configserver.customer

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomerUnlockTest {
    private val verifier = AmaranthLoginVerifier("https://example.invalid")

    @Test
    fun `로그인 성공 응답은 access_token 이 있어야 통과`() {
        val check = verifier.parse("""{"state":"success","data":{"access_token":"abc"}}""")
        assertTrue(check.ok)
    }

    @Test
    fun `success 라도 토큰이 없으면 실패`() {
        assertFalse(verifier.parse("""{"state":"success","data":{}}""").ok)
    }

    @Test
    fun `실패 응답은 아마란스 실패 횟수를 안내`() {
        val body = """{"state":"error","message":"incorrect","error":{"errorType":1002,"failCount":2,"totalCount":5}}"""
        val check = verifier.parse(body)
        assertFalse(check.ok)
        assertTrue(check.message.contains("2/5"))
    }

    @Test
    fun `JSON 이 아니면 실패`() {
        assertFalse(verifier.parse("<html>").ok)
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
