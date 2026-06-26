package com.kingle.configserver.report

import org.springframework.data.jpa.repository.JpaRepository

interface ReportRepository : JpaRepository<Report, Long> {
    fun findAllByOrderByUpdatedAtDesc(): List<Report>
    fun findByReporterOrderByUpdatedAtDesc(reporter: String): List<Report>
}

interface ReportCommentRepository : JpaRepository<ReportComment, Long> {
    fun findByReportIdOrderByCreatedAtAsc(reportId: Long): List<ReportComment>
    fun countByReportId(reportId: Long): Long
}
