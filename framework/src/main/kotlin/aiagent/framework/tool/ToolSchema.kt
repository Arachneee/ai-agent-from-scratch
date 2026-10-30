package aiagent.framework.tool

import aiagent.framework.model.ChatRequest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

/**
 * 툴 입력 타입의 직렬화 설명자에서 `parameters` JSON 스키마를 만든다.
 * 클래스 필드마다 [Description]이 있어야 하고, 기본값이 없는 필드만 필수가 된다.
 * 설명자만으로 모양을 알 수 없는 다형 타입과 문맥 타입은 받지 않는다.
 */
fun toolParameters(descriptor: SerialDescriptor): ChatRequest.Parameters {
    require(descriptor.kind == StructureKind.CLASS) { "툴 입력은 클래스여야 합니다: ${descriptor.serialName}" }
    val (properties, required) = fields(descriptor)
    return ChatRequest.Parameters(type = "object", properties = properties, required = required)
}

@OptIn(ExperimentalSerializationApi::class)
private fun schema(descriptor: SerialDescriptor, description: String?): ChatRequest.Property {
    if (descriptor.isInline) return schema(descriptor.getElementDescriptor(0), description)
    return when (descriptor.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> ChatRequest.Property("string", description)
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
            ChatRequest.Property("integer", description)
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> ChatRequest.Property("number", description)
        PrimitiveKind.BOOLEAN -> ChatRequest.Property("boolean", description)
        SerialKind.ENUM -> ChatRequest.Property(
            "string", description, enum = (0 until descriptor.elementsCount).map(descriptor::getElementName),
        )
        StructureKind.LIST -> ChatRequest.Property("array", description, items = schema(descriptor.getElementDescriptor(0), null))
        StructureKind.MAP -> ChatRequest.Property(
            "object", description, additionalProperties = schema(descriptor.getElementDescriptor(1), null),
        )
        StructureKind.CLASS -> {
            val (properties, required) = fields(descriptor)
            ChatRequest.Property("object", description, properties = properties, required = required)
        }
        else -> throw IllegalArgumentException("툴 인자로 쓸 수 없는 타입입니다: ${descriptor.serialName} (${descriptor.kind})")
    }
}

private fun fields(descriptor: SerialDescriptor): Pair<Map<String, ChatRequest.Property>, List<String>> {
    val indices = 0 until descriptor.elementsCount
    val properties = indices.associate { i ->
        val name = descriptor.getElementName(i)
        val description = descriptor.getElementAnnotations(i).filterIsInstance<Description>().firstOrNull()?.value
            ?: throw IllegalArgumentException("${descriptor.serialName}.$name 필드에 @Description이 없습니다")
        name to schema(descriptor.getElementDescriptor(i), description)
    }
    val required = indices.filterNot(descriptor::isElementOptional).map(descriptor::getElementName)
    return properties to required
}
