package com.kingle.configserver.custalias

import org.springframework.data.jpa.repository.JpaRepository

interface CustAliasRepository : JpaRepository<CustAlias, Long> {
    fun findBySectionAndLabelNorm(section: String, labelNorm: String): List<CustAlias>
    fun findAllByOrderBySectionAscLabelNormAsc(): List<CustAlias>
}
