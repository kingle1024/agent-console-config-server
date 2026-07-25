package com.kingle.configserver.market

import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

// ── DTO ──

data class MarketRequestDto(
    val id: Long,
    val itemId: Long,
    val buyerEmpNo: String,
    val buyerUserId: String,
    val buyerNm: String?,
    val status: String,
    val createdAt: String,
)

data class MarketItemDto(
    val id: Long,
    val devNo: String,
    val reqnNo: String?,
    val fileCd: String?,
    val fileNm: String?,
    val moduleNm: String?,
    val partnerNm: String?,
    val sellerEmpNo: String,
    val sellerUserId: String,
    val sellerNm: String?,
    val status: String,
    val buyerEmpNo: String?,
    val buyerUserId: String?,
    val buyerNm: String?,
    val createdAt: String,
    val updatedAt: String,
    // 활성(REQUESTED) + 처리된 요청 전부 — 선착순 표시를 위해 createdAt 오름차순.
    val requests: List<MarketRequestDto>,
)

// 내가 구매요청 탭 — 요청 + 대상 물건을 함께 내려준다.
data class MarketMyRequestDto(
    val request: MarketRequestDto,
    val item: MarketItemDto,
)

data class SellItemReq(
    val devNo: String? = null,
    val reqnNo: String? = null,
    val fileCd: String? = null,
    val fileNm: String? = null,
    val moduleNm: String? = null,
    val partnerNm: String? = null,
)

data class SellReq(
    val sellerEmpNo: String? = null,
    val sellerUserId: String? = null,
    val sellerNm: String? = null,
    val items: List<SellItemReq>? = null,
)

data class ActorReq(
    val userId: String? = null,
)

data class BuyReq(
    val buyerEmpNo: String? = null,
    val buyerUserId: String? = null,
    val buyerNm: String? = null,
)

