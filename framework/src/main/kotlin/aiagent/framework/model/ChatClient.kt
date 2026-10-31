package aiagent.framework.model

interface ChatClient {
    suspend fun chat(request: ChatRequest): Result<ChatResponse>

    /** 조각이 도착할 때마다 [onChunk]로 넘기고, 스트림이 끝나면 조립한 응답을 돌려준다. 실패하면 예외를 던진다. */
    suspend fun chatStream(request: ChatRequest, onChunk: (ChatChunk) -> Unit): ChatResponse
}
