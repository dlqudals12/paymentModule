# paymentModule

회원 인증과 결제 준비·승인 흐름을 구현하는 Spring Boot 프로젝트입니다. 결제 모듈은 주문·상품 도메인의 후속 처리와 분리하고, PG별 승인 로직은 `PaymentProcessor`로 구분합니다. 현재 결제 흐름에 연결된 PG는 토스페이먼츠입니다.

## 기술 구성

| 구분 | 사용 기술 |
| --- | --- |
| 언어·빌드 | Java 25, Gradle 9.5.1 |
| 애플리케이션 | Spring Boot 4.1.0, Spring MVC, Spring Data JPA |
| 인증 | Spring Security, JWT, 쿠키 |
| 데이터 저장소 | MySQL 8.4, Redis 7.4 |
| PG 통신 | Spring `RestClient` |
| API 문서 | springdoc OpenAPI |
| 테스트 | JUnit 5, Spring Boot Test, H2, Mockito |

Redis 의존성과 컨테이너 설정은 포함되어 있지만, 현재 결제 승인 흐름의 상태·이력·API 로그는 MySQL에 저장합니다.

## 주요 기능

### 회원 인증

- 회원 가입, 로그인, 토큰 갱신, 로그아웃 API를 제공합니다.
- 로그인 시 액세스·리프레시 JWT를 쿠키에 설정합니다. 결제 API에는 `ROLE_USER` 메서드 보안이 적용되어 있습니다.

### 결제 준비와 승인

1. `POST /apis/v3/payments/ready`가 PG와 `orderId`를 받아 서버에서 금액을 계산하고 결제 시도를 `READY`로 저장합니다.
2. 같은 주문에 `READY` 시도가 있으면 기존 시도를 재사용하고 금액을 갱신합니다. `FAILED` 시도는 이력으로 남기고 새 시도를 생성합니다. `PROCESSING`, `UNKNOWN`, `SUCCESS` 등은 다시 준비하지 않습니다.
3. 클라이언트가 반환된 주문 정보로 PG SDK를 실행하고, 인증 결과의 `paymentKey`, `orderId`, `amount`를 결제 승인 API에 전달합니다.
4. `POST /apis/v3/payments`는 서버에서 다시 계산한 금액, 저장 금액, 전달 금액을 비교합니다. 결제 행을 잠근 짧은 트랜잭션에서 `READY → PROCESSING`으로 변경해 같은 주문의 중복 승인 요청을 막습니다.
5. DB 트랜잭션 밖에서 PG 승인 API를 호출합니다. 정상 API 응답의 결제 상태는 서비스에서 처리하고, PG API 오류는 `PaymentException`으로 전달합니다. 승인 결과를 확정할 수 없는 호출 오류는 `UNKNOWN`으로 기록합니다.
6. 같은 결제 키로 `PROCESSING` 또는 `UNKNOWN` 요청이 다시 들어오면 PG 결제를 조회합니다. 조회 성공은 주문번호·금액·결제 키를 대조해 확정합니다. 토스가 결제 정보 부재를 명시한 404는 진행 중 요청의 유예 시간이 지난 뒤 `FAILED`로 확정해 새 `ready` 시도를 허용합니다. 통신 오류 등 불확실한 조회 결과는 기존 상태를 유지합니다.
7. 성공 결과가 커밋되면 `PaymentCompletedEvent`를 발행합니다. 비동기 수신부는 `orderStatus`를 `BEFORE → PENDING`으로 변경합니다. 실제 주문 반영이 성공한 뒤 `COMPLETE`로 변경하는 연결은 TODO로 남아 있습니다.

결제 상태는 `paymentStatus`, 주문 반영 상태는 `orderStatus`로 따로 저장하고 각각의 마지막 변경 시각을 `paymentStatusChangedAt`, `orderStatusChangedAt`에 기록합니다. 스케줄러는 `BEFORE` 건에는 결제 완료 시각인 `approvedAt`을, `PENDING` 건에는 주문 반영 상태 변경 시각을 사용해 유예 시간이 지난 모든 완료 이벤트를 다시 전달합니다. 실제 상품·주문 반영 코드는 아직 연결되지 않았으므로 이벤트 수신 후에도 `orderStatus`는 `PENDING`에 남습니다.

토스 API 호출에는 시크릿 키 기반 Basic 인증, JSON 본문, 멱등키를 사용합니다. 요청·응답 로그와 DB의 API 로그에는 인증 헤더와 결제 본문 원문을 저장하지 않습니다.

## 설계 시 고려한 상황

### 주문 도메인과 결제 모듈의 경계

