# 실험 환경 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 시드와 같은 장애 설정이면 같은 데이터와 같은 실패를 돌려주는 가짜 쇼핑몰 서비스, 터미널 채팅 화면, 최소한의 시나리오 실행기를 만든다.

**Architecture:** Gradle 다중 모듈로 `framework`와 `example-shop`을 나눈다. 이 단계에서 `framework`는 빈 모듈이고 모든 코드는 `example-shop`에 둔다. 가짜 서비스 네 개는 같은 프로세스 안의 메모리 상태를 공유하고, 모든 호출은 장애 주입기를 거친다. 모델 호출은 아직 없고, 정규식 기반의 기준 응답기가 채팅과 시나리오의 상대가 된다.

**Tech Stack:** Kotlin, Gradle Kotlin DSL과 Gradle 래퍼 9.6.0, JDK 21 툴체인, kotlinx-coroutines 1.10.2, kotlin-test와 JUnit 5, kotlinx-coroutines-test.

**Spec:** `docs/roadmap.md`의 "실험 환경" 절과 "예제 에이전트" 절.

## Global Constraints

- 언어는 Kotlin이고 런타임은 JDK 21이다. 로컬에는 JDK 17, 23, 25만 있으므로 Gradle 툴체인과 foojay 해석기 플러그인으로 JDK 21을 받는다.
- Gradle은 전역 설치본이 없다. `~/.gradle/wrapper/dists/gradle-9.6.0-bin/*/gradle-9.6.0/bin/gradle wrapper --gradle-version 9.6.0`으로 래퍼를 만든다.
- Kotlin 플러그인은 Gradle 9.6.0과 호환되는 최신 안정 버전을 쓰고, 버전은 `gradle/libs.versions.toml`에 한 번만 적는다.
- 이 단계에서는 Ktor, kotlinx.serialization, 모델 호출 코드를 추가하지 않는다.
- `framework` 모듈에 쇼핑몰 업무 개념을 넣지 않는다. 이 단계에서는 `framework`에 코드를 넣지 않는다.
- 고객 이름, 주문, 결제 데이터는 모두 가짜로 생성한다.
- 사용자에게 보이는 문구와 커밋 메시지는 한국어로 쓴다. 코드 식별자는 영어로 쓴다.
- 패키지 루트는 `aiagent.shop`이다.

## Review Focus

- 한 서비스에 호출을 추가해도 다른 서비스의 장애 순서가 바뀌지 않아야 한다. 서비스마다 별도의 난수 흐름을 쓴다. 테스트는 Task 3의 `extraCallsOnOneServiceDoNotShiftAnother`.
- `errorRate`가 0.0이면 절대 실패하지 않고 1.0이면 항상 실패해야 한다. 테스트는 Task 3의 `errorRateBoundaries`.
- 결제 금액을 넘는 환불이나 0 이하의 환불은 거부되어야 한다. 테스트는 Task 4의 `refundRejectsInvalidAmounts`.
- 이전 버전이 없는 엔티티에 오래된 읽기 장애가 걸리면 현재 값을 돌려줘야 한다. 테스트는 Task 4의 `staleReadWithoutHistoryReturnsCurrent`.
- 시나리오 하나가 예외로 실패해도 실행기 전체가 멈추지 않고, 시나리오마다 새 세계에서 시작해야 한다. 테스트는 Task 5의 `failingScenarioDoesNotStopRunAndStateIsFresh`.

---

### Task 1: 빌드 골격

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore`
- Create: `framework/build.gradle.kts`
- Create: `example-shop/build.gradle.kts`
- Create: 래퍼 파일 `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- Test: `example-shop/src/test/kotlin/aiagent/shop/BuildSmokeTest.kt`

**Interfaces:**
- Produces: `./gradlew test`로 두 모듈을 빌드하고 테스트한다. `example-shop`은 `implementation(project(":framework"))`와 `application` 플러그인을 쓰고, 메인 클래스는 `aiagent.shop.cli.MainKt`다.

- [ ] **Step 1: 래퍼를 만든다.** 위 Global Constraints의 명령으로 Gradle 9.6.0 래퍼를 만든다. 확인 명령은 `./gradlew --version`이고 `Gradle 9.6.0`이 출력되면 된다.
- [ ] **Step 2: 설정 파일을 만든다.** `settings.gradle.kts`에 foojay 해석기 플러그인과 `include("framework", "example-shop")`를 넣는다. 두 모듈 모두 `kotlin { jvmToolchain(21) }`를 쓰고 테스트는 `useJUnitPlatform()`으로 돌린다. `.gitignore`에는 `.gradle/`, `build/`, `.idea/`, `*.iml`, `.env`를 넣는다.
- [ ] **Step 3: 연기 테스트를 쓴다.**

