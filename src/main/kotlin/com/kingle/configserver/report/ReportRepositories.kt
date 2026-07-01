package com.kingle.configserver.report

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional

interface ReportRepository : JpaRepository<Report, Long> {
    fun findAllByOrderByUpdatedAtDesc(): List<Report>
    fun findByReporterOrderByUpdatedAtDesc(reporter: String): List<Report>
}

interface ReportCommentRepository : JpaRepository<ReportComment, Long> {
    fun findByReportIdOrderByCreatedAtAsc(reportId: Long): List<ReportComment>
    fun countByReportId(reportId: Long): Long

    // 리포트 삭제 시 딸린 댓글 일괄 제거. 파생 delete 는 쓰기 트랜잭션이 필요해 @Transactional 명시.
    @Transactional
    fun deleteByReportId(reportId: Long)
}
