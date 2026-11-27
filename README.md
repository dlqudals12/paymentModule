# paymentModule

회원 인증과 결제 준비·승인 흐름을 구현하는 Spring Boot 프로젝트입니다. 결제 모듈은 주문·상품 도메인의 후속 처리와 분리하고, PG별 승인 로직은 `PaymentProcessor`로 구분합니다. 현재 결제 흐름에 연결된 PG는 토스페이먼츠와 나이스페이먼츠입니다.

> **AI와의 개발 대화 기록:** [프로젝트 대화문](docs/ai-development-conversation.txt)
> 설계와 구현을 논의한 사용자·AI 메시지를 시간순으로 담았습니다. 비속어는 표시 없이 삭제했고, 도구 실행 내역은 제외했습니다.

## 기술 구성

| 구분 | 사용 기술 |
| --- | --- |
| 언어·빌드 | Java 25, Gradle 9.5.1 |
| 애플리케이션 | Spring Boot 4.1.0, Spring MVC, Spring Data JPA |
| 인증 | Spring Security, JWT, 쿠키 |
| 데이터 저장소 | MySQL 8.4, Redis 7.4 |
| PG 통신 | Spring `RestClient` |
| API 문서 | springdoc OpenAPI |
| API 테스트 화면 | React 19, Vite 8, React Router 8, Axios, TossPayments SDK 2, NICEPAY 결제창 스크립트 |
| 테스트 | JUnit 5, Spring Boot Test, H2, Mockito |

Redis 의존성과 컨테이너 설정은 포함되어 있지만, 현재 결제 승인 흐름의 상태·이력·API 로그는 MySQL에 저장합니다.

## 주요 기능

### 회원 인증

- 회원 가입, 로그인, 토큰 갱신, 로그아웃 API를 제공합니다.
- 로그인 시 액세스·리프레시 JWT를 쿠키에 설정합니다. 결제 API에는 `ROLE_USER` 메서드 보안이 적용되어 있습니다.

### 결제 준비와 승인

1. `POST /apis/v3/payments/ready`가 PG와 `orderId`를 받아 서버에서 금액을 계산하고 결제 시도를 `READY`로 저장합니다.
2. 같은 주문에 `READY` 시도가 있으면 금액을 갱신합니다. `FAILED`가 확정되면 같은 `Payment` 행의 이전 PG 키·멱등키를 정리하고 새 `READY` 시도로 재준비합니다. 이전 상태와 PG 호출은 이력·API 로그에 남습니다. `PROCESSING`, `UNKNOWN`, `SUCCESS` 등은 다시 준비하지 않습니다.
3. 클라이언트가 반환된 주문 정보로 PG SDK를 실행하고, 인증 결과의 PG 결제 키·`orderId`·`amount`를 결제 승인 API에 전달합니다. 토스는 `paymentKey`, 나이스페이는 `tid`를 PG 결제 키로 사용합니다. 나이스페이는 인증 결과의 서명도 서버에서 검증합니다.
4. `POST /apis/v3/payments`는 서버에서 다시 계산한 금액, 저장 금액, 전달 금액을 비교합니다. 결제 행을 잠근 짧은 트랜잭션에서 `READY → PROCESSING`으로 변경해 같은 주문의 중복 승인 요청을 막습니다.
5. DB 트랜잭션 밖에서 PG 승인 API를 호출합니다. 정상 API 응답의 결제 상태는 서비스에서 처리하고, PG API 오류는 `PaymentException`으로 전달합니다. 승인 결과를 확정할 수 없는 호출 오류는 `UNKNOWN`으로 기록합니다.
6. 같은 결제 키로 `PROCESSING`, `UNKNOWN` 또는 `WAITING_FOR_PAYMENT` 요청이 다시 들어오면 PG 결제를 조회합니다. 조회 성공은 주문번호·금액·결제 키를 대조해 확정합니다. PG가 결제 정보 부재를 명시한 404는 5초 간격으로 두 번 더 조회하고, 세 번 모두 결제 부재일 때 `FAILED`로 확정해 새 `ready` 시도를 허용합니다. 통신 오류 등 불확실한 조회 결과는 기존 상태를 유지합니다.
7. 성공 결과가 커밋되면 `PaymentCompletedEvent`를 발행합니다. 비동기 수신부는 `orderStatus`를 `BEFORE → PENDING`으로 변경합니다. 실제 주문 반영이 성공한 뒤 `COMPLETE`로 변경하는 연결은 TODO로 남아 있습니다.

실패한 결제를 다시 시도할 때는 `POST /apis/v3/payments/{paymentId}/retry`로 내부 결제 ID를 전달합니다. `FAILED`는 같은 행에서 새 `READY` 시도로 준비하고, `UNKNOWN`은 저장된 PG 결제 키로 즉시 조회한 뒤 성공이면 기존 결과를 반환합니다. PG가 실패 또는 결제 부재를 확정한 경우에만 재준비하며, 결과가 여전히 불확실하면 새 시도를 막습니다. 재준비 응답을 받은 브라우저는 SDK를 다시 실행하고 새 결제 키로 기존 승인 API를 호출합니다. 같은 재결제 준비 요청이 `READY` 상태에 다시 들어오면 새 멱등키를 만들지 않고 준비 결과를 반환합니다.

결제 상태는 `paymentStatus`, 주문 반영 상태는 `orderStatus`로 따로 저장하고 각각의 마지막 변경 시각을 `paymentStatusChangedAt`, `orderStatusChangedAt`에 기록합니다. 스케줄러는 `BEFORE` 건에는 결제 완료 시각인 `approvedAt`을, `PENDING` 건에는 주문 반영 상태 변경 시각을 사용해 유예 시간이 지난 모든 완료 이벤트를 다시 전달합니다. 실제 상품·주문 반영 코드는 아직 연결되지 않았으므로 이벤트 수신 후에도 `orderStatus`는 `PENDING`에 남습니다.

`PaymentScheduler`는 상태 변경 후 설정된 유예 시간이 지난 `PROCESSING`·`UNKNOWN`·`WAITING_FOR_PAYMENT` 결제를 저장된 결제 키로 PG에 조회합니다. 조회 결과가 입금 대기이면 `WAITING_FOR_PAYMENT`로 확정하고 다음 실행에서 다시 확인합니다. 주문 이벤트 재전달과 결제 상태 복구는 `schedule.payments` 아래 `order`, `processing` 설정으로 각각 조정합니다. 현재 두 작업은 시작 1분 후 첫 실행을 시작하고 각 실행이 끝난 5분 뒤 다시 실행합니다. 대상의 기준 시각에서 10분이 지난 건을 조회합니다.

토스 API 호출에는 시크릿 키 기반 Basic 인증, JSON 본문, 멱등키를 사용합니다. `RestClient` 공통 인터셉터는 요청·응답의 메서드, 경로, 상태, 시간, 헤더, JSON 본문을 여러 줄로 출력하며 인증값과 구매자 식별 키만 가립니다. `paymentKey`와 결제수단 정보는 콘솔 로그에서 가리지 않습니다. DB의 `payment_api_log`에는 PG 처리기가 선별한 요청·응답 JSON과 호출 결과만 저장하고 원문과 인증 헤더는 저장하지 않습니다.

## 설계 시 고려한 상황

### 주문 도메인과 결제 모듈의 경계

결제 모듈은 상품 ID나 주문 테이블 구조를 직접 결제 식별자로 사용하지 않고 `orderId`를 받습니다. 주문 서버가 결제 직전 주문과 금액을 확정하고, `ready`에서 그 금액을 서버 기준으로 읽어 저장하는 흐름을 전제로 합니다. 현재 `PaymentAmountCalculator`의 금액은 임시 값이므로, 실제 사용처의 주문 테이블 또는 주문 서비스 연동이 필요합니다. 같은 계산을 승인 직전에도 다시 실행해 준비 금액 및 SDK 전달 금액과 비교합니다. 클라이언트가 금액을 바꿔 보내거나 준비 이후 주문 금액이 달라진 경우 승인을 진행하지 않기 위한 검증입니다.

PG별 요청·응답 처리는 `PaymentProcessor`로 분리하고 `PaymentProvider`를 키로 선택합니다. 반면 결제 이후 주문·상품 반영은 모듈이 업무 도메인을 알 수 없으므로 완료 이벤트를 전달하는 지점까지만 맡습니다. 사용처가 단일 애플리케이션이든 별도 주문 서비스든, 실제 후속 처리와 그 트랜잭션은 사용처에서 연결해야 합니다.

### Spring Boot 결제 라이브러리로 발전시키는 방향

이 프로젝트의 목표는 사용하는 서비스가 결제 기능을 붙일 수 있는 Spring Boot 라이브러리입니다. Spring Framework에 의존하는 방식으로 개발하며, `RestClient`, 설정 프로퍼티, Bean 등록을 없애는 순수 Java 라이브러리를 목표로 하지 않습니다. 현재는 실행 가능한 단일 Spring Boot 프로젝트이고 별도 라이브러리 JAR이나 자동 설정을 배포하고 있지는 않습니다.

토스페이먼츠와 나이스페이먼츠의 처리기를 `PaymentProcessor`로 나누고 `PaymentProvider`로 선택합니다. 승인·조회에는 JPA `Payment` 엔티티 대신 공통 `PaymentProcessorData`를 넘겨 PG 구현이 저장 엔티티를 직접 다루지 않도록 했습니다. 이 단계는 향후 분리를 위한 입력 경계이며, 현재 Processor의 요청 DTO·상태 enum·예외·설정은 여전히 프로젝트 코드와 연결되어 있습니다.