```kotlin
class BuildSmokeTest {
    @Test
    fun runsOnJdk21() {
        assertEquals("21", System.getProperty("java.specification.version"))
    }
}
```

- [ ] **Step 4: 실행한다.** `./gradlew test`를 실행하고 `BUILD SUCCESSFUL`이 나오면 된다.
- [ ] **Step 5: 커밋한다.** 메시지는 `Gradle 다중 모듈 골격을 만든다`.

### Task 2: 도메인 모델과 시드 데이터 생성기

**Files:**
- Create: `example-shop/src/main/kotlin/aiagent/shop/domain/Models.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/domain/ShopState.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/data/ShopDataGenerator.kt`
- Test: `example-shop/src/test/kotlin/aiagent/shop/data/ShopDataGeneratorTest.kt`

**Interfaces:**
- Produces:
  - `data class Customer(val id: String, val name: String)`. 아이디 형식은 `CUS-001`이다.
  - `data class Product(val sku: String, val name: String, val price: Long)`. 아이디 형식은 `SKU-001`이고 가격 단위는 원이다.
  - `data class OrderItem(val sku: String, val quantity: Int, val unitPrice: Long)`
  - `data class Order(val id: String, val customerId: String, val items: List<OrderItem>, val status: OrderStatus, val orderedAt: Instant)`. 아이디 형식은 `ORD-0001`이고 1번부터 차례로 붙는다.
  - `enum class OrderStatus { PAID, PREPARING, SHIPPED, DELIVERED, CANCELLED, EXCHANGE_REQUESTED }`
  - `data class Payment(val orderId: String, val paidAmount: Long, val refundedAmount: Long)`
  - `data class Stock(val sku: String, val quantity: Int)`
  - `data class Shipment(val orderId: String, val status: ShipmentStatus, val eta: Instant)`
  - `enum class ShipmentStatus { READY, IN_TRANSIT, DELAYED, DELIVERED }`
  - `class ShopState`는 위 엔티티를 아이디로 찾는 변경 가능한 맵 여섯 개 `customers`, `products`, `orders`, `payments`, `stocks`, `shipments`를 가진다. 결제와 배송은 주문 아이디로, 재고는 SKU로 찾는다. 저장 메서드 `putOrder`, `putPayment`, `putStock`, `putShipment`는 덮어쓰기 직전의 값을 `previousOrder(id)`, `previousPayment(orderId)`, `previousStock(sku)`, `previousShipment(orderId)`로 꺼낼 수 있게 한 단계만 보관한다.
  - `class ShopDataGenerator(seed: Long) { fun generate(customerCount: Int = 20): ShopState }`. 기준 시각은 `2026-01-01T00:00:00+09:00`이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.**

```kotlin
@Test fun sameSeedProducesSameState() {
    val a = ShopDataGenerator(42).generate()
    val b = ShopDataGenerator(42).generate()
    assertEquals(a.orders, b.orders)
    assertEquals(a.payments, b.payments)
    assertEquals(a.shipments, b.shipments)
}

@Test fun differentSeedProducesDifferentState() {
    assertNotEquals(ShopDataGenerator(1).generate().orders, ShopDataGenerator(2).generate().orders)
}

@Test fun generatedDataIsConsistent() {
    val s = ShopDataGenerator(42).generate()
    assertTrue(s.orders.containsKey("ORD-0001"))
    s.orders.values.forEach { o ->
        assertEquals(o.items.sumOf { it.unitPrice * it.quantity }, s.payments.getValue(o.id).paidAmount)
        assertTrue(s.shipments.containsKey(o.id))
    }
}

@Test fun previousKeepsOneVersion() {
    val s = ShopDataGenerator(42).generate()
    val before = s.orders.getValue("ORD-0001")
    s.putOrder(before.copy(status = OrderStatus.CANCELLED))
    assertEquals(before, s.previousOrder("ORD-0001"))
}
```

