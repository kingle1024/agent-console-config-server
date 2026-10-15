package com.kingle.configserver.usagelog

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional
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

// 메뉴 사용 순위 한 줄 — (메뉴, 사용자)별 사용 횟수(시간대 단위)·호출 수·마지막 사용 시각.
interface MenuUsageRow {
    val menuLabel: String?
    val reporter: String?
    val sessions: Number // 그 메뉴를 쓴 서로 다른 "날짜+시" 수 — 한 시간 안 여러 호출은 1회
    val calls: Number
    val lastAt: Any?
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

// ★native 쿼리만 쓴다 — JPQL 은 기동 시 검증돼 오타 하나로 서버 전체가 안 뜬다.★
interface UsageMenuLabelRepository : JpaRepository<UsageLog, Long> {
    // 메뉴 라벨이 아직 없는 API 채널 목록(채우기 대상). menu:open 은 menu 칼럼이 곧 라벨이라 따로 채운다.
    @Query(nativeQuery = true, value = "SELECT DISTINCT api FROM usage_log WHERE menu_label IS NULL AND api <> 'menu:open'")
    fun unlabeledApis(): List<String>

    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = "UPDATE usage_log SET menu_label = :label WHERE api = :api AND menu_label IS NULL")
    fun fillLabel(@Param("api") api: String, @Param("label") label: String): Int

    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = "UPDATE usage_log SET menu_label = LEFT(menu, 100) WHERE api = 'menu:open' AND menu_label IS NULL AND menu IS NOT NULL")
    fun fillMenuOpenLabels(): Int

    @Query(
        nativeQuery = true,
        value = "SELECT menu_label AS menuLabel, reporter AS reporter, " +
            "COUNT(DISTINCT DATE_FORMAT(created_at, '%Y%m%d%H')) AS sessions, COUNT(*) AS calls, MAX(created_at) AS lastAt " +
            "FROM usage_log WHERE menu_label IS NOT NULL AND created_at >= :since GROUP BY menu_label, reporter",
    )
    fun rankByMenu(@Param("since") since: LocalDateTime): List<MenuUsageRow>

    // 잘못 채운 라벨 되돌리기 — 그 API·라벨이면서 cutoff 이전 행만(이후의 정확한 스탬프 로그는 보존).
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = "UPDATE usage_log SET menu_label = NULL WHERE api = :api AND menu_label = :label AND created_at < :before")
    fun resetLabel(@Param("api") api: String, @Param("label") label: String, @Param("before") before: LocalDateTime): Int
}
