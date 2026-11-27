package com.datagent.exception;

/** 会话操作失败的业务原因，供调用方决定如何反馈。 */
public final class SessionException extends RuntimeException {
    public enum Reason { ACTIVE_RUN, INVALID_STEER, NOT_FOUND }

    /** 触发本次拒绝的会话状态或输入条件。 */
    private final Reason reason;

    public SessionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
