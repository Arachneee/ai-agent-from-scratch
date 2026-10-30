package aiagent.shop.service

import aiagent.shop.domain.Payment
import aiagent.shop.domain.ShopState
import aiagent.shop.fault.FaultInjector
import aiagent.shop.fault.ServiceException
import aiagent.shop.fault.ServiceName

/** 환불 금액 범위만 검사한다. 배송 상태에 따른 환불 정책은 일부러 검사하지 않는다. */
class PaymentService(private val state: ShopState, private val injector: FaultInjector) {
    suspend fun getPayment(orderId: String): Payment = injector.call(ServiceName.PAYMENT, "getPayment") { stale ->
        val current = state.payments[orderId] ?: throw ServiceException.NotFound("결제", orderId)
        if (stale) state.previousPayment(orderId) ?: current else current
    }

    suspend fun refund(orderId: String, amount: Long): Payment = injector.call(ServiceName.PAYMENT, "refund") {
        val payment = state.payments[orderId] ?: throw ServiceException.NotFound("결제", orderId)
        val refundable = payment.paidAmount - payment.refundedAmount
        if (amount < 1) throw ServiceException.Rejected("환불 금액은 1원 이상이어야 합니다: $amount")
        if (amount > refundable) throw ServiceException.Rejected("환불 가능 금액 ${refundable}원을 넘습니다: $amount")
        payment.copy(refundedAmount = payment.refundedAmount + amount).also(state::putPayment)
    }
}
