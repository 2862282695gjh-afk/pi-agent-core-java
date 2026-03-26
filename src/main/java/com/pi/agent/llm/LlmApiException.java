package com.pi.agent.llm;

/**
 * Exception thrown when LLM API calls fail.
 */
public class LlmApiException extends RuntimeException {
    private final ErrorCode errorCode;
    private final int statusCode;
    private final boolean retryable;

    public enum ErrorCode {
        RATE_LIMIT(429, true),
        SERVER_ERROR(500, true),
        BAD_GATEWAY(502, true),
        SERVICE_UNAVAILABLE(503, true),
        GATEWAY_TIMEOUT(504, true),
        TIMEOUT(-1, true),
        NETWORK_ERROR(-2, true),
        INVALID_REQUEST(400, false),
        AUTHENTICATION_ERROR(401, false),
        PERMISSION_DENIED(403, false),
        NOT_FOUND(404, false),
        CONTEXT_LENGTH_EXCEEDED(413, false),
        UNKNOWN(0, false);

        private final int statusCode;
        private final boolean retryable;

        ErrorCode(int statusCode, boolean retryable) {
            this.statusCode = statusCode;
            this.retryable = retryable;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public boolean isRetryable() {
            return retryable;
        }

        public static ErrorCode fromStatusCode(int statusCode) {
            for (ErrorCode code : values()) {
                if (code.statusCode == statusCode) {
                    return code;
                }
            }
            if (statusCode >= 500) {
                return SERVER_ERROR;
            }
            return UNKNOWN;
        }
    }

    public LlmApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.statusCode = errorCode.getStatusCode();
        this.retryable = errorCode.isRetryable();
    }

    public LlmApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.statusCode = errorCode.getStatusCode();
        this.retryable = errorCode.isRetryable();
    }

    public LlmApiException(int statusCode, String message) {
        this(ErrorCode.fromStatusCode(statusCode), message);
    }

    public LlmApiException(int statusCode, String message, Throwable cause) {
        this(ErrorCode.fromStatusCode(statusCode), message, cause);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isRateLimit() {
        return errorCode == ErrorCode.RATE_LIMIT;
    }

    public boolean isTimeout() {
        return errorCode == ErrorCode.TIMEOUT || errorCode == ErrorCode.GATEWAY_TIMEOUT;
    }

    public boolean isServerError() {
        return errorCode == ErrorCode.SERVER_ERROR || 
               errorCode == ErrorCode.BAD_GATEWAY ||
               errorCode == ErrorCode.SERVICE_UNAVAILABLE ||
               errorCode == ErrorCode.GATEWAY_TIMEOUT;
    }
}
