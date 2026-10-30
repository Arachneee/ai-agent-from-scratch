package aiagent.framework.tool

import aiagent.framework.model.ChatRequest.Property
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolSchemaTest {
    @Serializable
    enum class Color { RED, BLUE }

    @Serializable
    data class Inner(@Description("이름") val name: String)

    @Serializable
    @JvmInline
    value class Sku(val value: String)

    @Serializable
    data class AllTypes(
        @SerialName("order_id") @Description("주문번호") val orderId: String,
        @Description("개수") val count: Int,
        @Description("금액") val amount: Long,
        @Description("비율") val ratio: Double,
        @Description("여부") val flag: Boolean,
        @Description("색") val color: Color,
        @Description("태그") val tags: List<String>,
        @Description("안쪽") val inner: Inner,
        @Description("재고") val stock: Map<String, Int>,
        @Description("상품 코드") val sku: Sku,
        @Description("메모") val memo: String? = null,
    )

    @Test
    fun eachTypeMapsToJsonSchema() {
        val parameters = toolParameters(serializer<AllTypes>().descriptor)
        val p = parameters.properties

        assertEquals("object", parameters.type)
        assertEquals(Property("string", "주문번호"), p["order_id"])
        assertEquals(Property("integer", "개수"), p["count"])
        assertEquals(Property("integer", "금액"), p["amount"])
        assertEquals(Property("number", "비율"), p["ratio"])
        assertEquals(Property("boolean", "여부"), p["flag"])
        assertEquals(Property("string", "색", enum = listOf("RED", "BLUE")), p["color"])
        assertEquals(Property("array", "태그", items = Property("string")), p["tags"])
        assertEquals(
            Property("object", "안쪽", properties = mapOf("name" to Property("string", "이름")), required = listOf("name")),
            p["inner"],
        )
        assertEquals(Property("object", "재고", additionalProperties = Property("integer")), p["stock"])
        assertEquals(Property("string", "상품 코드"), p["sku"])
        assertEquals(Property("string", "메모"), p["memo"])
    }

    @Test
    fun onlyFieldsWithoutDefaultAreRequired() {
        val parameters = toolParameters(serializer<AllTypes>().descriptor)

        assertEquals(
            listOf("order_id", "count", "amount", "ratio", "flag", "color", "tags", "inner", "stock", "sku"),
            parameters.required,
        )
    }

    @Serializable
    data class NoDescription(val orderId: String)

    @Serializable
    data class NestedNoDescription(@Description("안쪽") val inner: NoDescription)

    @Test
    fun missingDescriptionThrows() {
        val top = assertFailsWith<IllegalArgumentException> { toolParameters(serializer<NoDescription>().descriptor) }
        assertTrue("orderId" in top.message!!, top.message)
        assertFailsWith<IllegalArgumentException> { toolParameters(serializer<NestedNoDescription>().descriptor) }
    }

    @Serializable
    sealed interface Shape

    @Serializable
    data class WithPolymorphic(@Description("모양") val shape: Shape)

    @Test
    fun polymorphicTypeThrows() {
        assertFailsWith<IllegalArgumentException> { toolParameters(serializer<WithPolymorphic>().descriptor) }
    }

    @Test
    fun nonClassInputThrows() {
        assertFailsWith<IllegalArgumentException> { toolParameters(serializer<String>().descriptor) }
    }
}
