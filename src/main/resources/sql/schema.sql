CREATE TABLE IF NOT EXISTS user (
    id BIGINT NOT NULL COMMENT '사용자 PK',
    userId VARCHAR(20) NOT NULL COMMENT '사용자 로그인 ID',
    password VARCHAR(255) NOT NULL COMMENT '암호화된 비밀번호',
    email VARCHAR(50) NOT NULL COMMENT '이메일',
    userName VARCHAR(20) NOT NULL COMMENT '사용자 이름',
    role VARCHAR(10) NOT NULL COMMENT '사용자 권한: USER, ADMIN',
    createdBy VARCHAR(255) NOT NULL COMMENT '등록한 사용자 로그인 ID',
    updatedBy VARCHAR(255) NOT NULL COMMENT '수정한 사용자 로그인 ID',
    createdAt DATETIME(6) NOT NULL COMMENT '사용자 등록 일시',
    updatedAt DATETIME(6) NOT NULL COMMENT '사용자 수정 일시',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_userId (userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='사용자 정보';

CREATE TABLE IF NOT EXISTS payment (
                                       id BIGINT NOT NULL COMMENT '결제 PK',
                                       userId BIGINT NOT NULL COMMENT '결제 대상 사용자 PK',
                                       loginId VARCHAR(255) NOT NULL COMMENT '결제 당시 사용자의 로그인 식별자',
    orderId VARCHAR(64) NOT NULL COMMENT '결제 요청과 승인에 사용하는 주문 식별자',
    activeOrderId VARCHAR(64) NULL COMMENT '승인 가능한 현재 시도의 주문 식별자. 실패 확정 시 NULL, 재준비 시 복원',
    externalPaymentId VARCHAR(255) NULL COMMENT 'PG사에서 발급한 결제 식별자. 토스의 paymentKey',
    receiptUrl VARCHAR(1000) NULL COMMENT '결제 영수증 주소',
    cardIssuerCode VARCHAR(30) NULL COMMENT '카드 발급사 코드',
    cardIssuerName VARCHAR(100) NULL COMMENT '카드 발급사 이름',
    cardMaskedNumber VARCHAR(30) NULL COMMENT 'PG가 마스킹한 카드번호',
    cardInstallmentMonths INT NULL COMMENT '카드 할부 개월 수',
    cardApprovalNumber VARCHAR(50) NULL COMMENT '카드 승인번호',
    transferBankCode VARCHAR(30) NULL COMMENT '계좌이체 은행 코드',
    transferBankName VARCHAR(100) NULL COMMENT '계좌이체 은행 이름',
    virtualAccountBankCode VARCHAR(30) NULL COMMENT '가상계좌 은행 코드',
    virtualAccountBankName VARCHAR(100) NULL COMMENT '가상계좌 은행 이름',
    virtualAccountNumber VARCHAR(100) NULL COMMENT '입금용 가상계좌 번호',
    virtualAccountDepositorName VARCHAR(100) NULL COMMENT '가상계좌 입금자 안내명',
    virtualAccountDueDate VARCHAR(50) NULL COMMENT '가상계좌 입금 기한',
    easyPayProvider VARCHAR(100) NULL COMMENT '간편결제 제공사',
    cashReceiptIssueNumber VARCHAR(100) NULL COMMENT '현금영수증 발급번호',
    cashReceiptUrl VARCHAR(1000) NULL COMMENT '현금영수증 주소',
    provider VARCHAR(30) NOT NULL COMMENT '결제대행사 구분값: TOSS, KCP, PORTONE 등',
    paymentType VARCHAR(30) NOT NULL COMMENT '결제 유형: ONE_TIME, SUBSCRIPTION',
    paymentMethod VARCHAR(30) NULL COMMENT '결제 수단: CARD, TRANSFER, VIRTUAL_ACCOUNT 등',
    paymentStatus VARCHAR(30) NOT NULL DEFAULT 'BEFORE' COMMENT '결제 상태: BEFORE, READY, PROCESSING, WAITING_FOR_PAYMENT, SUCCESS, FAILED, UNKNOWN, PARTIAL_CANCELLED, CANCELLED',
    paymentStatusChangedAt DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '결제 상태가 마지막으로 변경된 일시',
    orderStatus VARCHAR(30) NOT NULL DEFAULT 'BEFORE' COMMENT '주문 반영 상태: BEFORE, PENDING, COMPLETE',
    orderStatusChangedAt DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '주문 반영 상태가 마지막으로 변경된 일시',
    amount BIGINT NOT NULL COMMENT '결제 준비 단계에 저장한 금액, 원 단위',
    cancelledAmount BIGINT NOT NULL DEFAULT 0 COMMENT '누적 취소 금액, 원 단위',
    idempotencyKey VARCHAR(255) NULL COMMENT '동일한 결제 요청의 중복 처리를 방지하기 위한 요청 식별자',
    failureCode VARCHAR(100) NULL COMMENT '최근 결제 실패 또는 오류 코드',
    failureMessage VARCHAR(500) NULL COMMENT '최근 결제 실패 또는 오류 메시지',
    requestedAt DATETIME(6) NULL COMMENT 'PG사 결제 요청 일시',
    approvedAt DATETIME(6) NULL COMMENT '결제 승인 완료 일시',
    cancelledAt DATETIME(6) NULL COMMENT '결제 전액 취소 완료 일시',
    createdBy VARCHAR(255) NOT NULL COMMENT '등록한 사용자 로그인 ID',
    updatedBy VARCHAR(255) NOT NULL COMMENT '수정한 사용자 로그인 ID',
    createdAt DATETIME(6) NOT NULL COMMENT '결제 데이터 생성 일시',
    updatedAt DATETIME(6) NOT NULL COMMENT '결제 데이터 최종 수정 일시',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_idempotencyKey (idempotencyKey),
    UNIQUE KEY uk_payment_provider_externalPaymentId (provider, externalPaymentId),
    UNIQUE KEY uk_payment_orderId (orderId),
    KEY idx_payment_provider_orderId (provider, orderId),
    KEY idx_payment_userId (userId),
    KEY idx_payment_loginId (loginId),
    KEY idx_payment_paymentStatus (paymentStatus),
    CONSTRAINT ck_payment_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_cancelledAmount CHECK (cancelledAmount >= 0 AND cancelledAmount <= amount)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='결제 현재 상태 및 결제 결과 관리';

-- 기존 DB에 남아 있는 미사용 가맹점 식별 컬럼도 제거한다.
SET @payment_merchant_id_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'merchantId') > 0,
    'ALTER TABLE payment DROP COLUMN merchantId',
    'SELECT 1'
);
PREPARE payment_merchant_id_stmt FROM @payment_merchant_id_sql;
EXECUTE payment_merchant_id_stmt;
DEALLOCATE PREPARE payment_merchant_id_stmt;

-- 기존 payment 테이블에는 CREATE TABLE IF NOT EXISTS가 새 컬럼을 추가하지 않으므로 누락된 PG 결제 키 컬럼을 보완한다.
SET @payment_external_id_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'externalPaymentId') = 0,
    'ALTER TABLE payment ADD COLUMN externalPaymentId VARCHAR(255) NULL',
    'SELECT 1'
);
PREPARE payment_external_id_stmt FROM @payment_external_id_sql;
EXECUTE payment_external_id_stmt;
DEALLOCATE PREPARE payment_external_id_stmt;