- [ ] **Step 2: 실패를 확인한다.** `./gradlew :example-shop:test --tests '*ShopDataGeneratorTest*'`가 컴파일 오류로 실패하면 된다.
- [ ] **Step 3: 구현한다.** 모든 난수는 `kotlin.random.Random(seed)` 하나에서 뽑는다. 상품은 한국어 이름 10개를 고정 목록으로 두고, 고객 이름은 성과 이름 목록을 조합해 만든다. 고객마다 주문 1개에서 4개, 주문마다 품목 1개에서 3개를 만든다. 주문 상태에 맞춰 배송 상태를 정한다. `PAID`와 `PREPARING`은 `READY`, `SHIPPED`는 `IN_TRANSIT`이나 `DELAYED`, `DELIVERED`는 `DELIVERED`다. 생성 시점에는 `previous*` 값이 없다.
- [ ] **Step 4: 통과를 확인한다.** 같은 명령이 통과하면 된다.
- [ ] **Step 5: 커밋한다.** 메시지는 `가짜 쇼핑몰 도메인 모델과 시드 데이터 생성기를 추가한다`.

### Task 3: 장애 주입기

**Files:**
- Create: `example-shop/src/main/kotlin/aiagent/shop/fault/FaultConfig.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/fault/FaultInjector.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/fault/ServiceException.kt`
- Test: `example-shop/src/test/kotlin/aiagent/shop/fault/FaultInjectorTest.kt`

**Interfaces:**
- Consumes: Task 2의 `ShopState`.
- Produces:
  - `enum class ServiceName { ORDER, PAYMENT, INVENTORY, SHIPPING }`
  - `data class ServiceFaults(val latencyMs: LongRange = 0L..0L, val errorRate: Double = 0.0, val rateLimitEvery: Int? = null, val staleReadRate: Double = 0.0)`. 생성 시 `errorRate`와 `staleReadRate`가 0.0에서 1.0 사이인지, `rateLimitEvery`가 1 이상인지 `require`로 검사한다.
  - `class ScheduledMutation(val afterTotalCalls: Int, val description: String, val apply: (ShopState) -> Unit)`
  - `data class FaultConfig(val services: Map<ServiceName, ServiceFaults> = emptyMap(), val mutations: List<ScheduledMutation> = emptyList())`
  - `sealed class ServiceException(message: String) : RuntimeException(message)`와 하위 클래스 `Unavailable(service: ServiceName, operation: String)`, `RateLimited(service: ServiceName, operation: String)`, `NotFound(entity: String, id: String)`, `Rejected(reason: String)`
  - `class FaultInjector(seed: Long, config: FaultConfig, state: ShopState)`
    - `suspend fun <T> call(service: ServiceName, operation: String, block: suspend (stale: Boolean) -> T): T`
    - `val totalCalls: Int`, `fun callCount(service: ServiceName): Int`, `val injectedDelayMs: Long`, `val firedMutations: List<String>`

- [ ] **Step 1: 실패하는 테스트를 쓴다.** 모든 테스트는 `runTest` 안에서 돌려서 `delay`가 가상 시간으로 지나가게 한다. 아래 `outcomes` 함수는 같은 서비스를 n번 부르고 결과를 `"ok"`나 예외 클래스 이름의 목록으로 돌려주는 테스트 도우미다.

