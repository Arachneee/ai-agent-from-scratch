package aiagent.framework.model

/** 모델 API가 2xx가 아닌 상태 코드로 응답했다. 오류 본문이 JSON이 아니면 [error]는 null이다. */
class ChatApiException(
    val status: Int,
    val error: ErrorResponse?,
) : Exception("모델 API 오류 $status: ${error?.error?.message ?: "오류 본문을 해석하지 못했습니다"}")