다른 PG사가 추가되어 공통 처리와 PG별 차이를 더 확인한 뒤 결제 기능을 라이브러리로 분리할 계획입니다. 그때 공통 계약·상태·결과 타입과 각 PG의 API 클라이언트·처리기·설정을 라이브러리 경계에 맞춰 옮기고, Bean 등록과 `@ConfigurationProperties` 바인딩을 사용하는 애플리케이션에서 적용할 수 있게 구성합니다. 결제 금액 확인과 결제 후 주문 반영은 사용처의 업무에 연결해야 하므로, 사용처가 제공할 지점을 함께 정리합니다. 라이브러리 분리는 아직 구현되지 않았습니다.

### 새로고침, 중복 탭, 중복 승인

SDK를 열기 전에 `ready`가 결제 시도를 저장합니다. 사용자가 결제창을 닫거나 새로고침한 뒤 같은 주문으로 다시 준비하면 `READY` 시도의 서버 금액을 갱신합니다. `FAILED`가 확정된 시도는 같은 결제 행을 재준비하고 이전 상태는 이력·API 로그에 남깁니다. 그 외 진행 중이거나 성공한 시도는 `ready`로 덮어쓰지 않습니다.

승인 시에는 `orderId` 고유 제약과 결제 행 잠금으로 같은 주문의 현재 결제를 하나로 제한합니다. PG 호출 전에 `PROCESSING`, 최초 SDK `paymentKey`, 요청 시각을 먼저 커밋합니다. 다른 탭이 다른 `paymentKey`로 들어오면 기존 시도를 이어받지 못하고 충돌로 처리합니다. 같은 키의 재진입은 새 승인을 보내기 전에 PG 조회로 기존 시도의 결과를 확인합니다. PG 요청에 사용하는 멱등키도 결제 시도에 저장합니다.

### 호출 중단과 응답 유실 복구

| 중단 지점 | 저장된 상태와 재진입 시 처리 |
| --- | --- |
| `PROCESSING` 커밋 후 PG 호출 전 종료 | 같은 결제 키로 다시 들어오면 PG를 조회합니다. 토스가 결제 부재를 명시한 404를 반환하면 5초 간격으로 두 번 더 확인합니다. 세 번 모두 결제 부재이면 `FAILED`로 저장하고 같은 행의 재준비를 허용합니다. |
| PG 승인 후 결과 저장 전 응답 유실·프로세스 종료 | 로컬 상태가 `PROCESSING` 또는 `UNKNOWN`이면 같은 결제 키로 PG를 조회합니다. 성공 응답은 주문번호·금액·결제 키를 확인한 뒤 `SUCCESS`로 저장합니다. 조회 통신 오류처럼 결과가 불명확하면 상태를 유지합니다. |
| `SUCCESS` 커밋 후 완료 이벤트 전달 실패 | `orderStatus=BEFORE`가 남습니다. 스케줄러가 결제 승인 시각부터 유예 시간이 지난 건의 완료 이벤트를 다시 발행합니다. |
| 이벤트 수신 후 후속 업무 미완료 | `orderStatus=PENDING`을 유지합니다. 스케줄러가 상태 변경 시각부터 유예 시간이 지난 건의 이벤트를 다시 발행합니다. 실제 업무 완료 후 사용처가 `COMPLETE`로 확정해야 합니다. |

PG 호출은 DB 트랜잭션 밖에서 실행하고, 호출 전후의 DB 변경은 `PaymentTransactionService`의 짧은 트랜잭션으로 나눕니다. 따라서 통신 실패나 프로세스 종료로 두 시스템의 결과가 잠시 어긋날 수 있습니다. `UNKNOWN`은 실패가 아니라 승인 여부를 확인하지 못한 상태이며, 이때 새 결제를 만들지 않고 PG 조회 결과로 판단합니다. 404 재조회 간격과 횟수는 `payments.recovery.not-found-retry-delay-ms`, `not-found-retry-count`로 설정합니다. 각 조회는 별도 API 로그로 남깁니다.

### 결제 상태와 후속 업무 상태

| 구분 | 주요 전이 | 의미 |
| --- | --- | --- |
| `paymentStatus` | `BEFORE → READY → PROCESSING → WAITING_FOR_PAYMENT → SUCCESS` | 결제 준비, PG 처리, 결제 완료 대기, 승인 완료를 나타냅니다. 대기 없이 곧바로 `SUCCESS`가 될 수도 있습니다. 승인 실패는 `FAILED`, 결과 확인 불가는 `UNKNOWN`으로 기록합니다. 토스의 가상계좌 입금 대기는 `WAITING_FOR_PAYMENT`로 매핑합니다. 취소 상태인 `PARTIAL_CANCELLED`, `CANCELLED`도 정의되어 있지만 취소 업무 흐름은 아직 연결되지 않았습니다. |
| `orderStatus` | `BEFORE → PENDING → COMPLETE` | 결제 성공 이후 사용처 업무의 반영 상태입니다. 이벤트를 수신하면 `PENDING`, 실제 후속 업무가 성공한 뒤에만 `COMPLETE`가 됩니다. |

두 상태의 마지막 변경 시각을 각각 저장합니다. `payment_history`는 결제 생성과 상태 변경 이력을, `payment_api_log`는 실제 PG 승인·조회 호출 결과를 기록합니다. 완료 이벤트는 재전달될 수 있으므로 사용처의 주문·상품 반영은 결제 ID를 기준으로 중복 실행에 안전하게 연결해야 합니다. 현재 수신부는 `PENDING` 전환까지만 구현되어 있어, 사용처 연동 전에는 재전달만으로 `COMPLETE`가 되지 않습니다.

### PG 오류와 결과 저장

`PaymentProcessor`는 PG별 응답을 공통 `PaymentProcessResult`로 변환합니다. 승인·조회 시에는 JPA `Payment` 엔티티 대신 결제 행의 스칼라 값과 감사 필드를 복사한 `PaymentProcessorData`를 받습니다. JPA 연관 `User` 객체는 포함하지 않고 결제 행에 저장된 사용자 식별값만 전달합니다. API 호출이 정상적으로 끝나 결제 상태를 받은 경우에는 결과를 반환합니다. PG API 오류는 상태, PG HTTP 상태, 오류 코드·메시지를 담은 `PaymentException`을 던집니다. 그 밖의 예상하지 못한 호출 예외는 서비스에서 따로 잡아 최초 승인 결과를 `UNKNOWN`으로 기록하고, 결과 조회 중이었다면 기존 결제 상태를 유지합니다. 정상 API 응답 뒤 DB 저장 중 발생한 예외는 PG 호출 오류로 다시 기록하지 않습니다.

`PaymentService`는 PG 호출과 결제 상태 분기를 같은 `try`에서 수행하고, `PaymentException`과 그 외 예외를 각각 처리합니다. DB 저장은 별도 트랜잭션의 `PaymentTransactionService.writeResult()`로 통일했습니다. 이 메서드는 결과 상태에 따라 `payment`와 `payment_history`를 갱신하고 PG 호출 결과를 `payment_api_log`에 기록합니다. 성공 결과에는 예외를 `null`로 전달하며, 예외 유무가 아닌 `PaymentProcessResult.status`로 결제 상태를 판단합니다. PG 구현은 호출별 HTTP 메서드·요청 URL·선별한 요청·응답 JSON·응답 상태·시각을 `PaymentProcessResult.apiCalls`에 실행 순서대로 담고, 각 항목을 API 로그 한 행으로 저장합니다. 토스 승인 요청 JSON에는 주문번호와 금액만, 성공 응답 JSON에는 주문번호·금액·상태·수단·승인 시각만, 오류 응답 JSON에는 오류 코드와 메시지만 포함합니다. 결제 키는 별도 식별 컬럼에 기록하고 카드·계좌·휴대전화 상세 정보나 인증 헤더는 본문 JSON에 담지 않습니다. PG 결과를 만들기 전 예상 밖 예외가 발생해 실제 값을 확인할 수 없으면 로그에는 `UNKNOWN`을 기록합니다. 조회 결과가 불확실하거나 404 재조회 중이면 결제 상태를 바꾸지 않고 API 로그만 남깁니다.

PG API 오류는 `BusinessException`을 상속한 `PaymentException`으로 전달하고 `ResponseCode.PAYMENT_CONFLICT`의 HTTP 409 응답을 사용합니다. 오류 응답의 `code`에는 PG 오류 코드를, `message`에는 안내 문구를 담고, DB 기록에 사용하는 결과 객체 전체는 응답에 노출하지 않습니다. PG 오류 코드가 없으면 모듈의 기본 409 코드를 사용합니다.

### 토스 API 클라이언트

`TossPaymentApi`에는 결제 승인, `paymentKey` 조회, `orderId` 조회, 결제 취소, 가상계좌 발급 메서드가 있습니다. 현재 `ready → payment` 서비스 흐름에는 결제 승인과 결과 미확인 시 `paymentKey` 조회가 연결되어 있습니다. `orderId` 조회·취소·가상계좌 발급은 클라이언트 메서드가 있어도 해당 업무 API 흐름까지 구현된 것은 아닙니다.