-- 기존 payment 테이블에서도 누락된 상세 컬럼만 추가해 부분 반영된 DB를 안전하게 보완한다.
SET @payment_details_sql = CONCAT(
    'ALTER TABLE payment ',
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'receiptUrl') = 0,
        'ADD COLUMN receiptUrl VARCHAR(1000) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'cardIssuerCode') = 0,
        'ADD COLUMN cardIssuerCode VARCHAR(30) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'cardIssuerName') = 0,
        'ADD COLUMN cardIssuerName VARCHAR(100) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'cardMaskedNumber') = 0,
        'ADD COLUMN cardMaskedNumber VARCHAR(30) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'cardInstallmentMonths') = 0,
        'ADD COLUMN cardInstallmentMonths INT NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'cardApprovalNumber') = 0,
        'ADD COLUMN cardApprovalNumber VARCHAR(50) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'transferBankCode') = 0,
        'ADD COLUMN transferBankCode VARCHAR(30) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'transferBankName') = 0,
        'ADD COLUMN transferBankName VARCHAR(100) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'virtualAccountBankCode') = 0,
        'ADD COLUMN virtualAccountBankCode VARCHAR(30) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'virtualAccountBankName') = 0,
        'ADD COLUMN virtualAccountBankName VARCHAR(100) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'virtualAccountNumber') = 0,
        'ADD COLUMN virtualAccountNumber VARCHAR(100) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'virtualAccountDepositorName') = 0,
        'ADD COLUMN virtualAccountDepositorName VARCHAR(100) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'virtualAccountDueDate') = 0,
        'ADD COLUMN virtualAccountDueDate VARCHAR(50) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'easyPayProvider') = 0,
        'ADD COLUMN easyPayProvider VARCHAR(100) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'cashReceiptIssueNumber') = 0,
        'ADD COLUMN cashReceiptIssueNumber VARCHAR(100) NULL, ', ''),
    IF((SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'cashReceiptUrl') = 0,
        'ADD COLUMN cashReceiptUrl VARCHAR(1000) NULL, ', '')
);
SET @payment_details_sql = IF(
    @payment_details_sql = 'ALTER TABLE payment ',
    'SELECT 1',
    LEFT(@payment_details_sql, LENGTH(@payment_details_sql) - 2)
);
PREPARE payment_details_stmt FROM @payment_details_sql;
EXECUTE payment_details_stmt;
DEALLOCATE PREPARE payment_details_stmt;

