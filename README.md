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
5. DB 트랜잭션 밖에서 PG 승인 API를 호출한 뒤, 별도 트랜잭션에서 결과와 `payment_history`, `payment_api_log`를 저장합니다. 승인 결과를 확정할 수 없는 통신 오류는 `UNKNOWN`으로 기록합니다.
6. 같은 결제 키로 `PROCESSING` 요청이 다시 들어오면 PG 결제를 조회합니다. 조회 결과가 성공이면 주문번호·금액·결제 키를 대조해 성공으로 확정하고, 아직 확인할 수 없으면 기존 상태를 유지합니다.
7. 성공 결과가 커밋되면 `PaymentCompletedEvent`를 발행합니다. 비동기 수신부는 `orderStatus`를 `BEFORE → PENDING`으로 변경합니다. 실제 주문 반영이 성공한 뒤 `COMPLETE`로 변경하는 연결은 TODO로 남아 있습니다.

결제 상태는 `paymentStatus`, 주문 반영 상태는 `orderStatus`로 따로 저장하고 각각의 마지막 변경 시각을 `paymentStatusChangedAt`, `orderStatusChangedAt`에 기록합니다. 스케줄러는 `BEFORE` 건에는 결제 완료 시각인 `approvedAt`을, `PENDING` 건에는 주문 반영 상태 변경 시각을 사용해 유예 시간이 지난 완료 이벤트를 다시 전달합니다. 실제 상품·주문 반영 코드는 아직 연결되지 않았으므로 이벤트 수신 후에도 `orderStatus`는 `PENDING`에 남습니다.

토스 API 호출에는 시크릿 키 기반 Basic 인증, JSON 본문, 멱등키를 사용합니다. 요청·응답 로그와 DB의 API 로그에는 인증 헤더와 결제 본문 원문을 저장하지 않습니다.

### 토스 API 클라이언트

`TossPaymentApi`에는 결제 승인, `paymentKey` 조회, `orderId` 조회, 결제 취소, 가상계좌 발급 메서드가 있습니다. **현재 `ready → payment` 서비스 흐름에 연결된 동작은 결제 승인입니다.** 조회·취소·가상계좌 발급은 클라이언트 메서드가 있어도 해당 업무 API 흐름까지 구현된 것은 아닙니다.

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
src/main/java/com/
├── api/toss/                         토스 RestClient, 요청·응답 DTO, 토스 enum
└── paymentModule/
    ├── config/                        Security, Swagger, PG 처리기 Bean 설정
    ├── data/                          엔티티, 저장소, 공통 응답·enum
    ├── domain/user/                   회원 인증 API와 서비스
    ├── domain/payment/                결제 API, 서비스, PG 처리기, 완료 이벤트
    └── global/                        설정 프로퍼티, 인증·쿠키 유틸리티
```

`PaymentService`는 결제 흐름을 조율하고, `PaymentTransactionService`는 결제 생성과 상태·이력·로그 저장을 짧은 트랜잭션으로 처리합니다. `PaymentProcessor` 구현은 `PaymentProvider`를 키로 한 Bean 맵에서 선택하며, 현재 등록된 구현은 `TossPaymentProcessor`입니다.

주요 테이블은 현재 결제 상태를 보관하는 `payment`, 상태 변경을 기록하는 `payment_history`, 실제 PG 호출 결과를 기록하는 `payment_api_log`입니다. 실패한 결제 행은 보존하고, 진행 가능한 결제 시도에는 `(provider, activeOrderId)` 고유 제약을 적용합니다.

## 로컬 실행

1. Java 25와 Docker 실행 환경을 준비합니다.
2. 토스 API를 실제 호출할 경우 `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY`, `TOSS_SECURITY_KEY` 환경 변수를 설정합니다. 설정 위치는 `src/main/resources/application.yaml`입니다.
3. 프로젝트 루트에서 `./gradlew bootRun`을 실행합니다. Windows PowerShell에서는 `.\gradlew.bat bootRun`을 사용합니다.

애플리케이션 포트는 `8080`입니다. `compose.yml`에 MySQL과 Redis가 정의되어 있고 Spring Boot Docker Compose 연동이 활성화되어 있습니다. MySQL은 로컬 `3306` 포트를 사용합니다. 최초 테이블 생성 SQL은 `src/main/resources/sql/schema.sql`에 있습니다.

기존 `payment` 테이블에 `activeOrderId`가 없다면 `src/main/resources/sql/migration/payment_active_order.sql`을 **별도로 적용**해야 합니다. 이번 `paymentStatus` 컬럼명 변경, `orderStatus` 추가, 두 상태 변경 시각 컬럼은 `schema.sql`에만 반영되어 있으므로 기존 DB에는 별도 스키마 갱신이 필요합니다. `CREATE TABLE IF NOT EXISTS`는 기존 테이블을 변경하지 않습니다.

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
- `PROCESSING` 재진입 시 PG 조회는 연결되어 있지만 조회 결과가 없으면 기존 상태를 유지합니다. `UNKNOWN` 상태의 별도 재조회와 PG 승인 요청 전 프로세스가 종료된 경우의 재승인 복구는 아직 구현되지 않았습니다.
- 완료 이벤트 수신부와 미완료 건 재전달 스케줄러는 있지만 실제 주문 반영과 반영 성공 후 `COMPLETE` 전환은 아직 연결되지 않았습니다. 외부 서비스의 반영 결과를 확인하는 연동도 아직 없습니다.
- 가상계좌 입금 완료를 반영하는 웹훅 수신 흐름은 구현되지 않았습니다.