결제 모듈은 상품 ID나 주문 테이블 구조를 직접 결제 식별자로 사용하지 않고 `orderId`를 받습니다. 주문 서버가 결제 직전 주문과 금액을 확정하고, `ready`에서 그 금액을 서버 기준으로 읽어 저장하는 흐름을 전제로 합니다. 현재 `PaymentAmountCalculator`의 금액은 임시 값이므로, 실제 사용처의 주문 테이블 또는 주문 서비스 연동이 필요합니다. 같은 계산을 승인 직전에도 다시 실행해 준비 금액 및 SDK 전달 금액과 비교합니다. 클라이언트가 금액을 바꿔 보내거나 준비 이후 주문 금액이 달라진 경우 승인을 진행하지 않기 위한 검증입니다.

PG별 요청·응답 처리는 `PaymentProcessor`로 분리하고 `PaymentProvider`를 키로 선택합니다. 반면 결제 이후 주문·상품 반영은 모듈이 업무 도메인을 알 수 없으므로 완료 이벤트를 전달하는 지점까지만 맡습니다. 사용처가 단일 애플리케이션이든 별도 주문 서비스든, 실제 후속 처리와 그 트랜잭션은 사용처에서 연결해야 합니다.

### 새로고침, 중복 탭, 중복 승인

SDK를 열기 전에 `ready`가 결제 시도를 저장합니다. 사용자가 결제창을 닫거나 새로고침한 뒤 같은 주문으로 다시 준비하면 `READY` 시도의 서버 금액을 갱신해 재사용합니다. 이미 `FAILED`가 된 시도는 보존하고 새 시도를 생성합니다. 그 외 진행 중이거나 성공한 시도는 `ready`로 덮어쓰지 않습니다.

승인 시에는 `(provider, activeOrderId)` 고유 제약과 결제 행 잠금으로 같은 주문의 진행 가능한 시도를 하나로 제한합니다. PG 호출 전에 `PROCESSING`, 최초 SDK `paymentKey`, 요청 시각을 먼저 커밋합니다. 다른 탭이 다른 `paymentKey`로 들어오면 기존 시도를 이어받지 못하고 충돌로 처리합니다. 같은 키의 재진입은 새 승인을 보내기 전에 PG 조회로 기존 시도의 결과를 확인합니다. PG 요청에 사용하는 멱등키도 결제 시도에 저장합니다.

### 호출 중단과 응답 유실 복구

| 중단 지점 | 저장된 상태와 재진입 시 처리 |
| --- | --- |
| `PROCESSING` 커밋 후 PG 호출 전 종료 | 같은 결제 키로 다시 들어오면 PG를 조회합니다. 토스가 결제 부재를 명시한 404를 반환해도 최초 요청이 진행 중일 수 있어 유예 시간 동안 상태를 유지합니다. 유예 시간이 지난 404는 `FAILED`로 저장하고 새 `ready`를 허용합니다. |
| PG 승인 후 결과 저장 전 응답 유실·프로세스 종료 | 로컬 상태가 `PROCESSING` 또는 `UNKNOWN`이면 같은 결제 키로 PG를 조회합니다. 성공 응답은 주문번호·금액·결제 키를 확인한 뒤 `SUCCESS`로 저장합니다. 조회 통신 오류처럼 결과가 불명확하면 상태를 유지합니다. |
| `SUCCESS` 커밋 후 완료 이벤트 전달 실패 | `orderStatus=BEFORE`가 남습니다. 스케줄러가 결제 승인 시각부터 유예 시간이 지난 건의 완료 이벤트를 다시 발행합니다. |
| 이벤트 수신 후 후속 업무 미완료 | `orderStatus=PENDING`을 유지합니다. 스케줄러가 상태 변경 시각부터 유예 시간이 지난 건의 이벤트를 다시 발행합니다. 실제 업무 완료 후 사용처가 `COMPLETE`로 확정해야 합니다. |

PG 호출은 DB 트랜잭션 밖에서 실행하고, 호출 전후의 DB 변경은 `PaymentTransactionService`의 짧은 트랜잭션으로 나눕니다. 따라서 통신 실패나 프로세스 종료로 두 시스템의 결과가 잠시 어긋날 수 있습니다. `UNKNOWN`은 실패가 아니라 승인 여부를 확인하지 못한 상태이며, 이때 새 결제를 만들지 않고 PG 조회 결과로 판단합니다. PG 조회의 404 유예 시간은 `payments.recovery.not-found-grace-ms`로 설정합니다. 유예 시간이 모든 외부 요청의 완료를 보장하는 것은 아니므로, 이 복구 흐름에는 최초 승인 요청과 조회가 경합할 가능성이 남아 있습니다.

