package com.kingle.configserver.firewall

import com.kingle.configserver.storage.R2Uploader
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

// OCR 진행 상태(firewall_request.consent_ocr_status)
object ConsentOcrStatus {
    const val PENDING = "PENDING" // 큐 대기/처리 중
    const val DONE = "DONE" // 접근 기간을 읽음
    const val NOT_FOUND = "NOT_FOUND" // 글자는 읽었지만 "접근 기간" 줄이 없음
    const val FAILED = "FAILED" // 다운로드/렌더/tesseract 실패
    const val NONE = "NONE" // 동의서 첨부 없음(내부 서버)
}

/**
 * 고객사동의서 접근 기간 OCR — 신청 생성 후 비동기로 돌려 firewall_request 에 결과를 채운다.
 *
 *  - 동의서 바이트는 R2 공개 URL 에서 받는다(업로드 시점이 아니라 신청 생성 후라 request ↔ 파일 연결이 확정돼 있다).
 *  - PDF 글자 레이어(워드 저장본)가 있으면 그대로, 없으면(복합기 스캔본) PDFBox 로 회색조 렌더 → tesseract CLI.
 *  - ★동시 1건(단일 스레드 큐)★ + tesseract 는 별도 프로세스(OMP_THREAD_LIMIT=1, 타임아웃) — 소형 인스턴스에서
 *    OCR 이 다른 API(리포트·쪽지 프록시)를 굶기지 않게. 프로세스라 메모리도 끝나면 바로 반환된다.
 *  - 서버 재시작으로 큐가 날아가면 PENDING 으로 남은 건을 기동 시 다시 넣는다.
 */
