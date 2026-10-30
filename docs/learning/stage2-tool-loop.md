# 툴 호출 루프 단계 학습 기록

이 기록은 툴 호출 루프 단계 전반부의 실험을 남긴다. 실험마다 실행하기 전의 예측, 실제 결과, 둘이 달랐던 이유를 적는다.

## 툴 정의가 늘리는 입력 토큰

### 예측

`/tools off`일 때 입력 토큰은 모델 호출 단계의 첫 호출과 같은 54개다. `/tools on`일 때는 54개에 툴 정의를 구분하는 토큰과 툴마다 붙은 설명의 토큰이 더해진다.

### 실제 결과

온도 0에서 "안녕하세요."를 보냈다. `/tools off`일 때 입력 토큰은 54개였고, `/tools on`일 때는 137개였다. 툴 정의 두 개가 입력 토큰을 83개 늘렸다. 출력 토큰은 두 경우 모두 11개였다.

Claude가 `o200k_base`로 툴 정의를 두 가지 모양으로 세어 비교했다.

- 보낸 JSON을 그대로 세면 121개이고, 이 모양이라면 입력 토큰은 175개가 된다.
- 정의를 아래와 같은 함수 선언 형태의 글로 바꿔서 세면 84개이고, 이 모양이라면 입력 토큰은 138개가 된다.

```
# Tools

## functions

namespace functions {

// 주문번호로 주문 상태를 조회한다.
type get_order = (_: {
// 주문번호. 예: ORD-0001
order_id: string,
}) => any;

// 주문번호로 배송 상태를 조회한다.
type get_shipment = (_: {
// 주문번호. 예: ORD-0001
order_id: string,
}) => any;

} // namespace functions
```

실제 값 137개는 두 번째 모양과 1개 차이다. 그래서 서버는 JSON을 그대로 넣지 않고 이런 글로 바꿔서 넣는 것으로 보인다. 이 형식은 OpenAI 공식 문서에 없고, 토큰 수가 거의 맞는다는 점만이 근거인 추정이다. 이 모양으로 나누어 세면 머리말과 꼬리말이 13개, `get_order`가 36개, `get_shipment`가 37개다.

### 달랐던 이유

툴 구분 토큰과 툴마다 붙은 설명의 토큰이 더해진다는 구조는 맞았다. 그러나 툴 설명뿐 아니라 인자 이름과 타입, 인자 설명까지 들어간다는 것은 생각하지 못했다.

## 툴이 필요 없는 말

이 실험은 따로 돌리지 않고 첫 실험의 `/tools on` 요청으로 대신했다. 결과를 먼저 본 뒤라 예측은 남기지 않았다.

### 실제 결과

툴을 실은 채 "안녕하세요."를 보내자 모델은 툴을 부르지 않고 바로 답했다. 출력 토큰은 툴을 싣지 않았을 때와 같은 11개였고, 툴을 불렀다면 나왔을 `[빈 응답]`도 나오지 않았다. 답의 문장은 기록하지 않았다.

## 툴 호출 응답의 모양

### 예측

툴을 켜고 원문 출력을 켠 채 "주문번호 ORD-0001 지금 어디쯤 왔어요?"를 보낸다. `finish_reason`은 `tool_calls`이고 `content`는 null일 것이다. 툴 호출 정보가 응답 JSON의 어디에 들어 있는지와 `order_id` 인자가 어떤 형식으로 오는지는 예측하지 못했다.

### 실제 결과

처음 실행에서는 첫 실험 뒤 툴이 꺼진 채로 남아 있어 요청에 `tools` 키가 없었다. 모델은 모델 호출 단계의 여덟 번째 실험처럼 "배송 중이며, 예상 도착일은 내일"이라고 지어냈고, 입력 토큰은 67개였다. 툴을 켜고 다시 보낸 결과는 다음과 같다.

```json
"message": {
  "role": "assistant",
  "content": null,
  "tool_calls": [
    {
      "id": "call_wmZpVWpDAVRahYjHIoYmuxm5",
      "type": "function",
      "function": {
        "name": "get_shipment",
        "arguments": "{\"order_id\":\"ORD-0001\"}"
      }
    }
  ],
  "refusal": null,
  "annotations": []
},
"finish_reason": "tool_calls"
```

- `finish_reason`은 `tool_calls`였고 `content`는 null이었다.
- 툴 호출 정보는 `choices[0].message` 안의 `tool_calls` 배열에 들어 있었다. 원소마다 호출 `id`, `type`, 그리고 `function`의 `name`과 `arguments`가 있다.
- `arguments`는 JSON 객체가 아니라 JSON을 담은 문자열이었다. 따옴표가 `\"`로 이스케이프되어 있다.
- 모델은 `get_order`와 `get_shipment` 중 `get_shipment` 하나만 불렀다.
- 입력 토큰은 150개, 출력 토큰은 19개였다. 툴이 없을 때의 67개에 첫 실험에서 잰 툴 정의 83개를 더한 값과 같다.
- 채팅에는 `[빈 응답] finish_reason=tool_calls`가 보였다. 응답을 읽는 코드가 아직 `tool_calls`를 모르기 때문이다.

응답 본문은 `framework/src/test/resources/openai/chat-completion-tool-calls.json`에 저장했다.

### 달랐던 이유

`finish_reason`과 `content`는 예측대로였다. 인자가 JSON 객체가 아니라 JSON을 담은 문자열로 온다는 것은 예측하지 못했다.

## 최종 답까지 오가는 요청

### 예측

### 실제 결과

### 달랐던 이유

## 주문 상태를 다시 묻기

### 예측

### 실제 결과

### 달랐던 이유

## 주문번호 없이 묻기

### 예측

### 실제 결과

### 달랐던 이유

## 마지막 확인

## 후반부로 넘길 문제
