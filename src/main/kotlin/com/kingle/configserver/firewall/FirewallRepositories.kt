package com.kingle.configserver.firewall

import org.springframework.data.jpa.repository.JpaRepository

interface FirewallRequestRepository : JpaRepository<FirewallRequest, Long> {
    fun findAllByOrderByUpdatedAtDesc(): List<FirewallRequest>
    fun findByReporterOrderByUpdatedAtDesc(reporter: String): List<FirewallRequest>
    fun findByConsentOcrStatus(status: String): List<FirewallRequest>
}

interface FirewallFileRepository : JpaRepository<FirewallFile, Long> {
    fun findByRequestIdOrderByIdAsc(requestId: Long): List<FirewallFile>
    fun countByRequestId(requestId: Long): Long
}

interface FirewallApprovalRepository : JpaRepository<FirewallApproval, Long> {
    fun findTop1000ByOrderByCreatedAtDesc(): List<FirewallApproval>
}

interface FirewallExpiryRepository : JpaRepository<FirewallExpiry, Long> {
    fun findTop500ByOrderByEndDateDesc(): List<FirewallExpiry>
    fun findByServiceNameAndDestIpAndEndDate(serviceName: String, destIp: String, endDate: String): FirewallExpiry?
}

interface FirewallCustomerEndRepository : JpaRepository<FirewallCustomerEnd, Long> {
    fun findByCustomer(customer: String): FirewallCustomerEnd?
}
