package aiagent.shop.fault

sealed class ServiceException(message: String) : RuntimeException(message) {
    class Unavailable(service: ServiceName, operation: String) : ServiceException("$service.$operation 일시적 오류")

    class RateLimited(service: ServiceName, operation: String) : ServiceException("$service.$operation 호출 한도 초과")

    class NotFound(entity: String, id: String) : ServiceException("$entity $id 없음")

    class Rejected(reason: String) : ServiceException(reason)
}
