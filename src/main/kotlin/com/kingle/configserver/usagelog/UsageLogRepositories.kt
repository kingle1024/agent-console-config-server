package com.kingle.configserver.usagelog

import org.springframework.data.jpa.repository.JpaRepository

interface UsageLogRepository : JpaRepository<UsageLog, Long> {
    // 최근 1000건만 — 사용 로그는 에러로그보다 훨씬 많이 쌓이므로 응답 크기를 넉넉히(1000) 제한.
    fun findTop1000ByOrderByCreatedAtDesc(): List<UsageLog>
}