### 결제 상태와 후속 업무 상태

| 구분 | 주요 전이 | 의미 |
| --- | --- | --- |
| `paymentStatus` | `BEFORE → READY → PROCESSING → SUCCESS` | 결제 준비, PG 처리, 승인 완료를 나타냅니다. 승인 실패는 `FAILED`, 결과 확인 불가는 `UNKNOWN`으로 기록합니다. 가상계좌 등 완료 전 대기는 `PROCESSING`을 유지합니다. 취소 상태인 `PARTIAL_CANCELLED`, `CANCELLED`도 정의되어 있지만 취소 업무 흐름은 아직 연결되지 않았습니다. |
| `orderStatus` | `BEFORE → PENDING → COMPLETE` | 결제 성공 이후 사용처 업무의 반영 상태입니다. 이벤트를 수신하면 `PENDING`, 실제 후속 업무가 성공한 뒤에만 `COMPLETE`가 됩니다. |

두 상태의 마지막 변경 시각을 각각 저장합니다. `payment_history`는 결제 생성과 상태 변경 이력을, `payment_api_log`는 실제 PG 승인·조회 호출 결과를 기록합니다. 완료 이벤트는 재전달될 수 있으므로 사용처의 주문·상품 반영은 결제 ID를 기준으로 중복 실행에 안전하게 연결해야 합니다. 현재 수신부는 `PENDING` 전환까지만 구현되어 있어, 사용처 연동 전에는 재전달만으로 `COMPLETE`가 되지 않습니다.

### PG 오류와 결과 저장

`PaymentProcessor`는 PG별 응답을 공통 `PaymentProcessResult`로 변환합니다. API 호출이 정상적으로 끝나 결제 상태를 받은 경우에는 결과를 반환합니다. PG API 오류는 상태, PG HTTP 상태, 오류 코드·메시지를 담은 `PaymentException`을 던집니다. 그 밖의 예상하지 못한 호출 예외는 서비스에서 따로 잡아 최초 승인 결과를 `UNKNOWN`으로 기록하고, 결과 조회 중이었다면 기존 결제 상태를 유지합니다. 정상 API 응답 뒤 DB 저장 중 발생한 예외는 PG 호출 오류로 다시 기록하지 않습니다.

`PaymentService`는 PG 호출과 결제 상태 분기를 같은 `try`에서 수행하고, `PaymentException`과 그 외 예외를 각각 처리합니다. DB 저장은 별도 트랜잭션의 `PaymentTransactionService.writeResult()`로 통일했습니다. 이 메서드는 결과 상태에 따라 `payment`와 `payment_history`를 갱신하고 PG 호출 결과를 `payment_api_log`에 기록합니다. 성공 결과에는 예외를 `null`로 전달하며, 예외 유무가 아닌 `PaymentProcessResult.status`로 결제 상태를 판단합니다. 조회 결과가 불확실하거나 결제 부재 404의 유예 시간 안이면 결제 상태를 바꾸지 않고 API 로그만 남깁니다.

PG API 오류는 `BusinessException`을 상속한 `PaymentException`으로 전달하고 `ResponseCode.PAYMENT_CONFLICT`의 HTTP 409 응답을 사용합니다. 오류 응답의 `code`에는 PG 오류 코드를, `message`에는 안내 문구를 담고, DB 기록에 사용하는 결과 객체 전체는 응답에 노출하지 않습니다. PG 오류 코드가 없으면 모듈의 기본 409 코드를 사용합니다.

### 토스 API 클라이언트

`TossPaymentApi`에는 결제 승인, `paymentKey` 조회, `orderId` 조회, 결제 취소, 가상계좌 발급 메서드가 있습니다. 현재 `ready → payment` 서비스 흐름에는 결제 승인과 결과 미확인 시 `paymentKey` 조회가 연결되어 있습니다. `orderId` 조회·취소·가상계좌 발급은 클라이언트 메서드가 있어도 해당 업무 API 흐름까지 구현된 것은 아닙니다.

## API

| 메서드 | 경로 | 역할 |
| --- | --- | --- |
| POST | `/apis/v3/auth/register` | 회원 가입 |
| POST | `/apis/v3/auth/login` | 로그인 및 JWT 쿠키 발급 |
| POST | `/apis/v3/auth/refresh` | JWT 쿠키 갱신 |
| POST | `/apis/v3/auth/logout` | JWT 쿠키 삭제 |
| POST | `/apis/v3/payments/ready` | 결제 준비 및 기존 `READY` 시도 재사용 |
| POST | `/apis/v3/payments` | SDK 인증 결과 검증 및 PG 승인 |

