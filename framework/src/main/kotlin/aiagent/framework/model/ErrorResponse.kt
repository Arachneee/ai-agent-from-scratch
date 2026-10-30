package aiagent.framework.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: ErrorDetail,
) {
    @Serializable
    data class ErrorDetail(
        val message: String,
        val type: String,
        val param: String?,
        val code: String?,
    )
}