```kotlin
@Test fun sameSeedSameFailures() = runTest {
    val cfg = FaultConfig(mapOf(ServiceName.ORDER to ServiceFaults(errorRate = 0.3)))
    assertEquals(outcomes(FaultInjector(7, cfg, state()), ServiceName.ORDER, 50),
                 outcomes(FaultInjector(7, cfg, state()), ServiceName.ORDER, 50))
}

@Test fun extraCallsOnOneServiceDoNotShiftAnother() = runTest {
    val cfg = FaultConfig(ServiceName.entries.associateWith { ServiceFaults(errorRate = 0.3) })
    val plain = FaultInjector(7, cfg, state())
    val noisy = FaultInjector(7, cfg, state())
    outcomes(noisy, ServiceName.INVENTORY, 13)
    assertEquals(outcomes(plain, ServiceName.ORDER, 30), outcomes(noisy, ServiceName.ORDER, 30))
}

@Test fun errorRateBoundaries() = runTest {
    assertTrue(outcomes(injector(errorRate = 0.0), ServiceName.ORDER, 100).all { it == "ok" })
    assertTrue(outcomes(injector(errorRate = 1.0), ServiceName.ORDER, 100).all { it == "Unavailable" })
}

@Test fun rateLimitEveryThirdCall() = runTest {
    assertEquals(listOf("ok", "ok", "RateLimited", "ok", "ok", "RateLimited"),
                 outcomes(injector(rateLimitEvery = 3), ServiceName.ORDER, 6))
}

@Test fun latencyIsInjectedWithinRange() = runTest {
    val inj = injector(latencyMs = 100L..300L)
    outcomes(inj, ServiceName.ORDER, 10)
    assertTrue(inj.injectedDelayMs in 1000L..3000L)
    assertEquals(inj.injectedDelayMs, testScheduler.currentTime)
}

@Test fun mutationFiresOnceBeforeTheNthCall() = runTest {
    val s = state()
    val inj = FaultInjector(7, FaultConfig(mutations = listOf(
        ScheduledMutation(2, "ORD-0001 배송 출발") { it.putOrder(it.orders.getValue("ORD-0001").copy(status = OrderStatus.SHIPPED)) })), s)
    inj.call(ServiceName.ORDER, "getOrder") { }
    assertNotEquals(OrderStatus.SHIPPED, s.orders.getValue("ORD-0001").status)
    inj.call(ServiceName.ORDER, "getOrder") { }
    repeat(5) { inj.call(ServiceName.ORDER, "getOrder") { } }
    assertEquals(OrderStatus.SHIPPED, s.orders.getValue("ORD-0001").status)
    assertEquals(listOf("ORD-0001 배송 출발"), inj.firedMutations)
}

@Test fun invalidConfigIsRejected() {
    assertFailsWith<IllegalArgumentException> { ServiceFaults(errorRate = 1.5) }
    assertFailsWith<IllegalArgumentException> { ServiceFaults(rateLimitEvery = 0) }
}
```

- [ ] **Step 2: 실패를 확인한다.** `./gradlew :example-shop:test --tests '*FaultInjectorTest*'`가 컴파일 오류로 실패하면 된다.
- [ ] **Step 3: 구현한다.** 서비스마다 `Random(seed * 31 + service.ordinal)`로 난수 흐름을 따로 만든다. `call` 한 번에 다음 순서를 지킨다. 같은 설정이면 같은 순서로 난수를 뽑아야 결정성이 유지되므로, 순서와 매 호출 난수 세 번 뽑기는 그대로 따른다.

```text
1. totalCalls를 1 늘리고, afterTotalCalls == totalCalls인 변경을 아직 안 했으면 적용하고 firedMutations에 기록한다.
2. 서비스별 호출 수를 1 늘린다.
3. 그 서비스의 난수에서 지연, 오류 판정, 오래된 읽기 판정 값을 이 순서로 매번 하나씩 뽑는다.
4. rateLimitEvery가 있고 서비스별 호출 수가 그 배수면 RateLimited를 던진다.
5. 뽑은 지연만큼 delay하고 injectedDelayMs에 더한다.
6. 오류 판정 값이 errorRate보다 작으면 Unavailable을 던진다.
7. block(stale = 오래된 읽기 판정 값 < staleReadRate)를 실행해 결과를 돌려준다.
```

- [ ] **Step 4: 통과를 확인한다.** 같은 명령이 통과하면 된다.
- [ ] **Step 5: 커밋한다.** 메시지는 `시드 기반 장애 주입기를 추가한다`.

### Task 4: 가짜 서비스와 세계 조립

**Files:**
- Create: `example-shop/src/main/kotlin/aiagent/shop/service/OrderService.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/service/PaymentService.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/service/InventoryService.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/service/ShippingService.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/ShopWorld.kt`
- Test: `example-shop/src/test/kotlin/aiagent/shop/service/ServicesTest.kt`

**Interfaces:**
- Consumes: Task 2의 모델과 `ShopDataGenerator`, Task 3의 `FaultInjector`와 `ServiceException`.
- Produces:
  - `OrderService`: `suspend fun getOrder(orderId: String): Order`, `suspend fun listOrders(customerId: String): List<Order>`, `suspend fun requestExchange(orderId: String, sku: String): Order`
  - `PaymentService`: `suspend fun getPayment(orderId: String): Payment`, `suspend fun refund(orderId: String, amount: Long): Payment`
  - `InventoryService`: `suspend fun getStock(sku: String): Stock`
  - `ShippingService`: `suspend fun getShipment(orderId: String): Shipment`
  - `class ShopWorld(val state: ShopState, val injector: FaultInjector, val orders: OrderService, val payments: PaymentService, val inventory: InventoryService, val shipping: ShippingService)`
  - `ShopWorld`의 `companion object`에 `fun create(seed: Long, faults: FaultConfig = FaultConfig()): ShopWorld`를 둔다. 데이터 생성과 장애 주입에 같은 시드를 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.**