`ready` 요청은 `provider`, `orderId`를 사용합니다. 승인 요청은 `provider`, `orderId`, `paymentKey`, `amount`를 사용합니다. 결제 응답은 `paymentId`, `provider`, `orderId`, `amount`, `paymentStatus`, `orderStatus`를 포함하며 공통 성공 응답으로 감쌉니다.

## 코드 구조

```text
src/main/java/com/paymentModule/
├── api/toss/                         토스 RestClient, 요청·응답 DTO, 토스 enum
├── config/                           Security, Swagger, PG 처리기 Bean 설정
├── data/                             엔티티, 저장소, 공통 응답·enum
├── domain/user/                      회원 인증 API와 서비스
├── domain/payment/                   결제 API, 서비스, PG 처리기
├── event/payments/                   결제 완료 이벤트와 비동기 수신부
└── global/                           설정 프로퍼티, 예외 처리, 인증·쿠키 유틸리티, 재전달 스케줄러
```

`PaymentService`는 결제 흐름을 조율하고, `PaymentTransactionService`는 결제 생성과 `writeResult()`의 상태·이력·로그 저장을 짧은 트랜잭션으로 처리합니다. `PaymentProcessor` 구현은 `PaymentProvider`를 키로 한 Bean 맵에서 선택하며, 현재 등록된 구현은 `TossPaymentProcessor`입니다.

주요 테이블은 현재 결제 상태를 보관하는 `payment`, 상태 변경을 기록하는 `payment_history`, 실제 PG 호출 결과를 기록하는 `payment_api_log`입니다. 실패한 결제 행은 보존하고, 진행 가능한 결제 시도에는 `(provider, activeOrderId)` 고유 제약을 적용합니다.

## 로컬 실행

1. Java 25와 Docker 실행 환경을 준비합니다.
2. 토스 API를 실제 호출할 경우 `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY`, `TOSS_SECURITY_KEY` 환경 변수를 설정합니다. 설정 위치는 `src/main/resources/application.yaml`입니다.
3. 프로젝트 루트에서 `./gradlew bootRun`을 실행합니다. Windows PowerShell에서는 `.\gradlew.bat bootRun`을 사용합니다.

애플리케이션 포트는 `8080`입니다. `compose.yml`에 MySQL과 Redis가 정의되어 있고 Spring Boot Docker Compose 연동이 활성화되어 있습니다. MySQL은 로컬 `3306` 포트를 사용합니다. 최초 테이블 생성 SQL은 `src/main/resources/sql/schema.sql`에 있습니다.

현재는 DB를 새로 생성하는 단계이므로 결제 테이블 구조는 `src/main/resources/sql/schema.sql`을 기준으로 관리합니다.

## 테스트

```powershell
.\gradlew.bat test
```

- `PaymentFlowIntegrationTest`: H2와 Spring Boot 컨텍스트로 준비·승인·실패·대기·재시도 흐름을 검증하고 토스 외부 호출만 모킹합니다.
- `PaymentCompletedEventListenerTest`: 완료 이벤트를 받으면 `PENDING`까지만 기록하는지 확인합니다.
- `TossPaymentProcessorTest`: 토스 응답의 결제 상태·금액·식별자 변환을 검증합니다.
- `TossPaymentApiTest`: 토스 API 클라이언트 호출을 다룹니다. 실제 API를 호출하는 테스트는 입력값과 키 설정을 먼저 확인해야 합니다.

## 현재 남은 작업

- 금액 계산 클래스는 아직 `orderId`로 주문을 조회하지 않고 임시 금액 `10,000`원을 반환합니다. 실제 주문 테이블 또는 주문 서비스에서 확정 금액을 계산하도록 연결해야 합니다.
- `ready`와 `payment` 모두 이 임시 금액을 사용합니다. 실제 주문 연동 전에는 금액 검증이 완성된 상태가 아닙니다.
- PG 승인 요청 전에 프로세스가 종료된 경우에는 결제 조회가 처음에는 404일 수 있습니다. 유예 시간이 지난 뒤 `FAILED`로 확정하고 새 결제 준비부터 시작하도록 하며, 기존 승인 요청의 완료 여부를 완전히 보장하는 분산 선점 처리는 아직 없습니다.
- 완료 이벤트 수신부와 미완료 건 재전달 스케줄러는 있지만 실제 주문 반영과 반영 성공 후 `COMPLETE` 전환은 아직 연결되지 않았습니다. 외부 서비스의 반영 결과를 확인하는 연동도 아직 없습니다.
- 가상계좌 입금 완료를 반영하는 웹훅 수신 흐름은 구현되지 않았습니다.
