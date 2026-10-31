package aiagent.shop.scenario

import aiagent.framework.model.ModelPricing
import aiagent.shop.agent.ModelResponder
import kotlinx.coroutines.CancellationException

/** 확인 지점이다. [turn]은 1부터 센다. 그 턴의 답에 [accepted] 가운데 하나라도 들어 있으면 정답이다. */
data class Checkpoint(val turn: Int, val accepted: List<String>)

data class TurnMeasure(val turn: Int, val inputTokens: Int, val cachedTokens: Int, val costUsd: Double?, val error: String?)

/** [failed]의 문자열은 그 확인 지점 턴의 답이다. */
data class LongChatReport(val turns: List<TurnMeasure>, val passed: List<Checkpoint>, val failed: List<Pair<Checkpoint, String>>)

/**
 * 컨텍스트 관리 단계의 40턴 상담이다. 시드 42에서 ORD-0001은 상품 준비 중이고 출고 대기, ORD-0002는 배송 지연이다.
 * 고객은 처음 세 턴 안에 두 주문번호를 말하고, 30턴 이후의 확인 지점에서는 주문번호를 다시 말하지 않는다.
 * 마지막 발화는 툴이 필요 없는 일반 문의다.
 */
object LongChat {
    private val FIRST_ORDER = listOf("ORD-0001", "준비 중", "출고 대기")
    private val SECOND_ORDER = listOf("ORD-0002", "배송 지연", "지연")

    val messages: List<String> = listOf(
        "안녕하세요. 주문 두 건 문의드리려고요.",
        "주문번호는 ORD-0001이랑 ORD-0002예요.",
        "먼저 ORD-0001 지금 배송 어디쯤인지 알려 주세요.",
        "ORD-0002는요?",
        "배송이 늦어지면 보통 얼마나 더 걸리나요?",
        "택배사는 어디를 쓰세요?",
        "주말에도 배송되나요?",
        "배송지 주소를 바꾸고 싶으면 어떻게 하나요?",
        "공동현관 비밀번호는 어디에 적으면 되나요?",
        "부재중이면 택배는 어떻게 되나요?",
        "교환은 어떤 경우에 가능한가요?",
        "색상만 바꾸는 것도 교환이 되나요?",
        "교환 배송비는 누가 내나요?",
        "교환 신청하면 회수는 언제 오나요?",
        "포장을 뜯었으면 교환이 안 되나요?",
        "환불은 보통 며칠 걸려요?",
        "카드 결제는 환불이 어떻게 되나요?",
        "부분 환불도 가능한가요?",
        "쿠폰 쓴 주문은 환불하면 쿠폰이 돌아오나요?",
        "포인트로 결제한 금액은요?",
        "적립금은 언제 쌓이나요?",
        "회원 등급은 어떻게 올라가나요?",
        "영수증은 어디서 발급받나요?",
        "현금영수증도 되나요?",
        "선물 포장 서비스가 있나요?",
        "해외 배송도 하나요?",
        "품절된 상품은 재입고 알림을 받을 수 있나요?",
        "상품 리뷰는 어디에 쓰나요?",
        "고객센터 운영 시간이 어떻게 되나요?",
        "제가 처음에 문의한 주문이 몇 건이었죠?",
        "처음에 말한 첫 번째 주문은 지금 어떤 상태예요?",
        "그 주문은 교환하면 언제쯤 받을 수 있을까요?",
        "그 주문 말고 다른 하나도 같이 여쭤볼게요.",
        "아까 말한 두 번째 주문은 지금 어떤 상태예요?",
        "두 번째 주문이 계속 늦어지면 환불할 수 있나요?",
        "환불하면 배송비도 돌려주나요?",
        "첫 번째 주문은 아직 출발 안 했나요?",
        "그럼 첫 번째 주문은 취소도 가능할까요?",
        "두 번째 주문은 지금 어떤 이유로 늦어지는 거예요?",
        "알겠습니다. 정리해 주셔서 감사해요.",
        "마지막으로 앱 알림은 어디서 끄나요?",
    )

    val checkpoints: List<Checkpoint> = listOf(
        Checkpoint(31, FIRST_ORDER),
        Checkpoint(34, SECOND_ORDER),
        Checkpoint(37, FIRST_ORDER),
        Checkpoint(39, SECOND_ORDER),
    )
}

/**
 * [messages]를 차례로 보내고 턴마다 입력 토큰과 캐시 토큰과 비용을 찍은 뒤 요약을 찍는다.
 * 턴의 입력 토큰은 그 턴에 보낸 요청들의 입력 토큰 합이다. 한 턴이 실패해도 멈추지 않고 그 턴을 오류로 표시한 뒤 다음 발화로 넘어간다.
 */
suspend fun runLongChat(
    responder: ModelResponder,
    messages: List<String>,
    checkpoints: List<Checkpoint>,
    out: (String) -> Unit,
): LongChatReport {
    val turns = mutableListOf<TurnMeasure>()
    val replies = mutableListOf<String>()
    messages.forEachIndexed { index, message ->
        val reply = try {
            responder.respond(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "[오류] ${e::class.simpleName}: ${e.message}"
        }
        val usages = responder.lastUsages
        val costs = usages.map { it.costUsd }
        val measure = TurnMeasure(
            turn = index + 1,
            inputTokens = usages.sumOf { it.inputTokens },
            cachedTokens = usages.sumOf { it.cachedTokens },
            costUsd = if (null in costs) null else costs.sumOf { it!! },
            error = reply.takeIf { it.startsWith("[모델 오류]") || it.startsWith("[오류]") },
        )
        turns += measure
        replies += reply
        out("[턴 ${measure.turn}] 입력 ${measure.inputTokens} (캐시 ${measure.cachedTokens}) · ${costText(measure.costUsd)}" + (measure.error?.let { " · $it" } ?: ""))
    }
    val (passed, failed) = checkpoints.partition { checkpoint -> checkpoint.accepted.any { it in replies[checkpoint.turn - 1] } }
    val report = LongChatReport(turns, passed, failed.map { it to replies[it.turn - 1] })
    val totalCost = turns.map { it.costUsd }.let { if (null in it) null else it.sumOf { cost -> cost!! } }
    out(
        "[요약] 마지막 턴 입력 ${turns.last().inputTokens} · 전체 입력 ${turns.sumOf { it.inputTokens }} " +
            "(캐시 ${turns.sumOf { it.cachedTokens }}) · ${costText(totalCost)}",
    )
    out("[요약] 확인 지점 ${passed.size}/${checkpoints.size} 정답")
    report.failed.forEach { (checkpoint, reply) -> out("[오답] 턴 ${checkpoint.turn}: $reply") }
    return report
}

private fun costText(costUsd: Double?): String =
    costUsd?.let { "약 %.4f원".format(it * ModelPricing.KRW_PER_USD) } ?: "가격 미등록"
