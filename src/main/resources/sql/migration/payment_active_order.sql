-- 기존 payment 테이블에 결제 시도별 주문 조회 키를 추가한다.
-- 적용 전 결제 요청을 중지하고, 이 스크립트는 기존 스키마에 한 번만 실행한다.
ALTER TABLE payment
    ADD COLUMN activeOrderId VARCHAR(64) NULL
        COMMENT '진행 가능한 결제 시도의 주문 식별자. 실패 시 NULL' AFTER orderId;

-- 실패 이력은 남겨 두되, 진행 가능한 기존 결제만 주문당 한 건으로 제한한다.
UPDATE payment
SET activeOrderId = orderId
WHERE status <> 'FAILED';

ALTER TABLE payment
    DROP INDEX uk_payment_provider_orderId,
    ADD UNIQUE KEY uk_payment_provider_activeOrderId (provider, activeOrderId),
    ADD KEY idx_payment_provider_orderId (provider, orderId);