### 나이스페이 API 클라이언트

`NicePaymentApi`는 동일한 `/v1/payments/{tid}` 경로에 승인 POST와 거래 조회 GET을 호출합니다. `NicePaymentProcessor`는 SDK 인증 결과의 `authToken`, 클라이언트 키, 금액, 서명을 시크릿 키로 검증한 후 준비 금액으로 승인합니다. 응답의 `tid`, 주문번호, 금액을 저장된 정보와 대조하고, 가상계좌 `ready`는 `WAITING_FOR_PAYMENT`로 변환합니다. 프런트 테스트 화면은 NICEPAY가 `returnUrl`로 POST한 인증 결과를 Vite 개발 서버에서 받아 React 결과 화면으로 전달하며, 로그인한 브라우저가 기존 결제 API를 호출합니다. 결제 백엔드에는 `returnUrl` 수신 컨트롤러를 두지 않습니다.
NICEPAY 요청·응답도 토스와 같은 여러 줄 콘솔 형식으로 기록하며 Basic 인증 헤더와 인증 토큰은 가립니다.

## API

| 메서드 | 경로 | 역할 |
| --- | --- | --- |
| POST | `/apis/v3/auth/register` | 회원 가입 |
| POST | `/apis/v3/auth/login` | 로그인 및 JWT 쿠키 발급 |
| POST | `/apis/v3/auth/refresh` | JWT 쿠키 갱신 |
| POST | `/apis/v3/auth/logout` | JWT 쿠키 삭제 |
| POST | `/apis/v3/payments/ready` | 결제 준비, 기존 `READY` 금액 갱신 및 `FAILED` 행 재준비 |
| POST | `/apis/v3/payments/{paymentId}/retry` | 실패 결제 재준비. `UNKNOWN`이면 PG 결과 조회 후 판단 |
| POST | `/apis/v3/payments` | SDK 인증 결과 검증 및 PG 승인 |
| GET | `/apis/v3/payments/toss/client-key` | 로그인한 브라우저에 공개용 토스 클라이언트 키 전달 |
| GET | `/apis/v3/payments?page=0` | 로그인한 사용자의 결제 목록, 20건씩 최신순 조회 |
| GET | `/apis/v3/payments/{paymentId}` | 로그인한 사용자의 결제 한 건 조회 |

`ready` 요청은 `provider`, `orderId`를 사용합니다. 승인 요청은 `provider`, `orderId`, `externalPaymentId`, `amount`를 사용합니다. 프런트엔드는 토스 SDK의 `paymentKey` 또는 NICEPAY SDK 인증 결과의 `tid`를 `externalPaymentId`로 전달합니다. NICEPAY 승인에는 `clientId`, `authToken`, `signature`를 추가로 전달하며 서버에서 서명을 검증합니다. 결제 응답의 `id`는 내부 결제 PK이고 `externalPaymentId`는 PG 결제 키입니다. 응답에는 `provider`, `orderId`, `amount`, `paymentStatus`, `orderStatus`도 포함되며 공통 성공 응답으로 감쌉니다.

## 코드 구조

```text
src/main/java/com/paymentModule/
├── api/toss/                         토스 RestClient, 요청·응답 DTO, 토스 enum
├── api/nice/                         나이스페이 RestClient, 응답 DTO, 인증 서명 검증
├── config/                           Security, Swagger 설정
├── data/                             엔티티, 저장소, 공통 응답·enum
├── domain/user/                      회원 인증 API와 서비스
├── domain/payment/                   결제 API, 서비스, PG 처리기·레지스트리
├── event/payments/                   결제 완료 이벤트와 비동기 수신부
└── global/                           설정 프로퍼티, 예외 처리, 인증·쿠키 유틸리티, 재전달 스케줄러
```

`PaymentService`는 결제 흐름을 조율하고, `PaymentTransactionService`는 결제 생성과 `writeResult()`의 상태·이력·로그 저장을 짧은 트랜잭션으로 처리합니다. `PaymentProcessor` 구현은 `PaymentProvider`를 키로 한 Bean 맵에서 선택하며, 현재 등록된 구현은 `TossPaymentProcessor`와 `NicePaymentProcessor`입니다.

`PaymentProcessResult`는 공통 결제 상태·식별자, PG 원본 상태·수단, PG 요청·승인 시각, 영수증 URL을 담습니다. 카드·계좌이체·가상계좌·간편결제·현금영수증 상세 정보는 해당 수단에서만 채웁니다. 토스와 나이스페이 구현은 검증된 승인·조회 응답을 이 공통 결과로 변환합니다. 결제 금액과 주문번호는 Processor 입력과 PG 응답을 대조하며 결과 DTO에 중복 저장하지 않습니다. `PaymentMethodType`은 결제수단 enum, `PaymentMethod`는 현재 결제수단의 DB 상세 정보를 묶는 임베디드 클래스입니다.

주요 테이블은 주문별 현재 결제 상태 한 행을 보관하는 `payment`, 상태 변경을 기록하는 `payment_history`, 실제 PG 호출 결과를 기록하는 `payment_api_log`입니다. 새로 만드는 DB에는 `orderId` 고유 제약을 적용합니다. 기존 DB에서 빠진 `externalPaymentId`·`transactionId` 컬럼은 `schema.sql`이 조건부로 추가합니다. 기존 인덱스와 고유 제약은 `CREATE TABLE IF NOT EXISTS`로 바뀌지 않으므로 중복 주문 행 확인과 별도 반영이 필요합니다.

`payment`에는 현재 시도의 영수증 URL과 화면에서 다시 보여줄 카드·이체·가상계좌·간편결제·현금영수증 핵심 정보를 nullable 컬럼으로 저장합니다. JPA의 `PaymentMethod` 임베디드 클래스로 이 컬럼들을 묶고, 재준비 시 이전 시도 정보를 비웁니다. 기존 DB에는 `schema.sql`이 누락된 컬럼을 조건부로 추가하고, 사용하지 않는 `merchantId` 컬럼은 조건부로 제거합니다. 결제 API는 소유자 확인 후 `paymentMethodDetails`로 저장된 안내 정보를 반환합니다. PG 호출별 API 로그에는 상세 결제수단 정보나 인증 헤더를 저장하지 않습니다.

`payment.id`는 내부 PK, `payment.orderId`는 주문 ID, `payment.externalPaymentId`는 PG 결제 키입니다. 이력·API 로그의 `paymentId`는 내부 결제 PK를 참조하며, PG 결제 키는 `externalPaymentId`, PG 거래 키는 `transactionId`에 저장합니다. 이력·API 로그는 각 행의 `id`만 PK로 사용하고 결제 참조값과 PG 키에는 고유 제약을 두지 않습니다.

결제 API 응답의 `PaymentResponse.id`는 JavaScript의 안전한 정수 범위를 넘을 수 있어 JSON 문자열로 전달합니다. 서버 내부의 `Payment.id`와 경로 변수 `paymentId`는 `Long`으로 유지합니다. React 화면은 응답 ID를 숫자로 변환하지 않고 조회·재결제 URL에 사용합니다.

## 로컬 실행

1. Java 25와 Docker 실행 환경을 준비합니다.
2. `src/main/resources/application.yaml`의 `payments.toss`·`payments.nice` 설정을 확인합니다. 토스는 문서용 위젯 테스트 키 쌍(`test_gck`/`test_gsk`), 나이스페이는 Server 승인 테스트 키와 샌드박스 API 주소를 사용합니다.
3. 프로젝트 루트에서 `./gradlew bootRun`을 실행합니다. Windows PowerShell에서는 `.\gradlew.bat bootRun`을 사용합니다.

애플리케이션 포트는 `8080`입니다. `compose.yml`에 MySQL과 Redis가 정의되어 있고 Spring Boot Docker Compose 연동이 활성화되어 있습니다. MySQL은 로컬 `3306` 포트를 사용합니다. 테이블 생성과 결제 식별자 컬럼의 조건부 보완 SQL은 `src/main/resources/sql/schema.sql`에 있습니다. Hibernate 물리적 명명 전략은 스키마의 camelCase 컬럼명을 그대로 사용하도록 설정하며, `User` 엔티티는 실제 테이블 이름 `user`를 명시합니다. 기존 테이블의 다른 컬럼·인덱스·제약 변경은 별도 반영이 필요합니다.

Hibernate SQL의 `?`에 전달되는 값은 `org.hibernate.orm.jdbc.bind=TRACE`로 SQL 다음에 별도 바인딩 로그로 표시합니다. SQL 문자열 자체의 `?`가 값으로 치환되지는 않습니다.

React 테스트 화면은 백엔드를 실행한 다음 `frontend`에서 `npm ci`, `npm run dev`로 시작합니다. 화면은 Vite 개발 서버의 `/apis` 프록시를 통해 백엔드에 요청하므로 로그인 JWT 쿠키를 같은 출처로 전달합니다. 백엔드 `WebConfig`는 Vite의 `http://localhost:5173` 출처를 허용합니다. 로그인·회원가입, PG 선택 후 `ready`, 토스 결제위젯 또는 나이스페이 결제창 요청, 성공 URL의 승인 요청, 실패 화면, 본인 결제 목록·단건 조회와 재시도 버튼을 제공합니다. `frontend/.env.local`의 공개 `VITE_TOSS_CLIENT_KEY`·`VITE_NICE_CLIENT_KEY`를 읽고, PG 시크릿 키는 백엔드에만 둡니다. NICEPAY의 인증 결과 POST를 받는 `/nice/return`은 Vite 개발 서버 미들웨어로 구현되어 있으므로 이 테스트 흐름은 `npm run dev`에서 실행합니다. 정적 파일만 배포하는 환경은 이 POST를 받을 프런트 서버 경로가 별도로 필요합니다.

