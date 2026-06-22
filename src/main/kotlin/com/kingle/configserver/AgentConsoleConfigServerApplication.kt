package com.kingle.configserver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.config.server.EnableConfigServer

// Agent Console 데스크톱 앱이 사용할 DB 접속정보·인증값을 보관/제공하는 Spring Cloud Config Server.
// - 접근은 HTTP Basic 비밀번호로 막는다(맞아야 받음).
// - 값은 {cipher} 로 암호화 저장하고, 복호화 키(ENCRYPT_KEY)는 환경변수로만 주입한다.
@SpringBootApplication
@EnableConfigServer
class AgentConsoleConfigServerApplication

fun main(args: Array<String>) {
	runApplication<AgentConsoleConfigServerApplication>(*args)
}
