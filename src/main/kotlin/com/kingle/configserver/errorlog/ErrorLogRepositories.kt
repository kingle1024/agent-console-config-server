package com.kingle.configserver.errorlog

import org.springframework.data.jpa.repository.JpaRepository

interface ErrorLogRepository : JpaRepository<ErrorLog, Long> {
    // 최근 500건만 — 모두 조회(전 사용자 공유). 오래된 건 잘라 응답 크기를 제한.
    fun findTop500ByOrderByCreatedAtDesc(): List<ErrorLog>
}