결제 테이블의 신규 생성 구조와 확인된 구형 결제 식별자 컬럼 보완은 `src/main/resources/sql/schema.sql`을 기준으로 관리합니다.

## 테스트

```powershell
.\gradlew.bat test
```

- `PaymentFlowIntegrationTest`: H2와 Spring Boot 컨텍스트로 준비·승인·실패·대기·재결제·재시도 흐름 및 카드 상세 정보 저장을 검증하고 토스 외부 호출만 모킹합니다.
- `PaymentSchedulerTest`: 재조회 대상과 100건이 넘는 완료 이벤트 재전달을 검증합니다.
- `PaymentCompletedEventListenerTest`: 완료 이벤트를 받으면 `PENDING`까지만 기록하는지 확인합니다.
- `TossPaymentProcessorTest`: 토스 응답의 결제 상태·금액·식별자와 카드·가상계좌 상세 변환을 검증합니다.
- `NicePaymentProcessorTest`: NICEPAY 인증 서명, 승인·조회 상태, 결제 식별자·금액과 카드·가상계좌 변환을 검증합니다.
- `TossPaymentApiTest`: 토스 API 클라이언트 호출을 다룹니다. 실제 API를 호출하는 테스트는 입력값과 키 설정을 먼저 확인해야 합니다.
- `frontend`: `npm run build`, `npm run lint`, `node --test test/nice-return.test.js`로 빌드·정적 검사와 NICEPAY 인증 결과 POST 전달을 확인합니다.

## 사용처 연결과 현재 구현 범위

- 금액 계산 클래스는 아직 `orderId`로 주문을 조회하지 않고 임시 금액 `10,000`원을 반환합니다. 실제 주문 테이블 또는 주문 서비스에서 확정 금액을 계산하도록 연결해야 합니다.
- `ready`와 `payment` 모두 이 임시 금액을 사용합니다. 실제 주문 연동 전에는 금액 검증이 완성된 상태가 아닙니다.
- 완료 이벤트 수신부는 `orderStatus`를 `PENDING`으로 기록하고, 미완료 이벤트는 스케줄러가 재전달합니다. 실제 주문·상품 반영과 성공 후 `COMPLETE` 전환은 이 모듈을 사용하는 업무가 별도 트랜잭션에서 연결합니다.
- 가상계좌 입금 완료 웹훅 수신부는 없습니다. 현재는 `WAITING_FOR_PAYMENT`를 포함한 미확정 결제를 스케줄러가 PG에 조회해 상태를 확인합니다.

## 날짜별 문제와 해결 기록

날짜는 해당 구현의 Git 커밋 날짜를 기준으로 했으며, 미커밋 작업은 작업 날짜를 표시합니다. `결과`는 구현된 코드의 동작을 뜻하며 테스트 통과를 의미하지 않습니다. 각 항목의 `당시 대화`는 해당 수정 범위의 사용자·AI 메시지만 발췌한 텍스트이며, 비속어는 표시 없이 삭제했습니다.

### 2026-11-12 · 결제 준비와 중복 요청

1. **문제 사항:** SDK 실행 전 결제 대상을 확정해야 하지만, 새로고침·결제창 종료·중복 탭으로 같은 주문의 요청이 반복될 수 있습니다.
2. **원인:** `ready` 이후 SDK를 완료하지 않아도 우리 서버의 준비 기록은 남고, 두 탭이 동시에 승인 API를 호출할 수 있습니다.
3. **생각과 해결 방향:** `ready`는 실제 PG 결제가 아닌 서버 내부 준비로 보고, 상품이 아닌 `orderId`로 결제를 식별합니다. 금액은 요청값이 아니라 서버에서 계산하고, 미완료 준비는 재사용하되 실패한 시도는 보존하기로 했습니다.
4. **해결안:** `ready`와 승인 API를 분리했습니다. 같은 주문의 `READY`는 금액을 갱신하고, `FAILED` 뒤에는 새 시도를 만듭니다. 진행 가능한 주문의 고유 제약과 결제 행 잠금으로 승인 진입을 직렬화했습니다.
5. **결과:** 새로고침은 기존 `READY`를 재사용하고, 실패 이력은 남습니다. 주문 연동 전 금액 계산기는 임시 값이므로 실제 주문 금액 연결은 별도 작업입니다.

**당시 대화:** [결제 준비와 중복 요청](docs/conversations/ready-and-duplicate-requests.txt)

### 2026-11-12 · PG 호출과 DB 트랜잭션

1. **문제 사항:** PG 응답을 기다리는 동안 결제 행을 잠그면 같은 주문의 후속 요청이 오래 대기할 수 있습니다.
2. **원인:** 네트워크 호출 시간은 DB 트랜잭션의 완료 시점과 다르게 움직입니다.
3. **생각과 해결 방향:** 중복 승인을 막는 상태와 결제 키는 PG 호출 전에 커밋하고, PG 호출은 트랜잭션 밖에서 실행하기로 했습니다.
4. **해결안:** 호출 전 `PROCESSING` 저장과 호출 후 결과 저장을 `PaymentTransactionService`의 별도 트랜잭션으로 분리했습니다.
5. **결과:** PG 통신 중에는 결제 행 잠금을 유지하지 않습니다. 호출과 결과 저장 사이에 중단될 수 있으므로 결과 조회를 통한 복구가 필요합니다.

**당시 대화:** [PG 호출과 DB 트랜잭션](docs/conversations/pg-call-and-transaction.txt)

### 2026-11-13 · 승인 응답 유실과 상태 복구

1. **문제 사항:** 승인 요청 중 서버가 끊기거나 응답 저장에 실패하면 로컬 상태만으로 성공·실패를 알 수 없습니다.
2. **원인:** PG 처리와 우리 DB 저장은 하나의 트랜잭션으로 묶을 수 없습니다.
3. **생각과 해결 방향:** 불확실한 결제를 곧바로 실패로 바꾸거나 새 승인을 보내지 않고, 최초 결제 키로 PG에 실제 결과를 확인하기로 했습니다.
4. **해결안:** `PROCESSING`·`UNKNOWN` 재진입 시 PG를 조회하고, 응답의 주문번호·금액·결제 키를 저장값과 대조한 뒤 상태·이력·API 로그를 기록하도록 했습니다.
5. **결과:** PG 조회가 성공하면 확인된 상태로 복구하고, 통신 오류 등 조회 결과가 불확실하면 기존 상태를 유지합니다.

**당시 대화:** [승인 응답 유실과 상태 복구](docs/conversations/approval-response-recovery.txt)

### 2026-11-13 · 결제 완료 후 주문 반영

1. **문제 사항:** 결제 성공 뒤 완료 이벤트가 누락되거나 주문 반영이 실패할 수 있습니다.
2. **원인:** 결제 모듈은 사용처의 주문·상품 구조를 알 수 없고, 이벤트 수신 측의 처리는 별도 트랜잭션에서 일어납니다.
3. **생각과 해결 방향:** 결제 확정은 동기로 끝내고, 주문 반영은 도메인 중립적인 이벤트를 받은 사용처가 맡도록 경계를 정했습니다. 누락 여부는 별도 `orderStatus`로 추적합니다.
4. **해결안:** 성공 커밋 후 완료 이벤트를 발행하고, `BEFORE`·`PENDING` 중 오래된 건은 스케줄러가 다시 전달합니다.
5. **결과:** 결제 모듈은 미완료 건을 다시 알릴 수 있습니다. 실제 주문 반영과 `COMPLETE` 전환은 사용처가 연결해야 하는 의도적인 모듈 경계입니다.

**당시 대화:** [결제 완료 후 주문 반영](docs/conversations/order-completion-follow-up.txt)

### 2026-11-13 · PG 오류와 결과 저장

1. **문제 사항:** PG API 오류, 정상 응답의 결제 실패 상태, DB 저장 예외를 같은 실패로 다루면 원인과 기록 기준이 흐려집니다.
2. **원인:** HTTP 호출 결과와 결제 상태는 서로 다른 정보이고, PG 응답을 받은 뒤에도 DB 저장이 실패할 수 있습니다.
3. **생각과 해결 방향:** PG API 오류만 예외로 전달하고, 정상 응답에 담긴 결제 상태는 서비스가 처리하도록 구분했습니다.
4. **해결안:** `PaymentException`과 일반 예외를 분리하고, 결제 상태·`payment_history`·`payment_api_log` 저장은 `writeResult()`로 통일했습니다.
5. **결과:** PG 오류와 상태 분기의 처리 위치가 분명해졌고, PG 응답 뒤 DB 오류를 다시 PG 통신 오류로 기록하지 않습니다.

**당시 대화:** [PG 오류와 결과 저장](docs/conversations/pg-errors-and-result-storage.txt)

### 2026-11-17 · 결제 완료 대기 상태

