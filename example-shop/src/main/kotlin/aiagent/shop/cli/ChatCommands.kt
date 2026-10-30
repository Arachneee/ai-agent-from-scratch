package aiagent.shop.cli

import aiagent.shop.agent.ModelSettings

data class ChatCommandResult(val settings: ModelSettings, val message: String, val reset: Boolean = false)

const val CHAT_COMMAND_USAGE =
    "사용법: /model <이름>, /temperature <0.0~2.0|off>, /max-tokens <양의 정수|off>, /raw <on|off>, /reset, /settings, /quit"

/** 모델 채팅 중에 `/`로 시작하는 줄을 해석한다. 값이 잘못되면 설정을 바꾸지 않고 사용법을 돌려준다. */
fun applyChatCommand(line: String, settings: ModelSettings): ChatCommandResult {
    val parts = line.trim().split(Regex("\\s+"))
    val arg = parts.getOrNull(1)
    val usage = ChatCommandResult(settings, CHAT_COMMAND_USAGE)
    val changed = when (parts[0]) {
        "/model" -> arg?.let { settings.copy(model = it) }
        "/temperature" -> when (arg) {
            "off" -> settings.copy(temperature = null)
            else -> arg?.toDoubleOrNull()?.takeIf { it in 0.0..2.0 }?.let { settings.copy(temperature = it) }
        }
        "/max-tokens" -> when (arg) {
            "off" -> settings.copy(maxTokens = null)
            else -> arg?.toIntOrNull()?.takeIf { it > 0 }?.let { settings.copy(maxTokens = it) }
        }
        "/raw" -> when (arg) {
            "on" -> settings.copy(showRaw = true)
            "off" -> settings.copy(showRaw = false)
            else -> null
        }
        "/reset" -> return ChatCommandResult(settings, "대화 기록을 비웠습니다.", reset = true)
        "/settings" -> return ChatCommandResult(settings, describe(settings))
        else -> null
    } ?: return usage
    return ChatCommandResult(changed, describe(changed))
}

private fun describe(s: ModelSettings) =
    "설정: model=${s.model}, temperature=${s.temperature ?: "off"}, max_tokens=${s.maxTokens ?: "off"}, raw=${if (s.showRaw) "on" else "off"}"
