package com.kingle.configserver.firewall

import org.springframework.data.jpa.repository.JpaRepository

interface FirewallRequestRepository : JpaRepository<FirewallRequest, Long> {
    fun findAllByOrderByUpdatedAtDesc(): List<FirewallRequest>
    fun findByReporterOrderByUpdatedAtDesc(reporter: String): List<FirewallRequest>
}

interface FirewallFileRepository : JpaRepository<FirewallFile, Long> {
    fun findByRequestIdOrderByIdAsc(requestId: Long): List<FirewallFile>
    fun countByRequestId(requestId: Long): Long
}

interface FirewallApprovalRepository : JpaRepository<FirewallApproval, Long> {
    fun findTop1000ByOrderByCreatedAtDesc(): List<FirewallApproval>
}