1. **문제 사항:** `PROCESSING`에 승인 처리 중과 PG가 확인한 결제 완료 대기가 함께 들어 있어 상태 의미가 모호했습니다.
2. **원인:** 토스의 입금 대기를 공통 `PROCESSING`으로 매핑해 두 상태를 구분하지 않았습니다.
3. **생각과 해결 방향:** 특정 PG나 가상계좌에 한정하지 않고, PG가 접수했지만 최종 완료를 기다리는 공통 상태를 두기로 했습니다.
4. **해결안:** `WAITING_FOR_PAYMENT`를 추가하고 토스의 `WAITING_FOR_DEPOSIT`을 매핑했습니다. PG 조회에서 대기가 확인되면 `UNKNOWN`에서도 이 상태로 전환합니다.
5. **결과:** 승인 처리 중, 결과 미확인, PG가 확인한 결제 대기를 구분합니다. 결제 완료 조회 결과가 오면 `SUCCESS`로 전환합니다.

**당시 대화:** [결제 완료 대기 상태](docs/conversations/waiting-for-payment.txt)

### 2026-11-17 · 결제 부재 404 재조회

1. **문제 사항:** PG 조회의 첫 404만으로 결제 부재를 확정하면 조회 시점 차이로 결과를 놓칠 수 있습니다.
2. **원인:** `ready`는 우리 서버의 준비 기록이며, PG 결제 정보는 SDK와 승인 흐름 이후에 조회됩니다.
3. **생각과 해결 방향:** 명시적인 결제 부재만 짧은 간격으로 재확인하고, 계속 없으면 해당 시도를 실패로 남겨 다시 준비할 수 있게 하기로 했습니다.
4. **해결안:** 같은 결제 키로 5초 간격 두 번 더 조회합니다. 세 번 모두 결제 부재이면 `FAILED`로 저장합니다. 중간에 결제가 확인되거나 통신 오류가 나면 그 결과에 맞춰 처리합니다.
5. **결과:** 새 `ready`가 가능한 시점을 정했고, 세 번의 PG 조회는 각각 API 로그로 추적할 수 있습니다.

**당시 대화:** [결제 부재 404 재조회](docs/conversations/payment-not-found-retry.txt)

### 2026-11-17 · enum 값의 공통 매핑

1. **문제 사항:** 토스 결제수단의 공통 타입 변환과 PG 결과 재조회 대상 상태가 사용하는 코드의 `switch`·조건문에 흩어져 있었습니다.
2. **원인:** 결제수단 매핑은 `TossPaymentProcessor`가, 재조회 대상 여부는 서비스와 스케줄러가 각각 직접 판단했습니다.
3. **생각과 해결 방향:** enum 상수마다 변하지 않는 매핑과 속성은 enum 필드에 두고, 사용하는 곳에서 바로 꺼내 쓰기로 했습니다. PG 응답 처리처럼 호출 맥락에 따라 달라지는 분기는 그대로 둡니다.
4. **해결안:** `TossPaymentMethod`에 공통 `PaymentMethod`를, `PaymentStatus`에 재조회 대상 여부를 넣었습니다. 서비스와 스케줄러는 enum의 값과 대상 목록을 사용합니다.
5. **결과:** 새 결제수단이나 상태를 추가할 때 매핑·재조회 여부를 해당 enum에서 확인할 수 있습니다. 공통 결제수단이 아직 없는 토스 값은 기존처럼 `null`로 둡니다.

**당시 대화:** [enum 값의 공통 매핑](docs/conversations/enum-common-mapping.txt)

### 2026-11-17 · 결제 상태별 이력 유형

1. **문제 사항:** `PaymentStatus`의 `failureHistoryType`에는 실패 계열만 매핑되고, 성공·대기 이력 유형은 결과 저장 코드에 직접 적혀 있었습니다.
2. **원인:** PG 결과의 상태별 이력 유형을 enum과 서비스가 나누어 관리했습니다.
3. **생각과 해결 방향:** 상태에 대응하는 이력 유형은 `PaymentStatus`에서 꺼내 쓰되, 같은 `PROCESSING` 상태에서도 승인 요청 시작과 PG 응답 대기는 다른 이력이므로 요청 시작은 별도로 기록하기로 했습니다.
4. **해결안:** `READY`·`PROCESSING`·`WAITING_FOR_PAYMENT`·`SUCCESS`와 실패 계열에 `paymentHistoryType`을 정의하고, 결제 생성과 PG 결과 저장에서 사용합니다. 승인 시작의 `PAYMENT_REQUESTED`는 명시적으로 유지합니다.
5. **결과:** 상태별 기본 이력 유형을 enum에서 확인할 수 있고, PG 결과 저장의 세 분기는 같은 이력 저장 경로를 사용합니다.

**당시 대화:** [결제 상태별 이력 유형](docs/conversations/payment-status-history-mapping.txt)

### 2026-11-20 · React 결제 테스트 화면과 조회 API

1. **문제 사항:** 브라우저에서 로그인부터 SDK 결제 요청·서버 승인·저장 결과 조회까지 한 흐름으로 시험할 화면과 결제 조회 API가 없었습니다.
2. **원인:** 백엔드에는 `ready`와 승인 API만 있었고, 토스 클라이언트 키는 서버 설정에만 있었습니다.
3. **생각과 해결 방향:** React JSX 화면을 경로별로 나누고 Axios와 Vite 프록시로 로그인 쿠키를 전달합니다. 브라우저에는 공개 클라이언트 키만 제공하고, 결제 조회는 현재 사용자로 제한합니다.
4. **해결안:** `frontend`에 로그인·결제·성공·실패·목록·상세 화면을 추가했습니다. 백엔드에는 토스 클라이언트 키, 사용자별 결제 목록과 단건 조회 API를 연결했습니다.
5. **결과:** 프런트 빌드와 린트, 백엔드 컴파일을 확인했습니다. 실제 PG 승인 흐름은 Docker·DB 및 토스 테스트 키를 갖춘 환경에서 확인해야 합니다.

**당시 대화:** [React 결제 테스트 화면](docs/conversations/react-payment-test-frontend.txt)

### 2026-11-23 · 실패한 결제의 같은 행 재준비

1. **문제 사항:** `FAILED` 뒤 같은 주문으로 `ready`를 호출하면 새 `Payment` 행이 생겨 주문의 현재 결제가 여러 행으로 나뉘었습니다.
2. **원인:** 실패 시 `activeOrderId`를 비우고, `ready`가 진행 가능한 행만 조회했습니다.
3. **생각과 해결 방향:** `Payment`는 주문별 현재 상태 한 행으로 두고 이전 시도의 상태와 PG 호출 결과는 이력·API 로그에 보존합니다. 결과가 불확실한 `UNKNOWN`은 재준비하지 않습니다.
4. **해결안:** `ready`가 주문 행을 잠가 `FAILED`를 같은 행의 `READY`로 전환하고 현재 PG 키·멱등키 등 시도 정보를 초기화합니다. 새 스키마에는 `orderId` 고유 제약을 정의합니다.
5. **결과:** 실패가 확정된 주문은 같은 내부 결제 `id`로 다시 준비합니다. 기존 DB에는 고유 제약을 별도로 반영해야 합니다.

**당시 대화:** [실패한 결제의 재준비](docs/conversations/ready-reuse-current-payment.txt)

### 2026-11-23 · 결제 참조 ID와 PG 식별자 구분

1. **문제 사항:** 이력·API 로그의 내부 결제 참조 컬럼이 `paymentPk`로 바뀌어 기존 `paymentId` 의미가 흐려졌습니다.
2. **원인:** PG 결제 키에도 `paymentId`를 사용하면서 한 행의 두 식별자 이름이 충돌했습니다.
3. **생각과 해결 방향:** 이력·로그의 `paymentId`는 내부 `Payment.id` 참조값으로 유지하고, PG 결제 키에는 별도 이름을 사용합니다. 여러 이력과 호출 로그가 한 결제에 연결되므로 이 컬럼들에는 고유 제약을 두지 않습니다.
4. **해결안:** 이력·로그의 내부 참조를 `paymentId`로 복원하고 PG 결제 키를 `pgPaymentId`로 구분했습니다. 거래 키는 `transactionId`에 저장하며 엔티티·저장 코드·스키마·검증 코드를 맞췄습니다.
5. **결과:** 이력과 API 로그는 각 행의 `id`만 PK이며 동일한 내부 결제 ID와 PG 키를 여러 행에 기록할 수 있습니다. 기존 DB의 컬럼 변경은 별도 반영이 필요합니다.

**당시 대화:** [결제 식별자 명명](docs/conversations/payment-identifiers.txt)

### 2026-11-23 · 외부 결제 식별자 명칭 통일

1. **문제 사항:** PG 결제 식별자를 `Payment.paymentId`와 이력·로그의 `pgPaymentId`로 달리 표현해 내부 참조 `paymentId`와 혼동하기 쉬웠습니다.
2. **원인:** `paymentId`가 테이블마다 내부 PK 참조와 PG 결제 키를 뜻했고, 이를 피하려 붙인 `pg` 접두사도 명확성이 부족했습니다.
3. **생각과 해결 방향:** 내부 PK는 `Payment.id`, 이력·로그의 내부 참조는 `paymentId`로 유지하고 PG 결제 식별자는 모든 계층에서 `externalPaymentId`로 부릅니다.
4. **해결안:** 엔티티, 스키마, 승인 요청·응답, PG 결과, 저장·복구 코드, 프런트엔드 전송 필드와 테스트를 같은 이름으로 변경했습니다. 토스 SDK의 `paymentKey`는 프런트엔드에서 매핑합니다.
5. **결과:** 내부 참조와 PG 결제 키가 구분됩니다. 기존 DB에서는 `CREATE TABLE IF NOT EXISTS`가 컬럼을 변경하지 않으므로 별도 스키마 반영이 필요합니다.