```kotlin
@Test fun unknownOrderIsNotFound() = runTest {
    assertFailsWith<ServiceException.NotFound> { ShopWorld.create(42).orders.getOrder("ORD-9999") }
}

@Test fun refundRejectsInvalidAmounts() = runTest {
    val w = ShopWorld.create(42)
    val paid = w.payments.getPayment("ORD-0001").paidAmount
    assertFailsWith<ServiceException.Rejected> { w.payments.refund("ORD-0001", 0) }
    assertFailsWith<ServiceException.Rejected> { w.payments.refund("ORD-0001", paid + 1) }
    w.payments.refund("ORD-0001", paid)
    assertFailsWith<ServiceException.Rejected> { w.payments.refund("ORD-0001", 1) }
}

@Test fun refundDoesNotCheckShippingPolicy() = runTest {
    val w = ShopWorld.create(42)
    val shipped = w.state.orders.values.first { it.status == OrderStatus.SHIPPED }
    w.payments.refund(shipped.id, 1)
}

@Test fun exchangeNeedsStock() = runTest {
    val w = ShopWorld.create(42)
    val sku = w.state.stocks.keys.first()
    w.state.putStock(Stock(sku, 0))
    assertFailsWith<ServiceException.Rejected> { w.orders.requestExchange("ORD-0001", sku) }
}

@Test fun staleReadReturnsPreviousVersion() = runTest {
    val w = ShopWorld.create(42, FaultConfig(mapOf(ServiceName.ORDER to ServiceFaults(staleReadRate = 1.0))))
    val before = w.state.orders.getValue("ORD-0001")
    w.state.putOrder(before.copy(status = OrderStatus.CANCELLED))
    assertEquals(before, w.orders.getOrder("ORD-0001"))
}

@Test fun staleReadWithoutHistoryReturnsCurrent() = runTest {
    val w = ShopWorld.create(42, FaultConfig(mapOf(ServiceName.ORDER to ServiceFaults(staleReadRate = 1.0))))
    assertEquals(w.state.orders.getValue("ORD-0001"), w.orders.getOrder("ORD-0001"))
}

@Test fun everyCallGoesThroughInjector() = runTest {
    val w = ShopWorld.create(42)
    w.orders.getOrder("ORD-0001"); w.shipping.getShipment("ORD-0001")
    assertEquals(2, w.injector.totalCalls)
}
```

- [ ] **Step 2: 실패를 확인한다.** `./gradlew :example-shop:test --tests '*ServicesTest*'`가 컴파일 오류로 실패하면 된다.
- [ ] **Step 3: 구현한다.** 각 메서드는 몸체 전체를 `injector.call(서비스, "메서드이름") { stale -> ... }`로 감싼다. 읽기 메서드는 `stale`이 참이고 `previous*` 값이 있으면 그 값을 돌려준다. 서비스는 다음 불변식만 검사한다. 환불 금액은 1 이상이고 남은 결제 금액 이하여야 한다. 교환은 재고가 1 이상이어야 하고, 성공하면 재고를 1 줄이고 주문 상태를 `EXCHANGE_REQUESTED`로 바꾼다. 배송 출발 후 환불 금지 같은 정책은 서비스가 검사하지 않는다. 이후 단계에서 에이전트가 정책을 어기는 문제를 겪어야 하기 때문이다.
- [ ] **Step 4: 통과를 확인한다.** 같은 명령이 통과하면 된다.
- [ ] **Step 5: 커밋한다.** 메시지는 `가짜 쇼핑몰 서비스 네 개와 세계 조립을 추가한다`.

### Task 5: 기준 응답기와 시나리오 실행기

**Files:**
- Create: `example-shop/src/main/kotlin/aiagent/shop/scenario/Responder.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/scenario/RuleBasedResponder.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/scenario/Scenario.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/scenario/ScenarioRunner.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/scenario/Scenarios.kt`
- Test: `example-shop/src/test/kotlin/aiagent/shop/scenario/ScenarioRunnerTest.kt`

