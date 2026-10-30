package aiagent.shop.cli

import aiagent.shop.fault.ServiceException
import aiagent.shop.scenario.Responder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTurnTest {
    @Test
    fun chatTurnSurvivesUnexpectedException() = runTest {
        val out = mutableListOf<String>()

        runChatTurn(Responder { throw IllegalStateException("깨짐") }, "안녕", out::add)

        assertEquals(listOf("[오류] IllegalStateException: 깨짐"), out)
    }

    @Test
    fun serviceExceptionIsShownAsServiceError() = runTest {
        val out = mutableListOf<String>()

        runChatTurn(Responder { throw ServiceException.NotFound("주문", "ORD-9999") }, "안녕", out::add)

        assertEquals(listOf("[서비스 오류] 주문 ORD-9999 없음"), out)
    }
}