**당시 대화:** [외부 결제 식별자 명칭](docs/conversations/external-payment-id.txt)

### 2026-11-23 · 결제 ID 기반 재결제 준비

1. **문제 사항:** 실패한 결제는 주문 ID로 `ready`를 다시 호출할 수 있었지만, 저장된 결제 ID에서 재결제를 시작할 API가 없었습니다. `UNKNOWN`을 바로 재시도하면 실제 승인 결과와 겹칠 수 있었습니다.
2. **원인:** 결과 복구는 스케줄러와 동일 결제 키의 재진입에 연결돼 있었고, 사용자가 결제 ID로 즉시 결과를 확인하고 새 시도를 여는 흐름은 분리돼 있지 않았습니다.
3. **생각과 해결 방향:** 재결제 요청 시 본인 결제의 상태를 확인합니다. `UNKNOWN`은 기존 키로 PG를 먼저 조회해 성공을 반환하거나 실패를 확정하고, 불확실한 경우에는 새 시도를 막습니다. 새 SDK 인증은 준비 이후 브라우저에서 진행합니다.
4. **해결안:** 결제 ID 기반 재결제 준비 API와 상태 확인 로직을 추가했습니다. 실패가 확정되면 같은 행을 잠가 금액·멱등키를 새로 준비하고 이력을 저장합니다. PG 조회 뒤에는 별도 조회 없이 재준비 트랜잭션의 행 잠금 안에서 최신 상태를 확인합니다. 테스트 화면에서는 결제 상세의 재시도 버튼으로 이 흐름을 시작합니다.
5. **결과:** 확정 실패는 새 `READY`로, 기존 성공은 기존 성공 결과로 처리합니다. 불확실한 결제는 `UNKNOWN`을 유지합니다. 실제 PG 연동 검증은 실행 환경에서 확인해야 합니다.

**당시 대화:** [결제 ID 기반 재결제 논의](docs/conversations/payment-retry-by-id.txt)

### 2026-11-25 · PG 처리기 입력을 엔티티에서 분리

1. **문제 사항:** `PaymentProcessor`의 승인·조회 계약이 JPA `Payment` 엔티티를 직접 받았습니다.
2. **원인:** PG 구현이 저장 엔티티의 필드를 바로 읽도록 연결돼 처리기 입력을 별도 모듈로 옮기기 어려웠습니다.
3. **생각과 해결 방향:** 모든 PG가 공통으로 받는 입력 DTO에 결제 행의 스칼라 값과 감사 필드를 모두 전달합니다. JPA 연관 `User` 객체는 넘기지 않고 저장된 `userId`와 `loginId`를 사용합니다.
4. **해결안:** `PaymentProcessorData`를 추가하고 서비스에서 엔티티 값을 복사해 승인·조회에 전달했습니다. `PaymentProcessor`, 토스 구현, 단위 테스트의 입력 타입을 변경했습니다.
5. **결과:** Processor의 승인·조회 메서드가 `Payment` 엔티티를 직접 참조하지 않습니다. 다른 입력 DTO와 공통 예외의 모듈 의존성은 이후 라이브러리 구성 시 별도로 판단합니다.

**당시 대화:** [PG 처리기 입력 DTO](docs/conversations/payment-processor-data.txt)

### 2026-11-25 · Spring Boot 결제 라이브러리 방향

1. **문제 사항:** 현재 프로젝트가 PG 처리기 분리를 시작했지만 라이브러리화 목표와 완료 시점이 문서에 명시되지 않았습니다.
2. **원인:** 단일 Spring Boot 프로젝트에서 토스 처리기만 구현한 상태라 공통 계약과 PG별 차이를 검증할 사례가 아직 하나뿐입니다.
3. **생각과 해결 방향:** Spring Framework 의존성을 허용하는 결제 라이브러리를 목표로 합니다. PG사가 더 추가되면 공통 처리와 PG별 구현의 경계를 실제 코드로 확인해 라이브러리 분리를 진행합니다.
4. **해결안:** 현재의 `PaymentProcessor`·`PaymentProcessorData` 경계, 별도 JAR·자동 설정이 아직 없는 점, 향후 PG 구현·설정·사용처 연결 지점의 분리 계획을 설계 항목에 기록했습니다.
5. **결과:** 현재 구현과 앞으로의 라이브러리화 계획을 구분해 확인할 수 있습니다. 이번 항목은 설계 기록이며 라이브러리 빌드 구성은 변경하지 않았습니다.

**당시 대화:** [Spring Boot 결제 라이브러리 방향](docs/conversations/payment-library-direction.txt)

### 2026-11-25 · PG HTTP 메서드를 호출 결과에 기록

1. **문제 사항:** Processor 계약의 `requestMethod()`는 승인 호출의 HTTP 메서드를 API 로그에 적기 위해서만 사용됐습니다.
2. **원인:** 승인과 조회가 각각 다른 HTTP 메서드를 쓸 수 있는데, 호출 결과와 별도로 Processor에서 메서드를 다시 조회했습니다.
3. **생각과 해결 방향:** PG 구현이 실제 호출 메서드를 공통 결과에 포함하고 로그 저장은 그 값을 사용합니다. PG 결과가 없는 예외에서는 메서드를 추정하지 않습니다.
4. **해결안:** Processor의 `requestMethod()`를 제거하고 토스 승인·조회 호출 정보에 각각 `POST`·`GET`을 넣었습니다. 이후 호출별 정보를 아래 `PaymentProcessResult.apiCalls`로 묶었습니다. 메서드 미확인 로그는 `UNKNOWN`으로 기록합니다.
5. **결과:** API 로그의 HTTP 메서드가 호출별 결과를 따릅니다. 예상 밖 예외의 메서드는 확인 불가로 구분합니다.

**당시 대화:** [PG HTTP 메서드 기록](docs/conversations/payment-http-method-result.txt)

### 2026-11-25 · PG 요청 URL을 호출 결과에 기록

1. **문제 사항:** Processor 계약의 `paymentUrl()`과 `lookupUrl()`을 API 로그 저장을 위해 별도로 호출했습니다.
2. **원인:** 실제 호출 URL이 PG 요청 결과와 떨어져 있어 조회 경로의 결제 키도 로그에 반영되지 않았습니다.
3. **생각과 해결 방향:** 한 결제 처리에서 여러 PG API를 호출할 수 있으므로 호출 정보를 시간순 목록으로 보관하고, 로그 저장은 각 호출 항목을 참조합니다.
4. **해결안:** `PaymentProcessResult.apiCalls`와 호출별 `PaymentApiCallResult`를 추가하고 토스 승인·조회 및 PG 오류 결과에 요청 URL·메서드·응답·시각을 채웠습니다. Processor의 URL 메서드를 제거하고, 결과 생성 전 예외의 로그에는 `UNKNOWN`을 기록합니다.
5. **결과:** 토스 조회 로그에는 결제 키가 포함된 요청 URL이 남습니다. 여러 호출 항목은 순서대로 개별 로그 행이 되며, `writeResult()`는 Processor를 전달받지 않아도 됩니다.

**당시 대화:** [PG 요청 URL 기록](docs/conversations/payment-request-url-result.txt)

### 2026-11-25 · PG 호출별 요청·응답 JSON 기록

1. **문제 사항:** `payment_api_log`에 요청·응답 본문 컬럼이 있지만 실제 결제 호출에서는 값을 저장하지 않았습니다.
2. **원인:** `PaymentApiCallResult`가 호출별 메서드·URL·상태만 전달했고 요청·응답 DTO의 로그용 표현은 만들지 않았습니다.
3. **생각과 해결 방향:** 여러 종류의 PG API를 호출할 수 있으므로 각 호출 항목에 요청·응답 JSON을 넣습니다. 저장할 필드는 DTO에서 선별해 민감한 원문이 로그에 들어가지 않게 합니다.
4. **해결안:** 토스 승인 요청에서 주문번호·금액, 조회·승인 응답에서 주문번호·금액·상태·수단·승인 시각을 JSON으로 만들고 API 로그 본문 컬럼에 저장합니다. 오류 결과는 코드·메시지를 JSON으로 기록합니다.
5. **결과:** 결제 흐름에서 호출한 토스 API의 요청·응답 내용을 로그에서 확인할 수 있습니다. `TossPaymentApi`를 Processor 밖에서 직접 호출한 경우는 현재 이 DB 로그 경로를 거치지 않습니다.

**당시 대화:** [PG 요청·응답 JSON 기록](docs/conversations/payment-api-body-log.txt)

### 2026-11-25 · 기존 결제 테이블의 누락 컬럼 보완

