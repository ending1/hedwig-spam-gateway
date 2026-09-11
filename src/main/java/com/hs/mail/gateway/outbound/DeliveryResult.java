package com.hs.mail.gateway.outbound;

/** 한 건의 아웃바운드 발송 시도 결과. */
public class DeliveryResult {

    public enum Status {
        SUCCESS, PERMANENT_FAILURE, TRANSIENT_FAILURE
    }

    private final Status status;
    private final String message;

    private DeliveryResult(Status status, String message) {
        this.status = status;
        this.message = message;
    }

    public static DeliveryResult success() {
        return new DeliveryResult(Status.SUCCESS, null);
    }

    public static DeliveryResult permanentFailure(String message) {
        return new DeliveryResult(Status.PERMANENT_FAILURE, message);
    }

    public static DeliveryResult transientFailure(String message) {
        return new DeliveryResult(Status.TRANSIENT_FAILURE, message);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