CREATE TABLE IF NOT EXISTS payment_history (
                                              id BIGINT NOT NULL COMMENT '결제 이력 PK',
                                              paymentId BIGINT NOT NULL COMMENT '결제 행 PK 참조값이며 DB 외래키 제약은 사용하지 않음',
                                              userId BIGINT NOT NULL COMMENT '결제 대상 사용자 PK',
                                              loginId VARCHAR(255) NOT NULL COMMENT '이력 생성 당시 사용자의 로그인 식별자',
    historyType VARCHAR(30) NOT NULL COMMENT '이력 유형: CREATED, READY_RETRIED, PAYMENT_REQUESTED, PAYMENT_PENDING, PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_UNKNOWN, PAYMENT_CANCELED, CANCEL_REQUESTED, CANCEL_SUCCEEDED, CANCEL_FAILED',
    previousStatus VARCHAR(30) NULL COMMENT '처리 전 결제 상태',
    currentStatus VARCHAR(30) NOT NULL COMMENT '처리 후 결제 상태',
    transactionAmount BIGINT NULL COMMENT '해당 이력에서 처리한 승인 또는 취소 금액, 원 단위',
    cumulativeCancelledAmount BIGINT NULL COMMENT '해당 이력 발생 시점의 누적 취소 금액, 원 단위',
    externalPaymentId VARCHAR(255) NULL COMMENT '이력 발생 시점의 PG사 결제 식별자',
    transactionId VARCHAR(255) NULL COMMENT 'PG사에서 발급한 승인 또는 취소 거래 식별자',
    resultCode VARCHAR(100) NULL COMMENT 'PG사 처리 결과 코드',
    resultMessage VARCHAR(500) NULL COMMENT 'PG사 처리 결과 메시지',
    createdBy VARCHAR(255) NOT NULL COMMENT '등록한 사용자 로그인 ID',
    createdAt DATETIME(6) NOT NULL COMMENT '결제 이력 생성 일시',
    PRIMARY KEY (id),
    KEY idx_paymentHistory_paymentId (paymentId),
    KEY idx_paymentHistory_userId (userId),
    KEY idx_paymentHistory_loginId (loginId),
    KEY idx_paymentHistory_transactionId (transactionId),
    CONSTRAINT ck_paymentHistory_transactionAmount CHECK (transactionAmount IS NULL OR transactionAmount >= 0),
    CONSTRAINT ck_paymentHistory_cumulativeCancelledAmount CHECK (cumulativeCancelledAmount IS NULL OR cumulativeCancelledAmount >= 0)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='결제 승인, 실패, 취소 및 상태 변경 이력';

-- 이전 이력 테이블의 providerTransactionId와 현재의 PG 결제 키·거래 키는 별개 컬럼으로 둔다.
SET @history_external_id_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'payment_history' AND column_name = 'externalPaymentId') = 0,
    'ALTER TABLE payment_history ADD COLUMN externalPaymentId VARCHAR(255) NULL',
    'SELECT 1'
);
PREPARE history_external_id_stmt FROM @history_external_id_sql;
EXECUTE history_external_id_stmt;
DEALLOCATE PREPARE history_external_id_stmt;

SET @history_transaction_id_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'payment_history' AND column_name = 'transactionId') = 0,
    'ALTER TABLE payment_history ADD COLUMN transactionId VARCHAR(255) NULL',
    'SELECT 1'
);
PREPARE history_transaction_id_stmt FROM @history_transaction_id_sql;
EXECUTE history_transaction_id_stmt;
DEALLOCATE PREPARE history_transaction_id_stmt;

