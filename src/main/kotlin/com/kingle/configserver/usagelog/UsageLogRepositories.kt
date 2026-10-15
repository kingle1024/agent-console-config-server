package com.kingle.configserver.usagelog

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

// 순위 집계 한 줄 — (키, 사용자)별 건수·마지막 사용 시각. 키는 메뉴 라벨(menu:open) 또는 API 채널명.
interface UsageRankRow {
    val rankKey: String?
    val reporter: String?
    val cnt: Number
    val lastAt: Any? // native 결과라 드라이버 타입(Timestamp/LocalDateTime) 그대로 — 문자열로만 쓴다
}

interface UsageLogRepository : JpaRepository<UsageLog, Long> {
    // 최근 1000건만 — 사용 로그는 에러로그보다 훨씬 많이 쌓이므로 응답 크기를 넉넉히(1000) 제한.
    fun findTop1000ByOrderByCreatedAtDesc(): List<UsageLog>

    // 메뉴 열기(api='menu:open') — 메뉴 라벨 × 사용자별 건수.
    // ★native 쿼리★ — JPQL 은 기동 시 검증돼 오타 하나로 서버 전체가 안 뜬다. native 는 호출 시에만 실패한다.
    @Query(
        nativeQuery = true,
        value = "SELECT menu AS rankKey, reporter AS reporter, COUNT(*) AS cnt, MAX(created_at) AS lastAt " +
            "FROM usage_log WHERE api = 'menu:open' AND created_at >= :since GROUP BY menu, reporter",
    )
    fun rankMenuOpens(@Param("since") since: LocalDateTime): List<UsageRankRow>

    // API 호출(메뉴 열기 이벤트 제외) — API 채널명 × 사용자별 건수. 메뉴 열기 수집 전 기간의 추정용.
    @Query(
        nativeQuery = true,
        value = "SELECT api AS rankKey, reporter AS reporter, COUNT(*) AS cnt, MAX(created_at) AS lastAt " +
            "FROM usage_log WHERE api <> 'menu:open' AND created_at >= :since GROUP BY api, reporter",
    )
    fun rankApiCalls(@Param("since") since: LocalDateTime): List<UsageRankRow>
}
