# paymentModule

회원 인증과 결제 준비·승인 흐름을 구현하는 Spring Boot 프로젝트입니다. 결제 모듈은 주문·상품 도메인의 후속 처리와 분리하고, PG별 승인 로직은 `PaymentProcessor`로 구분합니다. 현재 결제 흐름에 연결된 PG는 토스페이먼츠입니다.

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
| API 테스트 화면 | React 19, Vite 8, React Router 8, Axios, TossPayments SDK 2 |
| 테스트 | JUnit 5, Spring Boot Test, H2, Mockito |

Redis 의존성과 컨테이너 설정은 포함되어 있지만, 현재 결제 승인 흐름의 상태·이력·API 로그는 MySQL에 저장합니다.

## 주요 기능

### 회원 인증

- 회원 가입, 로그인, 토큰 갱신, 로그아웃 API를 제공합니다.
- 로그인 시 액세스·리프레시 JWT를 쿠키에 설정합니다. 결제 API에는 `ROLE_USER` 메서드 보안이 적용되어 있습니다.

### 결제 준비와 승인

1. `POST /apis/v3/payments/ready`가 PG와 `orderId`를 받아 서버에서 금액을 계산하고 결제 시도를 `READY`로 저장합니다.
2. 같은 주문에 `READY` 시도가 있으면 금액을 갱신합니다. `FAILED`가 확정되면 같은 `Payment` 행의 이전 PG 키·멱등키를 정리하고 새 `READY` 시도로 재준비합니다. 이전 상태와 PG 호출은 이력·API 로그에 남습니다. `PROCESSING`, `UNKNOWN`, `SUCCESS` 등은 다시 준비하지 않습니다.
3. 클라이언트가 반환된 주문 정보로 PG SDK를 실행하고, 인증 결과의 `paymentKey`, `orderId`, `amount`를 결제 승인 API에 전달합니다.
4. `POST /apis/v3/payments`는 서버에서 다시 계산한 금액, 저장 금액, 전달 금액을 비교합니다. 결제 행을 잠근 짧은 트랜잭션에서 `READY → PROCESSING`으로 변경해 같은 주문의 중복 승인 요청을 막습니다.
5. DB 트랜잭션 밖에서 PG 승인 API를 호출합니다. 정상 API 응답의 결제 상태는 서비스에서 처리하고, PG API 오류는 `PaymentException`으로 전달합니다. 승인 결과를 확정할 수 없는 호출 오류는 `UNKNOWN`으로 기록합니다.
6. 같은 결제 키로 `PROCESSING`, `UNKNOWN` 또는 `WAITING_FOR_PAYMENT` 요청이 다시 들어오면 PG 결제를 조회합니다. 조회 성공은 주문번호·금액·결제 키를 대조해 확정합니다. 토스가 결제 정보 부재를 명시한 404는 5초 간격으로 두 번 더 조회하고, 세 번 모두 결제 부재일 때 `FAILED`로 확정해 새 `ready` 시도를 허용합니다. 통신 오류 등 불확실한 조회 결과는 기존 상태를 유지합니다.
7. 성공 결과가 커밋되면 `PaymentCompletedEvent`를 발행합니다. 비동기 수신부는 `orderStatus`를 `BEFORE → PENDING`으로 변경합니다. 실제 주문 반영이 성공한 뒤 `COMPLETE`로 변경하는 연결은 TODO로 남아 있습니다.

결제 상태는 `paymentStatus`, 주문 반영 상태는 `orderStatus`로 따로 저장하고 각각의 마지막 변경 시각을 `paymentStatusChangedAt`, `orderStatusChangedAt`에 기록합니다. 스케줄러는 `BEFORE` 건에는 결제 완료 시각인 `approvedAt`을, `PENDING` 건에는 주문 반영 상태 변경 시각을 사용해 유예 시간이 지난 모든 완료 이벤트를 다시 전달합니다. 실제 상품·주문 반영 코드는 아직 연결되지 않았으므로 이벤트 수신 후에도 `orderStatus`는 `PENDING`에 남습니다.

`PaymentScheduler`는 상태 변경 후 설정된 유예 시간이 지난 `PROCESSING`·`UNKNOWN`·`WAITING_FOR_PAYMENT` 결제를 저장된 결제 키로 PG에 조회합니다. 조회 결과가 입금 대기이면 `WAITING_FOR_PAYMENT`로 확정하고 다음 실행에서 다시 확인합니다. 주문 이벤트 재전달과 결제 상태 복구는 `schedule.payments` 아래 `order`, `processing` 설정으로 각각 조정합니다. 현재 두 작업은 시작 1분 후 첫 실행을 시작하고 각 실행이 끝난 5분 뒤 다시 실행합니다. 대상의 기준 시각에서 10분이 지난 건을 조회합니다.

