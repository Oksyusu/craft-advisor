package com.kuh.craftadvisor.ai;

/** A failed AI call, classified so the chat can show a helpful message. Never contains the API key. */
public final class AiException extends RuntimeException {
    public enum Kind { NO_KEY, BAD_KEY, RATE_LIMITED, OVERLOADED, REFUSED, BAD_REQUEST, NETWORK, BAD_RESPONSE }

    private final Kind kind;

    public AiException(Kind kind, String detail) {
        super(detail);
        this.kind = kind;
    }

    public AiException(Kind kind, String detail, Throwable cause) {
        super(detail, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Unwraps CompletableFuture's CompletionException. */
    public static AiException from(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof AiException)) {
            cause = cause.getCause();
        }
        return cause instanceof AiException ai ? ai : new AiException(Kind.NETWORK, String.valueOf(cause.getMessage()), cause);
    }
}