1. **문제 사항:** 결제 테이블을 조회하는 SQL에서 `payment.externalPaymentId`가 없다는 JDBC 오류가 발생했습니다.
2. **원인:** 현재 엔티티와 `schema.sql`의 `CREATE TABLE`에는 컬럼이 있지만 기존 로컬 MySQL 테이블에는 이전 이름의 컬럼만 남아 있었습니다. `CREATE TABLE IF NOT EXISTS`는 기존 테이블에 새 컬럼을 추가하지 않습니다.
3. **생각과 해결 방향:** 실제 로컬 DB의 세 결제 테이블 컬럼을 확인하고, 누락된 결제 식별자 컬럼만 `schema.sql`에서 재실행 가능하게 보완합니다. 기존 데이터와 인덱스는 이번 컬럼 추가와 분리합니다.
4. **해결안:** `payment.externalPaymentId`, `payment_history.externalPaymentId`·`transactionId`, `payment_api_log.externalPaymentId`가 없을 때만 추가하도록 조건부 SQL을 넣었습니다.
5. **결과:** 복사한 구형 테이블에 SQL을 두 차례 실행해 네 컬럼이 각각 한 번씩 생성되는 것을 확인했습니다. 실제 `payment` DB에는 아직 적용하지 않았고, 기존 인덱스·고유 제약은 그대로입니다.

**당시 대화:** [기존 결제 테이블 컬럼 오류](docs/conversations/payment-schema-missing-columns.txt)

### 2026-11-25 · Vite 회원가입 요청의 CORS 오류

1. **문제 사항:** Vite의 `/apis/v3/auth/register` 요청이 HTTP 403과 `Invalid CORS request`로 거부됐습니다.
2. **원인:** Vite 프록시가 전달한 `http://localhost:5173` 출처가 백엔드 `WebConfig`의 허용 목록에 없었습니다. 기존 허용값은 `http://localhost:3000`뿐이었습니다.
3. **생각과 해결 방향:** Vite 테스트 화면의 출처를 백엔드 허용 목록에 추가합니다. 개발 프록시가 `Origin` 헤더를 제거하도록 잠시 넣은 변경은 원복합니다.
4. **해결안:** `WebConfig`에 `http://localhost:5173`을 추가했습니다.
5. **결과:** 허용 출처 설정은 수정됐습니다. 백엔드가 실행 중이지 않아 HTTP 요청으로 결과를 확인하지는 못했습니다.

**당시 대화:** [Vite 회원가입 CORS 오류](docs/conversations/vite-register-cors.txt)

### 2026-11-25 · 사용자 테이블 매핑 오류

1. **문제 사항:** 사용자 조회 시 `Table 'payment.User' doesn't exist` 오류가 발생했습니다.
2. **원인:** JDBC URL의 `payment`는 DB 이름입니다. 기존 `User` 엔티티의 `@Table`에는 이름이 없고 물리적 명명 전략은 대문자를 유지하지만, `schema.sql`과 실제 로컬 DB의 테이블 이름은 소문자 `user`입니다.
3. **생각과 해결 방향:** DB 이름이나 테이블을 새로 만들지 않고 엔티티 매핑을 실제 테이블 이름에 맞춥니다.
4. **해결안:** `User` 엔티티에 `@Table(name = "user")`를 명시했습니다.
5. **결과:** 엔티티가 `payment.user` 테이블을 가리키도록 변경했습니다. 백엔드 HTTP 실행 결과는 아직 확인하지 않았습니다.

**당시 대화:** [사용자 테이블 매핑 오류](docs/conversations/user-table-name.txt)

### 2026-11-25 · 결제창 버튼 비활성화와 프런트 키 설정

1. **문제 사항:** `ready`가 성공한 뒤에도 결제창 버튼이 비활성화되어 대기 상태처럼 보였습니다.
2. **원인:** 버튼 활성 조건은 `clientKey`였고, 기존 화면은 초기 렌더링 때 백엔드 키 조회 API를 한 번 호출했습니다. 키 조회 실패·빈 값 안내는 이후 `ready` 성공 메시지에 덮일 수 있었습니다.
3. **생각과 해결 방향:** 브라우저에서 쓰는 공개 클라이언트 키는 Vite의 환경 설정으로 직접 전달하고, 키가 없을 때는 준비 성공 메시지와 별도로 안내합니다. 백엔드의 시크릿 키는 프런트에 넣지 않습니다.
4. **해결안:** `frontend/.env.local`과 설정 예시 파일 `.env.example`에 `VITE_TOSS_CLIENT_KEY` 항목을 마련하고, 결제 화면이 이를 읽도록 했습니다. 사용하지 않는 프런트 키 조회 함수를 제거했습니다.
5. **결과:** 공개 키를 직접 입력하고 Vite를 재시작하면 결제창 버튼이 활성화됩니다. 백엔드의 키 조회 API는 다른 사용처를 위해 유지합니다.

**당시 대화:** [프런트 결제 키 설정](docs/conversations/frontend-toss-client-key.txt)

### 2026-11-25 · 결제창 호출의 키 종류 오류

1. **문제 사항:** `결제창 열기`에서 오류가 발생했고, 개발자 도구에는 입력 필드 식별자 경고와 CORB 메시지가 보였습니다.
2. **원인:** 주문번호 입력 필드에 `id`·`name`이 없었습니다. 결제창은 SDK `payment()`를 사용하는데 현재 로컬 프런트·백엔드 설정의 키 종류는 결제위젯용 `test_gck`·`test_gsk`였습니다. 캡처된 토스 로그 요청은 HTTP 200이라 그 CORB 메시지만으로 결제 실패 원인을 확정하지 않았습니다.
3. **생각과 해결 방향:** 현재 `payment()` 흐름에는 API 개별 연동 키 `test_ck`·`test_sk`를 사용합니다. 키 값 자체는 사용자 로컬 설정에서 바꾸고, 화면은 잘못된 `gck` 키를 전달하기 전에 이유를 알립니다.
4. **해결안:** 주문번호 입력에 `id`·`name`을 추가하고, `gck` 키가 설정됐을 때 결제창 버튼을 비활성화하며 키 종류 안내를 표시했습니다.
5. **결과:** 입력 필드 경고의 원인을 제거하고, 결제위젯용 키가 `payment()`에 들어가지 않도록 했습니다. 실제 결제창 성공은 맞는 키를 입력한 뒤 확인해야 합니다.

**당시 대화:** [결제창 키 종류와 브라우저 경고](docs/conversations/toss-payment-key-type.txt)

### 2026-11-25 · 문서용 gck/gsk 키로 결제위젯 연동

1. **문제 사항:** `ready` 응답은 200이지만 결제 버튼이 비활성화되어 결제 테스트를 진행할 수 없었습니다.
2. **원인:** 화면은 위젯용 `gck` 키를 차단하면서 `payment()` 방식으로 결제 요청을 구성했습니다. 문서용 키는 위젯 연동용입니다.
3. **생각과 해결 방향:** 제공된 `gck/gsk` 키 쌍을 그대로 사용하도록 화면을 주문서형 위젯 흐름으로 전환합니다. 서버가 준비한 금액을 위젯에 설정하고 성공 URL에서 기존 서버 승인 API를 호출합니다.
4. **해결안:** `widgets()`로 결제수단과 약관을 표시한 뒤 `requestPayment()`를 호출하도록 변경하고, 키 안내와 설정 문서를 갱신했습니다.
5. **결과:** `gck` 키를 이유로 버튼을 차단하던 조건을 제거했습니다. 실제 브라우저 결제와 서버 승인 결과는 실행 환경에서 확인해야 합니다.

**당시 대화:** [문서용 키와 위젯 연동 결정](docs/conversations/toss-widget-key-decision.txt)

### 2026-11-25 · 서버 승인 키와 토스 API 로그 표시

1. **문제 사항:** 위젯 전환 뒤 서버 승인에서 시크릿 키 오류가 발생했고, 콘솔의 `Toss API request` 로그가 잘 보이지 않았습니다.
2. **원인:** 프런트는 위젯용 `gck`를 사용했지만 `payments.toss`에는 API 개별 연동용 `ck/sk`가 설정돼 있었습니다. 공통 인터셉터의 로그는 메서드·경로·상태·시간만 한 줄에 표시했습니다.
3. **생각과 해결 방향:** 프런트와 서버를 같은 위젯 테스트 키 쌍으로 맞추고, `RestClient` 공통 인터셉터에서 요청·응답을 빈 줄로 시작하는 여러 줄 로그로 표시합니다. 토스가 노출을 금지한 인증값은 가립니다.
4. **해결안:** 공개 클라이언트 키를 문서용 위젯 키로 변경하고, 서버 시크릿 키는 동일 키 쌍의 `gsk`를 환경변수로 주입하도록 했습니다. 요청·응답 헤더와 JSON 본문을 출력하고, 응답 본문을 로그 후에도 파싱할 수 있도록 버퍼링했습니다.
5. **결과:** 서버에 위젯용 시크릿 키를 설정해 승인 테스트를 진행할 수 있게 했고 로그의 내용을 구분해 볼 수 있게 했습니다. Java 컴파일은 통과했습니다. 결제 처리기 단위 테스트는 실행 환경의 Byte Buddy 에이전트 JAR 경로 오류로 완료되지 않았습니다. 이후 사용자가 실제 서버 테스트를 모두 완료했다고 확인했습니다. `orderId` 조회 API는 위젯용 `gsk`를 지원하지 않지만 현재 결제·복구 경로는 결제 키로 조회합니다.

**당시 대화:** [서버 키와 API 로그 표시](docs/conversations/toss-server-key-api-logs.txt)

### 2026-11-25 · Hibernate 바인딩 값과 결제 키 로그

