package com.kingle.configserver.customer

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class UnlockSession(val userId: String, val expiresAt: Instant)

// 아마란스 비밀번호 확인을 통과한 열람 세션(토큰) + 연속 실패 차단.
//  - 토큰은 메모리에만 둔다 — 서버 재시작 시 모두 잠기고 앱은 비밀번호를 다시 묻는다(의도된 동작).
//  - 연속 실패 차단은 아마란스 계정 잠금(5회) 전에 멈추게 하려는 것: 같은 아이디가 3번 틀리면 10분 쉰다.
@Component
class CustomerUnlockSessions {
    private val ttl: Duration = Duration.ofMinutes(30)
    private val failLimit = 3
    private val failWindow: Duration = Duration.ofMinutes(10)
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, UnlockSession>()
    private val failures = ConcurrentHashMap<String, MutableList<Instant>>()

    fun issue(userId: String, now: Instant = Instant.now()): Pair<String, UnlockSession> {
        purgeExpired(now)
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val session = UnlockSession(userId, now.plus(ttl))
        sessions[token] = session
        failures.remove(key(userId))
        return token to session
    }

    fun find(token: String?, now: Instant = Instant.now()): UnlockSession? {
        if (token.isNullOrBlank()) {
            return null
        }
        val session = sessions[token] ?: return null
        if (session.expiresAt.isAfter(now)) {
            return session
        }
        sessions.remove(token)
        return null
    }

    fun revoke(token: String?) {
        if (!token.isNullOrBlank()) {
            sessions.remove(token)
        }
    }

    fun isBlocked(userId: String, now: Instant = Instant.now()): Boolean =
        recentFailures(userId, now).size >= failLimit

    fun recordFailure(userId: String, now: Instant = Instant.now()) {
        val list = failures.computeIfAbsent(key(userId)) { mutableListOf() }
        synchronized(list) {
            list.add(now)
        }
    }

    private fun recentFailures(userId: String, now: Instant): List<Instant> {
        val list = failures[key(userId)] ?: return emptyList()
        synchronized(list) {
            list.removeIf { it.plus(failWindow).isBefore(now) }
            return list.toList()
        }
    }

    private fun purgeExpired(now: Instant) {
        sessions.entries.removeIf { !it.value.expiresAt.isAfter(now) }
    }

    private fun key(userId: String): String = userId.trim().lowercase()
}
