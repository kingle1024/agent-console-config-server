package com.kingle.configserver.menuadmin

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

// 메뉴별 관리자 1명 — 어떤 메뉴(menuKey)의 관리자 기능을 누가(loginId, 아마란스/NSM 로그인 ID) 쓸 수 있는지.
// FI개발도우미의 [기타>관리자 설정] 화면(슈퍼관리자 전용)에서 메뉴별로 편집하고, 모든 앱이 시작할 때 받아
// 메뉴 노출·관리자 버튼 노출·상태 변경 권한 판정에 쓴다. 사번이 아니라 ★로그인 ID 기준★(앱과 동일).
@Entity
@Table(name = "menu_admin")
class MenuAdmin(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 메뉴 식별 키(예: firewall, weekly_wiki). 앱의 MENU_ADMIN_TARGETS 키와 1:1.
    @Column(name = "menu_key", length = 60, nullable = false)
    var menuKey: String = "",

    // 관리자 로그인 ID(예: ejy10241). 소문자로 정규화해 저장한다.
    @Column(name = "login_id", length = 100, nullable = false)
    var loginId: String = "",

    // 표시/저장 순서(작을수록 위). 편집 화면의 입력 순서 보존용.
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    // 마지막으로 이 메뉴 목록을 저장한 사람(로그인 ID) — 감사용.
    @Column(name = "updated_by", length = 100)
    var updatedBy: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

interface MenuAdminRepository : JpaRepository<MenuAdmin, Long> {
    fun findAllByOrderByMenuKeyAscSortOrderAscIdAsc(): List<MenuAdmin>
    fun deleteAllByMenuKey(menuKey: String)
}