1. **문제 사항:** SQL 로그의 `id = ?`에 전달된 값을 볼 수 없고 토스 API 콘솔 로그의 `paymentKey`도 가려졌습니다.
2. **원인:** Hibernate SQL 출력에는 바인딩 값 로그가 설정되지 않았습니다. 토스 API 로그의 필드 필터는 `paymentKey`와 결제수단 상세까지 가리도록 작성됐습니다.
3. **생각과 해결 방향:** Hibernate 바인딩 값을 별도 TRACE 로그로 보이게 합니다. 토스가 외부 노출을 허용한 `paymentKey`는 콘솔에 표시하고 인증에 쓰는 값만 가립니다.
4. **해결안:** `org.hibernate.orm.jdbc.bind`를 `TRACE`로 설정하고, 토스 API 로그의 필드 필터에서 결제 키와 결제수단 필드를 제외했습니다.
5. **결과:** SQL의 `?` 값은 별도 바인딩 로그에 나타나고, 토스 API 요청·응답의 `paymentKey`가 콘솔에 표시됩니다. 애플리케이션 재시작 후 실제 로그 확인이 필요합니다.

**당시 대화:** [SQL 바인딩과 결제 키 로그](docs/conversations/sql-bind-and-payment-key-log.txt)

### 2026-11-25 · 큰 결제 ID의 브라우저 정밀도 손실

1. **문제 사항:** 결제 성공 응답에서 표시한 ID로 단건 조회하면 `결제를 찾을 수 없습니다` 404가 발생했습니다.
2. **원인:** `PaymentResponse.id`가 JSON 숫자로 전달되었고, 실제 PK가 JavaScript의 안전한 정수 범위를 넘어 브라우저에서 반올림될 수 있었습니다. 조회 URL에는 반올림된 ID가 들어갈 수 있습니다.
3. **생각과 해결 방향:** DB와 서버 내부 ID는 `Long`으로 유지하고, 브라우저로 나가는 결제 응답의 ID만 JSON 문자열로 직렬화합니다.
4. **해결안:** `PaymentResponse.id`에 Jackson 3 문자열 직렬화를 지정했습니다. 큰 ID가 문자열로 유지되고 금액은 숫자로 남는 테스트를 추가했습니다.
5. **결과:** 직렬화 회귀 테스트가 통과했습니다. 이후 사용자가 실제 서버에서 테스트를 모두 완료했다고 확인했습니다.

**당시 대화:** [큰 결제 ID 조회 오류](docs/conversations/large-payment-id-lookup.txt)

결제 상태 복구와 완료 이벤트 재전달은 `PaymentScheduler`와 `schedule.payments` 설정 아래에 모았습니다. 처리량에 따른 배치 크기나 실행 분산은 실제 호출량을 본 뒤 결정하기로 했습니다.

### 2026-11-27 · PG 공통 결제수단 정보와 현재 결제 상세 저장

1. **문제 사항:** PG 승인·조회 결과가 결제 상태와 키만 공통 결과로 전달해 카드·계좌·가상계좌 안내 정보를 호출 측에서 사용할 수 없었습니다.
2. **원인:** 토스 응답 DTO에는 수단별 정보가 있었지만 `PaymentProcessResult` 변환에서 제외됐고, `payment` 행에도 현재 수단의 안내 필드가 없었습니다.
3. **생각과 해결 방향:** 여러 PG가 제공하는 의미가 같은 필드는 공통 결과로 변환해 최대한 전달합니다. DB에는 고객이 다시 조회할 핵심 정보만 저장하고, 현재 결제 한 행의 nullable 컬럼을 임베디드 클래스로 묶습니다. 당시에는 PG 가맹점 ID도 저장 대상으로 판단했습니다.
4. **해결안:** 결과에 당시 `merchantId`, PG 원본 상태·수단, 요청 시각, 영수증 URL 및 카드·계좌이체·가상계좌·간편결제·현금영수증 정보를 추가했습니다. 토스 승인·조회 변환을 연결하고, `PaymentMethod` 임베디드 클래스와 `payment` 컬럼 및 조회 응답을 추가했습니다. 기존 결제수단 enum은 `PaymentMethodType`으로 이름을 구분했습니다. 이후 `merchantId`는 아래 항목에서 제거했습니다.
5. **결과:** 토스 처리기 단위 테스트와 H2 결제 흐름 통합 테스트가 통과했습니다. 실제 MySQL의 기존 테이블에 대한 `schema.sql` 적용은 별도 확인이 필요합니다.

**당시 대화:** [PG 공통 결제수단 정보 설계](docs/conversations/payment-method-information.txt)

### 2026-11-27 · PG 가맹점 ID 저장 제거

1. **문제 사항:** 공통 결과와 `payment` 행에 가맹점 ID를 저장하지만 현재 결제 승인·조회·복구에서는 사용하지 않습니다.
2. **원인:** 토스 응답의 `mId`와 과거 NICEPAY 방식의 MID를 공통 저장 대상으로 보았으나, 이번 NICEPAY Server 승인 방식은 클라이언트 키와 시크릿 키로 상점을 특정합니다.
3. **생각과 해결 방향:** PG API 호출에 필요하지 않은 가맹점 ID를 결제 행마다 중복 저장하지 않습니다. 여러 가맹점 계정을 운영하게 되면 실제 승인에 사용한 설정을 구별할 방법을 별도로 설계합니다.
4. **해결안:** `PaymentProcessResult`, `Payment`, `PaymentResponse`와 토스 변환·저장 흐름에서 `merchantId`를 제거하고, `schema.sql`은 기존 DB의 컬럼도 조건부로 삭제합니다.
5. **결과:** 현재 결제 결과에는 PG 결제 키와 영수증·결제수단 정보만 남습니다. Java와 테스트 소스는 컴파일됐고 응답 직렬화 테스트는 통과했습니다. Mockito를 사용하는 테스트는 실행 환경의 Byte Buddy 에이전트 경로 오류로 완료하지 못했습니다. 실제 MySQL 스키마 적용 결과는 별도 확인이 필요합니다.

**당시 대화:** [PG 가맹점 ID 저장 여부](docs/conversations/merchant-id-removal.txt)

### 2026-11-27 · NICEPAY 테스트 키와 API 설정 준비

1. **문제 사항:** NICEPAY Server 승인 방식을 연동하기 위한 테스트 키와 API 주소 설정이 없었습니다.
2. **원인:** 현재 설정은 토스 PG만 정의하고, 프런트 설정에도 토스 공개 키만 있었습니다.
3. **생각과 해결 방향:** 토스와 같은 `@ConfigurationProperties` 방식으로 NICEPAY 설정을 묶고, 공개 키만 프런트 설정에 둡니다. 샌드박스의 승인 POST와 조회 GET은 같은 `/v1/payments/{tid}` 경로를 사용합니다.
4. **해결안:** `NiceApiProperties`와 `payments.nice`에 사용자가 제공한 테스트 키·샌드박스 주소·API 경로를 설정하고, 프런트 예시와 로컬 설정에 `VITE_NICE_CLIENT_KEY`를 추가했습니다.
5. **결과:** 이 시점에는 키와 경로 설정만 준비됐습니다. 이후 아래 항목에서 NICEPAY 승인·조회와 SDK 화면을 연결했습니다.

**당시 대화:** [NICEPAY 테스트 키 설정](docs/conversations/nice-test-key-configuration.txt)

### 2026-11-27 · NICEPAY Server 승인 흐름 연결

1. **문제 사항:** NICEPAY 테스트 키와 API 주소만 있어 `ready` 이후 결제창 인증과 서버 승인·복구를 실행할 수 없었습니다.
2. **원인:** `PaymentProvider`에 NICE 항목과 `PaymentProcessor` 구현이 없고, React 화면도 토스 결제위젯만 호출했습니다. NICEPAY 결제창은 인증 결과를 `returnUrl`에 POST하는 방식입니다.
3. **생각과 해결 방향:** 사용자가 정한 `ready → SDK → 기존 결제 API의 서버 검증·승인` 흐름을 유지합니다. 인증 결과는 프런트 개발 서버에서 받아 React 화면으로 전달하고, 결제 백엔드는 PG 승인 POST·조회 GET만 호출합니다.
4. **해결안:** `NicePaymentApi`·`NicePaymentProcessor`·응답 DTO·상태 매핑·서명 검증을 추가했습니다. React에 PG 선택과 NICEPAY 결제창·결과 화면을 추가하고, Vite 개발 서버가 인증 결과 POST를 받아 결과 화면으로 전달합니다. 기존 `PaymentService.payment(request)`와 사용자 인증 흐름은 변경하지 않았습니다.
5. **결과:** NICEPAY 처리기 단위 테스트 4건, 프런트 POST 전달 테스트 2건, Java 컴파일, Vite 빌드·lint가 통과했습니다. 사용자가 실제 NICEPAY 테스트 결제의 `SUCCESS` 응답과 저장된 카드 정보·영수증 URL을 확인했습니다. 이 결과만으로 실패·복구 흐름이나 실제 MySQL 스키마 변경까지 검증된 것은 아닙니다. 정적 프런트 배포에는 POST를 받을 프런트 서버 경로가 추가로 필요합니다.

**당시 대화:** [NICEPAY 승인 흐름과 프런트 응답 수신](docs/conversations/nice-payment-flow.txt)
