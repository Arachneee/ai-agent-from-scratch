package aiagent.shop.scenario

fun interface Responder {
    suspend fun respond(message: String): String
}
