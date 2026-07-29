package com.kingle.configserver.relay

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

// NSM DB 중계 노드 1대(= 포트포워딩/중계 서버를 열어둔 PC). 사용자당 1행 upsert.
// NSM DB 직접 접속 권한이 없는 자리가 "어디로 붙어야 하는지" 찾을 수 있게 앱이 주기적으로 등록(heartbeat)한다.
// 담기는 값은 사내 사설 IP·포트·로그인ID 뿐 — 자격증명은 담지 않는다.
@Entity
@Table(name = "relay_node", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id"])])
class RelayNode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 중계를 제공하는 사람의 로그인ID(= NSM userid). 같은 사람이 다시 등록하면 이 행을 갱신한다.
    @Column(name = "user_id", length = 100, nullable = false)
    var userId: String = "",

    @Column(name = "emp_nm", length = 100)
    var empNm: String? = null,

    // 접속 후보 주소 — "kind:ip" 를 '|' 로 이어붙인 문자열(예: "wired:10.105.1.53|wireless:10.106.14.160").
    // 회선(유선/무선)에 따라 IP 가 달라 후보를 여러 개 둔다.
    @Column(length = 500, nullable = false)
    var addrs: String = "",

    // 중계가 리슨하는 포트(netsh portproxy listenport / 앱 중계 servePort).
    @Column(nullable = false)
    var port: Int = 15253,

    // 중계 대상 DB 호스트 — 서로 다른 DB 를 가리키는 중계가 섞이지 않게 하는 식별용(선택).
    @Column(name = "target_host", length = 200)
    var targetHost: String? = null,

    @Column(name = "app_version", length = 60)
    var appVersion: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    // 마지막 heartbeat 시각 — 오래된 노드는 목록에서 죽은 것으로 표시된다.
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
