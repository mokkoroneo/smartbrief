package jp.smartbrief.billing.payment.domain;

public enum WebhookProcessResult {
    PROCESSED,
    DUPLICATE,
    USER_NOT_FOUND,
    IGNORED
}