토스 API 호출에는 시크릿 키 기반 Basic 인증, JSON 본문, 멱등키를 사용합니다. 요청·응답 로그와 DB의 API 로그에는 인증 헤더와 결제 본문 원문을 저장하지 않습니다.

## 설계 시 고려한 상황

### 주문 도메인과 결제 모듈의 경계

결제 모듈은 상품 ID나 주문 테이블 구조를 직접 결제 식별자로 사용하지 않고 `orderId`를 받습니다. 주문 서버가 결제 직전 주문과 금액을 확정하고, `ready`에서 그 금액을 서버 기준으로 읽어 저장하는 흐름을 전제로 합니다. 현재 `PaymentAmountCalculator`의 금액은 임시 값이므로, 실제 사용처의 주문 테이블 또는 주문 서비스 연동이 필요합니다. 같은 계산을 승인 직전에도 다시 실행해 준비 금액 및 SDK 전달 금액과 비교합니다. 클라이언트가 금액을 바꿔 보내거나 준비 이후 주문 금액이 달라진 경우 승인을 진행하지 않기 위한 검증입니다.

PG별 요청·응답 처리는 `PaymentProcessor`로 분리하고 `PaymentProvider`를 키로 선택합니다. 반면 결제 이후 주문·상품 반영은 모듈이 업무 도메인을 알 수 없으므로 완료 이벤트를 전달하는 지점까지만 맡습니다. 사용처가 단일 애플리케이션이든 별도 주문 서비스든, 실제 후속 처리와 그 트랜잭션은 사용처에서 연결해야 합니다.

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

`PaymentProcessor`는 PG별 응답을 공통 `PaymentProcessResult`로 변환합니다. API 호출이 정상적으로 끝나 결제 상태를 받은 경우에는 결과를 반환합니다. PG API 오류는 상태, PG HTTP 상태, 오류 코드·메시지를 담은 `PaymentException`을 던집니다. 그 밖의 예상하지 못한 호출 예외는 서비스에서 따로 잡아 최초 승인 결과를 `UNKNOWN`으로 기록하고, 결과 조회 중이었다면 기존 결제 상태를 유지합니다. 정상 API 응답 뒤 DB 저장 중 발생한 예외는 PG 호출 오류로 다시 기록하지 않습니다.

`PaymentService`는 PG 호출과 결제 상태 분기를 같은 `try`에서 수행하고, `PaymentException`과 그 외 예외를 각각 처리합니다. DB 저장은 별도 트랜잭션의 `PaymentTransactionService.writeResult()`로 통일했습니다. 이 메서드는 결과 상태에 따라 `payment`와 `payment_history`를 갱신하고 PG 호출 결과를 `payment_api_log`에 기록합니다. 성공 결과에는 예외를 `null`로 전달하며, 예외 유무가 아닌 `PaymentProcessResult.status`로 결제 상태를 판단합니다. 조회 결과가 불확실하거나 404 재조회 중이면 결제 상태를 바꾸지 않고 API 로그만 남깁니다.

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
| POST | `/apis/v3/payments/ready` | 결제 준비, 기존 `READY` 금액 갱신 및 `FAILED` 행 재준비 |
| POST | `/apis/v3/payments` | SDK 인증 결과 검증 및 PG 승인 |
| GET | `/apis/v3/payments/toss/client-key` | 로그인한 브라우저에 공개용 토스 클라이언트 키 전달 |
| GET | `/apis/v3/payments?page=0` | 로그인한 사용자의 결제 목록, 20건씩 최신순 조회 |
| GET | `/apis/v3/payments/{paymentId}` | 로그인한 사용자의 결제 한 건 조회 |

`ready` 요청은 `provider`, `orderId`를 사용합니다. 승인 요청은 `provider`, `orderId`, `externalPaymentId`, `amount`를 사용합니다. 프런트엔드는 토스 SDK가 반환한 `paymentKey`를 승인 요청의 `externalPaymentId`로 전달합니다. 결제 응답의 `id`는 내부 결제 PK이고 `externalPaymentId`는 PG 결제 키입니다. 응답에는 `provider`, `orderId`, `amount`, `paymentStatus`, `orderStatus`도 포함되며 공통 성공 응답으로 감쌉니다.

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

