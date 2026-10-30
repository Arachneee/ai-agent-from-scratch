package aiagent.framework.model

interface ChatClient {
    suspend fun chat(request: ChatRequest): Result<ChatResponse>
}
