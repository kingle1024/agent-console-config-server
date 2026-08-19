package com.kingle.configserver.devprogress

import org.springframework.data.jpa.repository.JpaRepository

interface DevProgressRepository : JpaRepository<DevProgress, Long> {
    fun findByUserIdOrderByStartedAtDesc(userId: String): List<DevProgress>
    fun findByUserIdAndStatusOrderByStartedAtDesc(userId: String, status: String): List<DevProgress>
    fun findByStatusOrderByStartedAtDesc(status: String): List<DevProgress>
    fun findByDevNoOrderByStartedAtDesc(devNo: String): List<DevProgress>

    // 같은 사람이 같은 건을 다시 누른 경우(멱등 처리)·완료/재개 대상 찾기용.
    fun findByDevNoAndUserIdAndStatus(devNo: String, userId: String, status: String): List<DevProgress>
}