주요 테이블은 주문별 현재 결제 상태 한 행을 보관하는 `payment`, 상태 변경을 기록하는 `payment_history`, 실제 PG 호출 결과를 기록하는 `payment_api_log`입니다. 새로 만드는 DB에는 `orderId` 고유 제약을 적용합니다. 기존 DB는 `CREATE TABLE IF NOT EXISTS`로 제약이 바뀌지 않으므로 중복 주문 행 확인과 스키마 반영이 별도로 필요합니다.

`payment.id`는 내부 PK, `payment.orderId`는 주문 ID, `payment.externalPaymentId`는 PG 결제 키입니다. 이력·API 로그의 `paymentId`는 내부 결제 PK를 참조하며, PG 결제 키는 `externalPaymentId`, PG 거래 키는 `transactionId`에 저장합니다. 이력·API 로그는 각 행의 `id`만 PK로 사용하고 결제 참조값과 PG 키에는 고유 제약을 두지 않습니다.

## 로컬 실행

1. Java 25와 Docker 실행 환경을 준비합니다.
2. 토스 API를 실제 호출할 경우 `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY`, `TOSS_SECURITY_KEY` 환경 변수를 설정합니다. 설정 위치는 `src/main/resources/application.yaml`입니다.
3. 프로젝트 루트에서 `./gradlew bootRun`을 실행합니다. Windows PowerShell에서는 `.\gradlew.bat bootRun`을 사용합니다.

애플리케이션 포트는 `8080`입니다. `compose.yml`에 MySQL과 Redis가 정의되어 있고 Spring Boot Docker Compose 연동이 활성화되어 있습니다. MySQL은 로컬 `3306` 포트를 사용합니다. 최초 테이블 생성 SQL은 `src/main/resources/sql/schema.sql`에 있습니다. Hibernate 물리적 명명 전략은 스키마의 camelCase 컬럼명을 그대로 사용하도록 설정합니다. 기존 테이블에는 `CREATE TABLE IF NOT EXISTS`가 새 컬럼을 추가하지 않으므로 스키마 변경 시 별도 반영이 필요합니다.

React 테스트 화면은 백엔드를 실행한 다음 `frontend`에서 `npm ci`, `npm run dev`로 시작합니다. 화면은 Vite 개발 서버의 `/apis` 프록시를 통해 백엔드에 요청하므로 로그인 JWT 쿠키를 같은 출처로 전달합니다. 로그인·회원가입, `ready`, 토스 SDK 결제창, 성공 URL의 승인 요청, 실패 화면, 본인 결제 목록·단건 조회를 제공합니다. 토스 클라이언트 키는 백엔드의 `TOSS_CLIENT_KEY` 설정에서 공개용 키만 조회하며 시크릿 키는 브라우저에 전달하지 않습니다. 이 화면은 SDK의 `payment()`를 사용하므로 실제 PG 테스트에는 API 개별 연동 테스트 클라이언트 키(`test_ck`)와 짝이 맞는 시크릿 키(`test_sk`)가 필요합니다.

현재는 DB를 새로 생성하는 단계이므로 결제 테이블 구조는 `src/main/resources/sql/schema.sql`을 기준으로 관리합니다.

## 테스트

```powershell
.\gradlew.bat test
```

- `PaymentFlowIntegrationTest`: H2와 Spring Boot 컨텍스트로 준비·승인·실패·대기·재시도 흐름을 검증하고 토스 외부 호출만 모킹합니다.
- `PaymentSchedulerTest`: 재조회 대상과 100건이 넘는 완료 이벤트 재전달을 검증합니다.
- `PaymentCompletedEventListenerTest`: 완료 이벤트를 받으면 `PENDING`까지만 기록하는지 확인합니다.
- `TossPaymentProcessorTest`: 토스 응답의 결제 상태·금액·식별자 변환을 검증합니다.
- `TossPaymentApiTest`: 토스 API 클라이언트 호출을 다룹니다. 실제 API를 호출하는 테스트는 입력값과 키 설정을 먼저 확인해야 합니다.
- `frontend`: `npm run build`, `npm run lint`로 React 테스트 화면의 빌드·정적 검사를 확인합니다.

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

결제 상태 복구와 완료 이벤트 재전달은 `PaymentScheduler`와 `schedule.payments` 설정 아래에 모았습니다. 처리량에 따른 배치 크기나 실행 분산은 실제 호출량을 본 뒤 결정하기로 했습니다.