**Interfaces:**
- Consumes: Task 4의 `ShopWorld.create`, Task 3의 `FaultConfig`와 `ServiceException`.
- Produces:
  - `fun interface Responder { suspend fun respond(message: String): String }`
  - `class RuleBasedResponder(world: ShopWorld) : Responder`. 메시지에서 `ORD-\d{4}`를 찾으면 주문과 배송을 조회해 `"주문 {id}의 상태는 {주문 상태 한글}이고, 배송은 {배송 상태 한글}입니다."`로 답한다. 찾지 못하면 `"주문번호를 알려 주세요."`로 답한다. 오류를 처리하지 않는다. 이 응답기는 이후 단계에서 LLM 에이전트와 비교할 기준선이다.
  - 상태 한글 표기: `PAID` 결제 완료, `PREPARING` 상품 준비 중, `SHIPPED` 배송 출발, `DELIVERED` 배송 완료, `CANCELLED` 취소, `EXCHANGE_REQUESTED` 교환 요청. `READY` 출고 대기, `IN_TRANSIT` 배송 중, `DELAYED` 배송 지연, `DELIVERED` 배송 완료.
  - `data class Turn(val user: String, val reply: String)`
  - `class Scenario(val id: String, val faults: FaultConfig, val messages: List<String>, val check: (ShopWorld, List<Turn>) -> Boolean)`
  - `data class ScenarioResult(val id: String, val passed: Boolean, val failure: String?, val serviceCalls: Int, val injectedDelayMs: Long)`. `failure`는 예외로 끝났을 때 예외 클래스 이름과 메시지, 검사에서 떨어졌을 때 `"검사 실패"`다.
  - `class ScenarioRunner(val seed: Long, val responderFactory: (ShopWorld) -> Responder)`: `suspend fun run(scenarios: List<Scenario>): List<ScenarioResult>`, `fun report(results: List<ScenarioResult>): String`
  - `object Scenarios { val all: List<Scenario> }`. 세 시나리오를 둔다. `order-status-basic`은 장애 없이 ORD-0001 상태를 묻고, 답에 실제 주문 상태 한글 표기가 들어 있으면 성공이다. `order-status-unavailable`은 주문 서비스 `errorRate = 1.0`으로 같은 질문을 한다. `refund-after-shipping`은 첫 호출 직전에 ORD-0001을 `SHIPPED`로 바꾸는 변경을 걸고 환불을 요청하며, 답에 "환불"이 들어 있으면 성공이다. 기준 응답기는 뒤의 두 시나리오에서 실패하는 것이 정상이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.**

```kotlin
@Test fun reportFormat() {
    val text = ScenarioRunner(42) { RuleBasedResponder(it) }.report(listOf(
        ScenarioResult("a", true, null, 2, 0),
        ScenarioResult("b", false, "Unavailable: ORDER.getOrder", 1, 0)))
    assertEquals("""
        시나리오 결과 (seed=42)
        [성공] a: 서비스 호출 2회, 주입 지연 0ms
        [실패] b: Unavailable: ORDER.getOrder, 서비스 호출 1회, 주입 지연 0ms
        합계: 2개 중 1개 성공 (50.0%)
    """.trimIndent(), text)
}

@Test fun baselineResultsOnBuiltInScenarios() = runTest {
    val results = ScenarioRunner(42) { RuleBasedResponder(it) }.run(Scenarios.all)
    assertEquals(listOf(true, false, false), results.map { it.passed })
}

@Test fun sameSeedSameResults() = runTest {
    val runner = ScenarioRunner(42) { RuleBasedResponder(it) }
    assertEquals(runner.run(Scenarios.all), runner.run(Scenarios.all))
}

@Test fun failingScenarioDoesNotStopRunAndStateIsFresh() = runTest {
    val breaking = Scenario("break", FaultConfig(), listOf("x"), { _, _ -> true })
    var created = 0
    val runner = ScenarioRunner(42) { w ->
        if (created++ == 0) Responder { w.state.orders.clear(); error("boom") } else RuleBasedResponder(w)
    }
    val results = runner.run(listOf(breaking, Scenarios.all.first()))
    assertEquals(listOf(false, true), results.map { it.passed })
    assertTrue(results[0].failure!!.contains("boom"))
}
```