@Service
class ConsentOcrService(
    private val requests: FirewallRequestRepository,
    private val files: FirewallFileRepository,
    private val r2: R2Uploader,
    @Value("\${ocr.tesseract-cmd:tesseract}") private val tesseractCmd: String,
    @Value("\${ocr.tessdata-dir:}") private val tessdataDir: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "consent-ocr").apply { isDaemon = true } }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val maxPages = 3 // 동의서는 1~2쪽. 긴 문서를 통째로 OCR 하지 않게 상한
    private val renderWidthPx = 1800f // A4 가로 약 220dpi — kor 모델이 숫자·한글을 안정적으로 읽는 해상도
    private val tesseractTimeoutSec = 240L // 소형 인스턴스(CPU 적음)에서 1쪽 OCR 이 수십 초~분 단위 — 비동기라 넉넉히
    private val imageExt = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp", "tif", "tiff")

    /** 큐에 넣는다. 상태는 즉시 PENDING 으로 기록(상세 화면이 "읽는 중"을 표시). */
    fun enqueue(requestId: Long) {
        requests.findById(requestId).ifPresent {
            it.consentOcrStatus = ConsentOcrStatus.PENDING
            requests.save(it)
        }
        executor.submit { runSafely(requestId) }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun resumePending() {
        requests.findByConsentOcrStatus(ConsentOcrStatus.PENDING).forEach { r -> executor.submit { runSafely(r.id!!) } }
    }

    private fun runSafely(requestId: Long) {
        val started = System.currentTimeMillis()
        val outcome = try {
            process(requestId)
        } catch (e: Exception) {
            log.warn("consent OCR 실패 #{}: {}", requestId, e.toString())
            OcrOutcome(ConsentOcrStatus.FAILED, e.toString())
        }
        val elapsed = System.currentTimeMillis() - started
        saveStatus(requestId, outcome.status, listOfNotNull(outcome.note, "${elapsed}ms").joinToString(" · "))
        log.info("consent OCR #{} {} ({}ms)", requestId, outcome.status, elapsed)
    }

    // 상태 + 진단 메모(실패 사유 / 항목을 못 찾았을 때 읽은 글자 일부 — 관리자가 원인을 보게).
    private data class OcrOutcome(val status: String, val note: String? = null)

    // 결과를 채우고 상태를 돌려준다(상태 저장은 runSafely 가 한 번에).
    private fun process(requestId: Long): OcrOutcome {
        val consent = files.findByRequestIdOrderByIdAsc(requestId).firstOrNull { it.kind == "consent" }
            ?: return OcrOutcome(ConsentOcrStatus.NONE)
        val text = extractText(download(r2.publicUrl(consent.objectKey)), consent.filename)
        val period = ConsentPeriodParser.parse(text)
            ?: return OcrOutcome(ConsentOcrStatus.NOT_FOUND, "읽은 글자: " + notFoundSnippet(text))
        val r = requests.findById(requestId).orElse(null) ?: return OcrOutcome(ConsentOcrStatus.FAILED, "신청 없음")
        r.consentPeriod = period.raw.ifEmpty { null }
        r.consentEndDate = period.endDate
        requests.save(r)
        return OcrOutcome(ConsentOcrStatus.DONE)
    }

    // 진단용 글자 일부 — "기간" 이 보이면 그 주변(라벨이 깨진 모양 확인), 없으면 앞부분.
    private fun notFoundSnippet(text: String): String {
        val flat = text.replace(Regex("""\s+"""), " ")
        val at = flat.indexOf("기간").takeIf { it >= 0 } ?: flat.indexOf("기 간")
        val from = if (at > 150) at - 150 else 0
        return flat.substring(from).take(400)
    }

    private fun saveStatus(requestId: Long, status: String, note: String) {
        val r = requests.findById(requestId).orElse(null) ?: return
        r.consentOcrStatus = status
        r.consentOcrError = note.take(500)
        r.updatedAt = LocalDateTime.now()
        requests.save(r)
    }

    private fun download(url: String): ByteArray {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (res.statusCode() !in 200..299) throw IllegalStateException("동의서 다운로드 실패 HTTP ${res.statusCode()}")
        return res.body()
    }

    /** 동의서 파일에서 글자를 뽑는다. 지원하지 않는 형식(doc/hwp 등)은 "". */
    fun extractText(bytes: ByteArray, filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext in imageExt) return ocrImageBytes(bytes, ext)
        if (ext != "pdf") return ""
        return Loader.loadPDF(bytes).use { doc ->
            val layer = textLayer(doc)
            if (Regex("""\S{4,}""").containsMatchIn(layer)) layer else ocrPdf(doc)
        }
    }

    private fun textLayer(doc: PDDocument): String =
        PDFTextStripper().apply { endPage = minOf(doc.numberOfPages, maxPages) }.getText(doc).trim()

    private fun ocrPdf(doc: PDDocument): String {
        val renderer = PDFRenderer(doc)
        return (0 until minOf(doc.numberOfPages, maxPages)).joinToString("\n") { i ->
            val dpi = renderWidthPx / (doc.getPage(i).mediaBox.width / 72f)
            val image = renderer.renderImageWithDPI(i, dpi, ImageType.GRAY)
            withTempFile("png") { png ->
                ImageIO.write(image, "png", png.toFile())
                runTesseract(png)
            }
        }
    }

    private fun ocrImageBytes(bytes: ByteArray, ext: String): String =
        withTempFile(ext) { img ->
            Files.write(img, bytes)
            runTesseract(img)
        }

    // tesseract <이미지> <출력베이스> -l kor → <출력베이스>.txt (stdout 파이프 대신 파일 — 버퍼 교착 방지)
    private fun runTesseract(image: Path): String {
        val outBase = image.resolveSibling(image.fileName.toString() + "-out")
        val outTxt = Path.of("$outBase.txt")
        // preserve_interword_spaces=1 — 없으면 tesseract CLI 가 한글 글자마다 공백을 끼운다("서 비 스 종 료").
        val cmd = mutableListOf(
            tesseractCmd, image.toString(), outBase.toString(), "-l", "kor", "--psm", "3", "-c", "preserve_interword_spaces=1",
        )
        if (tessdataDir.isNotBlank()) cmd += listOf("--tessdata-dir", tessdataDir)
        val procLog = Path.of("$outBase.log")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(procLog.toFile())
        pb.environment()["OMP_THREAD_LIMIT"] = "1"
        val proc = pb.start()
        try {
            if (!proc.waitFor(tesseractTimeoutSec, TimeUnit.SECONDS)) throw IllegalStateException("tesseract 타임아웃(${tesseractTimeoutSec}초)")
            if (proc.exitValue() != 0) throw IllegalStateException("tesseract 종료코드 ${proc.exitValue()}: ${Files.readString(procLog).take(300)}")
            return Files.readString(outTxt)
        } finally {
            proc.destroyForcibly()
            Files.deleteIfExists(outTxt)
            Files.deleteIfExists(procLog)
        }
    }

    private fun <T> withTempFile(ext: String, block: (Path) -> T): T {
        val file = Files.createTempFile("consent-ocr-", ".$ext")
        try {
            return block(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
