package com.kingle.configserver.network

import org.springframework.data.jpa.repository.JpaRepository

interface NetworkMemberRepository : JpaRepository<NetworkMember, Long> {
    fun findAllByOrderBySortOrderAscIdAsc(): List<NetworkMember>
}

interface NetworkServerRepository : JpaRepository<NetworkServer, Long> {
    fun findAllByOrderBySortOrderAscIdAsc(): List<NetworkServer>
}

interface NetworkServerPresetRepository : JpaRepository<NetworkServerPreset, Long> {
    fun findAllByPresetKeyOrderBySortOrderAscIdAsc(presetKey: String): List<NetworkServerPreset>
    fun deleteAllByPresetKey(presetKey: String)
}
