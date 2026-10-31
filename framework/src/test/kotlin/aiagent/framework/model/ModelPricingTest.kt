package aiagent.framework.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelPricingTest {
    @Test
    fun defaultModelHasPrice() {
        assertNotNull(ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 1_000, 0, 1_000))
    }

    @Test
    fun costIsLinearInTokens() {
        val one = ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 1_000_000, 0, 0)!!
        assertEquals(one * 2, ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 2_000_000, 0, 0)!!, 1e-9)
    }

    @Test
    fun unknownModelHasNoPrice() = assertNull(ModelPricing.costUsd("no-such-model", 1, 0, 1))

    @Test
    fun defaultModelIsCallable() =
        // gpt-4.1-nano는 모델 목록과 가격표에 남아 있지만 호출하면 404 model_not_found가 돌아온다(2026-10-30 확인).
        assertEquals("gpt-4o-mini", ModelPricing.DEFAULT_MODEL)

    @Test
    fun cachedTokensArePricedSeparatelyNotTwice() {
        val cost = ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 1_000_000, 400_000, 0)!!

        assertEquals((600_000 * 0.15 + 400_000 * 0.075) / 1_000_000, cost, 1e-12)
    }
}
