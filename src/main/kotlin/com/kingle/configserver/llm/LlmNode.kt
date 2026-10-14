package com.kingle.configserver.llm

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

// 로컬 LLM(Ollama) 공유 노드 1대 = LLM 게이트를 열어둔 PC. 사용자당 1행 upsert.
// 일일보고 문구 다듬기를 요청할 곳을 동료 앱이 찾을 수 있게 앱이 주기적으로 등록(heartbeat)한다.
// 담기는 값은 사내 사설 IP·포트·모델명·로그인ID 뿐 — 자격증명은 담지 않는다.
@Entity
@Table(name = "llm_node", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id"])])
class LlmNode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", length = 100, nullable = false)
    var userId: String = "",

    @Column(name = "emp_nm", length = 100)
    var empNm: String? = null,

    // 접속 후보 주소 — "kind:ip" 를 '|' 로 이어붙인 문자열(relay_node 와 같은 형식).
    @Column(length = 500, nullable = false)
    var addrs: String = "",

    // LLM 게이트가 리슨하는 포트.
    @Column(nullable = false)
    var port: Int = 11435,

    @Column(length = 100)
    var model: String? = null,

    @Column(name = "app_version", length = 60)
    var appVersion: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    // 마지막 heartbeat 시각 — 오래된 노드는 목록에서 죽은 것으로 표시된다.
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
