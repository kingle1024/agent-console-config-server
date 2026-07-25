package com.kingle.configserver.market

import org.springframework.data.jpa.repository.JpaRepository

interface MarketItemRepository : JpaRepository<MarketItem, Long> {
    fun findByStatusOrderByCreatedAtDesc(status: String): List<MarketItem>
    fun findBySellerUserIdOrderByCreatedAtDesc(sellerUserId: String): List<MarketItem>
    fun existsByDevNoAndStatus(devNo: String, status: String): Boolean
}

interface MarketPurchaseRequestRepository : JpaRepository<MarketPurchaseRequest, Long> {
    fun findByItemIdInOrderByCreatedAtAsc(itemIds: Collection<Long>): List<MarketPurchaseRequest>
    fun findByItemIdOrderByCreatedAtAsc(itemId: Long): List<MarketPurchaseRequest>
    fun findByBuyerUserIdOrderByCreatedAtDesc(buyerUserId: String): List<MarketPurchaseRequest>
    fun existsByItemIdAndBuyerUserIdAndStatus(itemId: Long, buyerUserId: String, status: String): Boolean
}