- [ ] **Step 2: 실패를 확인한다.** `./gradlew :example-shop:test --tests '*ScenarioRunnerTest*'`가 컴파일 오류로 실패하면 된다.
- [ ] **Step 3: 구현한다.** `run`은 시나리오마다 `ShopWorld.create(seed, scenario.faults)`로 새 세계를 만들고, 메시지를 차례로 응답기에 넣는다. 응답기가 던진 예외는 모든 `Exception`을 잡아 `ScenarioResult`의 실패로 기록하고 다음 시나리오로 넘어간다. 성공률은 소수점 첫째 자리까지 표시한다.
- [ ] **Step 4: 통과를 확인한다.** 같은 명령이 통과하면 된다.
- [ ] **Step 5: 커밋한다.** 메시지는 `기준 응답기와 최소 시나리오 실행기를 추가한다`.

### Task 6: 실행 진입점

**Files:**
- Create: `example-shop/src/main/kotlin/aiagent/shop/cli/Main.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/fault/FaultPresets.kt`
- Test: `example-shop/src/test/kotlin/aiagent/shop/cli/ArgsTest.kt`

**Interfaces:**
- Consumes: Task 4의 `ShopWorld.create`, Task 5의 `RuleBasedResponder`, `ScenarioRunner`, `Scenarios`.
- Produces:
  - `object FaultPresets { fun get(name: String): FaultConfig }`. 이름은 `none`, `slow`, `flaky`, `rate-limited`, `stale`이다. `slow`는 모든 서비스 `latencyMs = 500L..2000L`, `flaky`는 모든 서비스 `errorRate = 0.2`, `rate-limited`는 모든 서비스 `rateLimitEvery = 3`, `stale`은 모든 서비스 `staleReadRate = 0.5`다. 모르는 이름이면 사용 가능한 이름을 담은 `IllegalArgumentException`을 던진다.
  - `data class CliArgs(val command: String, val seed: Long, val faults: String)`와 `fun parseArgs(args: Array<String>): CliArgs`. 명령은 `chat`이나 `scenarios`이고, 옵션은 `--seed`(기본 42)와 `--faults`(기본 `none`)다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.**

```kotlin
@Test fun defaults() = assertEquals(CliArgs("chat", 42, "none"), parseArgs(arrayOf("chat")))

@Test fun options() = assertEquals(CliArgs("scenarios", 7, "flaky"),
    parseArgs(arrayOf("scenarios", "--seed", "7", "--faults", "flaky")))

@Test fun unknownCommandFails() { assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("serve")) } }

@Test fun unknownPresetFails() { assertFailsWith<IllegalArgumentException> { FaultPresets.get("chaos") } }
```

- [ ] **Step 2: 실패를 확인한다.** `./gradlew :example-shop:test --tests '*ArgsTest*'`가 컴파일 오류로 실패하면 된다.
- [ ] **Step 3: 구현한다.** `main`은 `runBlocking` 안에서 동작한다. `chat`은 프롬프트 `> `를 띄우고 한 줄씩 읽어 `RuleBasedResponder`의 답을 출력한다. `ServiceException`은 `[서비스 오류] {메시지}`로 출력하고 대화를 이어 가며, `/quit`나 입력 끝에서 종료한다. `scenarios`는 `ScenarioRunner(seed).run(Scenarios.all)`의 `report`를 출력한다. 인자 오류는 사용법을 출력하고 종료 코드 2로 끝낸다. `example-shop/build.gradle.kts`의 `run` 작업에 `standardInput = System.in`을 설정한다.
- [ ] **Step 4: 통과를 확인한다.** 같은 명령과 `./gradlew test` 전체가 통과하면 된다.
- [ ] **Step 5: 완료 기준을 직접 확인한다.** 아래 명령을 실행하고 두 출력이 같으면 된다. 로드맵의 실험 환경 완료 기준인 "같은 시드와 같은 장애 설정이면 같은 결과"를 실행 파일 수준에서 확인하는 단계다.

```bash
./gradlew -q :example-shop:run --args="scenarios --seed 42" > build/a.txt
./gradlew -q :example-shop:run --args="scenarios --seed 42" > build/b.txt
diff build/a.txt build/b.txt && cat build/a.txt
```

`printf 'ORD-0001 어디쯤 왔나요?\n/quit\n' | ./gradlew -q :example-shop:run --args="chat --faults flaky"`도 실행해서 답이나 `[서비스 오류]` 줄이 출력되는지 확인한다.

- [ ] **Step 6: 커밋한다.** 메시지는 `채팅과 시나리오 실행 진입점을 추가한다`.