CREATE TABLE IF NOT EXISTS payment_api_log (
                                             id BIGINT NOT NULL COMMENT 'PG사 API 통신 로그 PK',
                                             paymentId BIGINT NOT NULL COMMENT '결제 행 PK 참조값이며 DB 외래키 제약은 사용하지 않음',
                                             userId BIGINT NOT NULL COMMENT '결제 대상 사용자 PK',
                                             loginId VARCHAR(255) NOT NULL COMMENT 'API 호출 당시 사용자의 로그인 식별자',
    externalPaymentId VARCHAR(255) NULL COMMENT 'PG사에서 발급한 결제 식별자',
    transactionId VARCHAR(255) NULL COMMENT 'PG사에서 발급한 승인 또는 취소 거래 식별자',
    provider VARCHAR(30) NOT NULL COMMENT '호출한 결제대행사 구분값: TOSS, KCP, PORTONE 등',
    apiType VARCHAR(50) NOT NULL COMMENT 'PG사 API 호출 유형: PAYMENT, CANCEL, BILLING_KEY, SUBSCRIPTION_PAYMENT, PAYMENT_LOOKUP 등',
    httpMethod VARCHAR(10) NOT NULL COMMENT 'PG사 API 요청 HTTP 메서드: GET, POST, PUT, PATCH, DELETE 등. 확인할 수 없으면 UNKNOWN',
    requestUrl VARCHAR(1000) NOT NULL COMMENT '호출한 PG사 API 주소',
    requestHeader LONGTEXT NULL COMMENT 'PG사 API 요청 헤더이며 인증정보와 민감정보는 저장 전 마스킹',
    requestBody LONGTEXT NULL COMMENT '민감정보를 제외하고 선별한 PG사 API 요청 JSON',
    responseStatus INT NULL COMMENT 'PG사 API HTTP 응답 상태 코드',
    responseHeader LONGTEXT NULL COMMENT 'PG사 API 응답 헤더이며 민감정보는 저장 전 마스킹',
    responseBody LONGTEXT NULL COMMENT '민감정보를 제외하고 선별한 PG사 API 응답 JSON',
    successYn BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'PG사 API 호출 성공 여부',
    durationMs BIGINT NULL COMMENT 'PG사 API 요청부터 응답까지 걸린 시간, 밀리초 단위',
    errorType VARCHAR(100) NULL COMMENT 'API 호출 중 발생한 내부 예외 또는 오류 유형',
    errorMessage VARCHAR(1000) NULL COMMENT 'API 호출 중 발생한 내부 예외 또는 오류 메시지',
    requestedAt DATETIME(6) NOT NULL COMMENT 'PG사 API 요청 시작 일시',
    respondedAt DATETIME(6) NULL COMMENT 'PG사 API 응답 수신 일시',
    createdBy VARCHAR(255) NOT NULL COMMENT '등록한 사용자 로그인 ID',
    createdAt DATETIME(6) NOT NULL COMMENT 'API 로그 생성 일시',
    PRIMARY KEY (id),
    KEY idx_paymentApiLog_paymentId (paymentId),
    KEY idx_paymentApiLog_userId (userId),
    KEY idx_paymentApiLog_loginId (loginId),
    KEY idx_paymentApiLog_provider_externalPaymentId (provider, externalPaymentId),
    KEY idx_paymentApiLog_provider_apiType (provider, apiType),
    KEY idx_paymentApiLog_successYn (successYn),
    CONSTRAINT ck_paymentApiLog_durationMs CHECK (durationMs IS NULL OR durationMs >= 0)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='외부 PG사 API 요청, 응답 및 오류 통신 로그';

-- 기존 통신 로그 테이블에도 PG 결제 키 컬럼을 추가한다. 재실행 시 이미 있으면 건너뛴다.
SET @api_log_external_id_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'payment_api_log' AND column_name = 'externalPaymentId') = 0,
    'ALTER TABLE payment_api_log ADD COLUMN externalPaymentId VARCHAR(255) NULL',
    'SELECT 1'
);
PREPARE api_log_external_id_stmt FROM @api_log_external_id_sql;
EXECUTE api_log_external_id_stmt;
DEALLOCATE PREPARE api_log_external_id_stmt;
