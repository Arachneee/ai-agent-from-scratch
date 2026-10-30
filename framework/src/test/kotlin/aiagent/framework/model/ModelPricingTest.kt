package aiagent.framework.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelPricingTest {
    @Test
    fun defaultModelHasPrice() {
        assertNotNull(ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 1_000, 1_000))
    }

    @Test
    fun costIsLinearInTokens() {
        val one = ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 1_000_000, 0)!!
        assertEquals(one * 2, ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 2_000_000, 0)!!, 1e-9)
    }

    @Test
    fun unknownModelHasNoPrice() = assertNull(ModelPricing.costUsd("no-such-model", 1, 1))
}
