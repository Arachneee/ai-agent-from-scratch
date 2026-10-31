package aiagent.shop.cli

/**
 * 채팅 출력과 원문 로그가 함께 쓰는 콘솔이다. 줄바꿈 없이 찍은 글자 조각 뒤에 줄이 오면 먼저 줄을 바꾼다.
 * 두 출력이 이 상태를 공유해야 원문 로그가 답 끝에 붙지 않는다.
 */
class ConsoleOutput(private val print: (String) -> Unit) {
    private var midLine = false

    fun line(text: String) {
        if (midLine) print("\n")
        midLine = false
        print("$text\n")
    }

    fun fragment(text: String) {
        print(text)
        midLine = !text.endsWith("\n")
    }
}
