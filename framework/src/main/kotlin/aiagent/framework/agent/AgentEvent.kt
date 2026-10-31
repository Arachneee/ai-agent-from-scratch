package aiagent.framework.agent

/** 한 턴이 끝나기 전에 [Agent.run]이 바깥으로 알리는 진행 상황이다. */
sealed interface AgentEvent {
    /** 모델이 쓴 글자 조각. 도착한 순서대로 온다. */
    data class TextDelta(val text: String) : AgentEvent

    /** 툴을 실행하기 직전. 인자는 조립이 끝난 JSON 문자열이다. */
    data class ToolCallStarted(val name: String, val arguments: String) : AgentEvent
}
