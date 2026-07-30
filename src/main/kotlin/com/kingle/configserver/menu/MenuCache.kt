package com.kingle.configserver.menu

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

// comet 모듈 1개의 메뉴명 캐시 메타. 실제 코드→메뉴명은 MenuName 행들.
// [Gitlab 등록] 이 프로젝트 설명("TR - 자금관리 - 자금수지관리 - 자료수집")을 자동으로 만들 때 쓴다.
// 원본은 stg comet MenuService(모듈별 트리) — 모듈당 한 번만 조회해 여기 담아두고 모두가 같이 쓴다.
// 사내 메뉴명뿐이라 자격증명·개인정보는 담기지 않는다.
@Entity
@Table(name = "menu_cache", uniqueConstraints = [UniqueConstraint(columnNames = ["module_cd"])])
class MenuCache(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 모듈코드(TR, FI …). 같은 모듈을 다시 올리면 이 행을 갱신한다.
    @Column(name = "module_cd", length = 20, nullable = false)
    var moduleCd: String = "",

    // 모듈 한글명(TR → 자금관리) — MenuService/Module 에서 얻는다.
    @Column(name = "module_nm", length = 200)
    var moduleNm: String? = null,

    // 담긴 메뉴 코드 수 — 캐시가 정상인지 눈으로 확인하는 용도.
    @Column(name = "name_cnt", nullable = false)
    var nameCnt: Int = 0,

    // 마지막으로 올린 사람(로그인ID) — 추적용(선택).
    @Column(name = "user_id", length = 100)
    var userId: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

// 메뉴 코드 1개 = 메뉴명 1개. (모듈, 코드) 유일.
// 계층은 코드 접두사로 표현된다 — TMFBDM00100 의 상위는 TMFBDM, 그 위는 TMF.
@Entity
@Table(
    name = "menu_name",
    uniqueConstraints = [UniqueConstraint(columnNames = ["module_cd", "menu_cd"])],
    indexes = [Index(name = "ix_menu_name_cd", columnList = "menu_cd")],
)
class MenuName(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "module_cd", length = 20, nullable = false)
    var moduleCd: String = "",

    @Column(name = "menu_cd", length = 60, nullable = false)
    var menuCd: String = "",

    @Column(name = "menu_nm", length = 300, nullable = false)
    var menuNm: String = "",
)