// NSM 마켓 — 개발건(NSM)을 다른 개발자에게 넘기는 장터. X-Api-Key(ReportApiSecurity)로만 보호.
// 신원(userId/empNo)은 앱이 NSM 로그인 정보로 넣어준다(사내 도구 신뢰 모델 — 방화벽 이력과 동일).
@RestController
@RequestMapping("/api/market")
class MarketController(
    private val items: MarketItemRepository,
    private val requests: MarketPurchaseRequestRepository,
) {
    // 물건 목록 — seller 없으면 마켓 탭(판매중 전체), 있으면 내가 판매중 탭(취소 제외 전체 상태).
    @GetMapping("/items")
    fun listItems(@RequestParam(required = false) seller: String?): List<MarketItemDto> {
        val list =
            if (seller.isNullOrBlank()) {
                items.findByStatusOrderByCreatedAtDesc("ON_SALE")
            } else {
                items.findBySellerUserIdOrderByCreatedAtDesc(seller.trim()).filter { it.status != "CANCELED" }
            }
        val reqMap = requests.findByItemIdInOrderByCreatedAtAsc(list.mapNotNull { it.id }).groupBy { it.itemId }
        return list.map { it.toDto(reqMap[it.id].orEmpty()) }
    }

    // 판매 등록(일괄) — 이미 판매중(ON_SALE)인 devNo 는 건너뛰고 skipped 로 알려준다.
    @PostMapping("/items")
    fun sell(@RequestBody req: SellReq): Map<String, Any?> {
        val sellerUserId = req.sellerUserId?.trim().orEmpty()
        val sellerEmpNo = req.sellerEmpNo?.trim().orEmpty()
        if (sellerUserId.isEmpty() || sellerEmpNo.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "판매자 정보(sellerUserId/sellerEmpNo)가 없습니다 — NSM 로그인 필요")
        }
        val created = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (it in req.items.orEmpty()) {
            val devNo = it.devNo?.trim().orEmpty()
            if (devNo.isEmpty()) continue
            if (items.existsByDevNoAndStatus(devNo, "ON_SALE")) {
                skipped.add(devNo)
                continue
            }
            items.save(
                MarketItem(
                    devNo = devNo.take(40),
                    reqnNo = it.reqnNo?.trim()?.take(40)?.ifEmpty { null },
                    fileCd = it.fileCd?.trim()?.take(80)?.ifEmpty { null },
                    fileNm = it.fileNm?.trim()?.take(300)?.ifEmpty { null },
                    moduleNm = it.moduleNm?.trim()?.take(100)?.ifEmpty { null },
                    partnerNm = it.partnerNm?.trim()?.take(200)?.ifEmpty { null },
                    sellerEmpNo = sellerEmpNo.take(40),
                    sellerUserId = sellerUserId.take(100),
                    sellerNm = req.sellerNm?.trim()?.take(100)?.ifEmpty { null },
                ),
            )
            created.add(devNo)
        }
        return mapOf("created" to created, "skipped" to skipped)
    }

    // 판매 취소 — 판매자 본인 + ON_SALE 만. 걸려 있던 구매요청은 REJECTED 로 정리.
    @PostMapping("/items/{id}/cancel")
    @Transactional
    fun cancelItem(@PathVariable id: Long, @RequestBody body: ActorReq): Map<String, Any?> {
        val item = items.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "물건이 없습니다") }
        if (item.sellerUserId != body.userId?.trim().orEmpty()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "판매자 본인만 취소할 수 있습니다")
        }
        if (item.status != "ON_SALE") {
            throw ResponseStatusException(HttpStatus.CONFLICT, "판매중(ON_SALE) 상태가 아닙니다 (${item.status})")
        }
        item.status = "CANCELED"
        item.updatedAt = LocalDateTime.now()
        items.save(item)
        requests.findByItemIdOrderByCreatedAtAsc(id)
            .filter { it.status == "REQUESTED" }
            .forEach {
                it.status = "REJECTED"
                it.updatedAt = LocalDateTime.now()
                requests.save(it)
            }
        return mapOf("ok" to true)
    }

    // 구매요청 — 판매중 물건에만, 본인 물건 금지, 같은 사람 중복 요청 금지.
    @PostMapping("/items/{id}/requests")
    fun request(@PathVariable id: Long, @RequestBody body: BuyReq): Map<String, Any?> {
        val buyerUserId = body.buyerUserId?.trim().orEmpty()
        val buyerEmpNo = body.buyerEmpNo?.trim().orEmpty()
        if (buyerUserId.isEmpty() || buyerEmpNo.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "구매자 정보(buyerUserId/buyerEmpNo)가 없습니다 — NSM 로그인 필요")
        }
        val item = items.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "물건이 없습니다") }
        if (item.status != "ON_SALE") {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 판매완료/취소된 물건입니다 (${item.status})")
        }
        if (item.sellerUserId == buyerUserId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "본인이 판매중인 물건은 구매요청할 수 없습니다")
        }
        if (requests.existsByItemIdAndBuyerUserIdAndStatus(id, buyerUserId, "REQUESTED")) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 구매요청한 물건입니다")
        }
        val saved = requests.save(
            MarketPurchaseRequest(
                itemId = id,
                buyerEmpNo = buyerEmpNo.take(40),
                buyerUserId = buyerUserId.take(100),
                buyerNm = body.buyerNm?.trim()?.take(100)?.ifEmpty { null },
            ),
        )
        return mapOf("id" to saved.id)
    }

    // 승인 — 판매자가 구매요청 하나를 승인하면 물건은 SOLD, 나머지 요청은 REJECTED.
    // (실제 NSM 개발담당자 변경은 앱이 NSM DB 에 먼저 수행한 뒤 이 API 를 부른다.)
    @PostMapping("/requests/{id}/approve")
    @Transactional
    fun approve(@PathVariable id: Long, @RequestBody body: ActorReq): Map<String, Any?> {
        val req = requests.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "구매요청이 없습니다") }
        val item = items.findById(req.itemId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "물건이 없습니다") }
        if (item.sellerUserId != body.userId?.trim().orEmpty()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "판매자 본인만 승인할 수 있습니다")
        }
        if (item.status != "ON_SALE") {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 판매완료/취소된 물건입니다 (${item.status})")
        }
        if (req.status != "REQUESTED") {
            throw ResponseStatusException(HttpStatus.CONFLICT, "요청중(REQUESTED) 상태가 아닙니다 (${req.status})")
        }
        val now = LocalDateTime.now()
        req.status = "APPROVED"
        req.updatedAt = now
        requests.save(req)
        requests.findByItemIdOrderByCreatedAtAsc(item.id!!)
            .filter { it.id != req.id && it.status == "REQUESTED" }
            .forEach {
                it.status = "REJECTED"
                it.updatedAt = now
                requests.save(it)
            }
        item.status = "SOLD"
        item.buyerEmpNo = req.buyerEmpNo
        item.buyerUserId = req.buyerUserId
        item.buyerNm = req.buyerNm
        item.updatedAt = now
        items.save(item)
        return mapOf("ok" to true)
    }

    // 구매요청 취소 — 요청자 본인 + REQUESTED 만.
    @PostMapping("/requests/{id}/cancel")
    fun cancelRequest(@PathVariable id: Long, @RequestBody body: ActorReq): Map<String, Any?> {
        val req = requests.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "구매요청이 없습니다") }
        if (req.buyerUserId != body.userId?.trim().orEmpty()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "요청자 본인만 취소할 수 있습니다")
        }
        if (req.status != "REQUESTED") {
            throw ResponseStatusException(HttpStatus.CONFLICT, "요청중(REQUESTED) 상태가 아닙니다 (${req.status})")
        }
        req.status = "CANCELED"
        req.updatedAt = LocalDateTime.now()
        requests.save(req)
        return mapOf("ok" to true)
    }

    // 내가 구매요청 탭 — 내 요청(요청중/승인=구매완료/거절/취소) + 대상 물건.
    @GetMapping("/requests")
    fun myRequests(@RequestParam buyer: String): List<MarketMyRequestDto> {
        val mine = requests.findByBuyerUserIdOrderByCreatedAtDesc(buyer.trim())
        if (mine.isEmpty()) return emptyList()
        val itemMap = items.findAllById(mine.map { it.itemId }.distinct()).associateBy { it.id }
        return mine.mapNotNull { r ->
            val item = itemMap[r.itemId] ?: return@mapNotNull null
            MarketMyRequestDto(request = r.toDto(), item = item.toDto(emptyList()))
        }
    }
}

private fun MarketPurchaseRequest.toDto() = MarketRequestDto(
    id = id!!,
    itemId = itemId,
    buyerEmpNo = buyerEmpNo,
    buyerUserId = buyerUserId,
    buyerNm = buyerNm,
    status = status,
    createdAt = createdAt.toString(),
)

private fun MarketItem.toDto(reqs: List<MarketPurchaseRequest>) = MarketItemDto(
    id = id!!,
    devNo = devNo,
    reqnNo = reqnNo,
    fileCd = fileCd,
    fileNm = fileNm,
    moduleNm = moduleNm,
    partnerNm = partnerNm,
    sellerEmpNo = sellerEmpNo,
    sellerUserId = sellerUserId,
    sellerNm = sellerNm,
    status = status,
    buyerEmpNo = buyerEmpNo,
    buyerUserId = buyerUserId,
    buyerNm = buyerNm,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    requests = reqs.map { it.toDto() },
)
