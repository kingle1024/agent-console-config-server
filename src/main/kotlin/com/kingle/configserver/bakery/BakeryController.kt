package com.kingle.configserver.bakery

import com.kingle.configserver.storage.R2Uploader
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

/**
 * 베이커리 월간 메뉴 발행 — 앱(관리자)이 PDF 를 파싱해 만든 매니페스트 JSON 을 서버가 R2 에 올린다.
 *
 * 방화벽 첨부와 같은 이유로 서버 프록시: R2 쓰기 키를 PC 에 두지 않고 ★서버 env 에만★ 둔다.
 * PDF 파싱(pdfjs)은 클라이언트에서 하고, 결과 JSON 만 multipart 로 보내 서버가 R2 에 PUT 한다.
 * 키는 서버가 year/month 로 정한다(`bakery/<YYYY-MM>.json`) — 임의 키 덮어쓰기 방지.
 * 읽기는 그대로 공개 URL(r2.dev, 무인증). 인증은 X-Api-Key(ReportApiSecurity) — 다른 API 와 동일.
 */
@RestController
@RequestMapping("/api/bakery")
class BakeryController(
    private val r2: R2Uploader,
) {
    @PostMapping("/publish", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun publish(
        @RequestParam year: Int,
        @RequestParam month: Int,
        @RequestParam("file") file: MultipartFile,
    ): Map<String, Any?> {
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "empty file")
        if (year < 2000 || year > 2100) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad year")
        if (month < 1 || month > 12) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad month")
        val ym = "%04d-%02d".format(year, month)
        val key = "bakery/$ym.json"
        r2.putObject(key, file.bytes, "application/json")
        return mapOf("ok" to true, "objectKey" to key, "publicUrl" to r2.publicUrl(key))
    }
}
