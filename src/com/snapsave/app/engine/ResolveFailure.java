package com.snapsave.app.engine;

import java.net.SocketTimeoutException;
import java.util.Locale;

/** Sanitized failure information returned by the resolver pipeline. */
public final class ResolveFailure {

    public enum Code {
        NONE,
        AUTH,
        QUOTA,
        NETWORK,
        TIMEOUT,
        UNSUPPORTED,
        CANCELLED
    }

    public final Code code;
    public final String errorCode;
    public final String userMessage;

    public ResolveFailure(Code code, String userMessage) {
        this.code = code == null ? Code.NETWORK : code;
        this.errorCode = this.code.name().toLowerCase(Locale.US);
        this.userMessage = userMessage == null ? messageFor(this.code) : userMessage;
    }

    public static ResolveFailure forCode(Code code) {
        return new ResolveFailure(code, messageFor(code));
    }

    /** Classify without retaining an exception message or response body. */
    public static Code codeFor(Throwable error) {
        if (error == null) {
            return Code.NONE;
        }
        if (error instanceof Http.Timeout || error instanceof SocketTimeoutException) {
            return Code.TIMEOUT;
        }
        if (error instanceof Http.Cancelled || error instanceof InterruptedException) {
            return Code.CANCELLED;
        }
        if (error instanceof Http.HttpException) {
            return codeForStatus(((Http.HttpException) error).status);
        }
        String text = safeText(error);
        if (text.contains("timeout") || text.contains("timed out") || text.contains("time out")) {
            return Code.TIMEOUT;
        }
        if (containsAny(text, "quota", "credit", "rate limit", "too many requests", "429")) {
            return Code.QUOTA;
        }
        if (containsAny(text, "unauthor", "forbidden", "invalid key", "invalid token", "401", "403")) {
            return Code.AUTH;
        }
        return Code.NETWORK;
    }

    public static Code codeForStatus(int status) {
        if (status == 401 || status == 403) {
            return Code.AUTH;
        }
        if (status == 402 || status == 408 || status == 429) {
            return status == 408 ? Code.TIMEOUT : Code.QUOTA;
        }
        return status >= 400 ? Code.NETWORK : Code.NONE;
    }

    /** Classify a sanitized backend status/error hint without exposing it to the user. */
    public static Code codeForText(String value) {
        if (value == null) {
            return Code.NETWORK;
        }
        String text = value.toLowerCase(Locale.US);
        if (containsAny(text, "quota", "credit", "rate limit", "too many requests", "429")) {
            return Code.QUOTA;
        }
        if (containsAny(text, "unauthor", "forbidden", "invalid key", "invalid token", "401", "403")) {
            return Code.AUTH;
        }
        return Code.NETWORK;
    }

    /** Choose the most useful final code when several fallback paths fail. */
    public static Code stronger(Code current, Code candidate) {
        if (candidate == null || candidate == Code.NONE) {
            return current == null ? Code.NONE : current;
        }
        if (current == null || current == Code.NONE || rank(candidate) > rank(current)) {
            return candidate;
        }
        return current;
    }

    public static String messageFor(Code code) {
        if (code == null) {
            return "Couldn't resolve this link.";
        }
        switch (code) {
            case AUTH:
                return "The resolver credentials were rejected.";
            case QUOTA:
                return "The resolver has reached its usage limit.";
            case TIMEOUT:
                return "Resolving this link took too long.";
            case CANCELLED:
                return "Resolving was cancelled.";
            case UNSUPPORTED:
                return "This link has no supported downloadable media.";
            case NETWORK:
                return "The resolver could not be reached.";
            case NONE:
            default:
                return "";
        }
    }

    private static int rank(Code code) {
        switch (code) {
            case TIMEOUT:
                return 60;
            case CANCELLED:
                return 50;
            case AUTH:
                return 40;
            case QUOTA:
                return 35;
            case NETWORK:
                return 30;
            case UNSUPPORTED:
                return 10;
            case NONE:
            default:
                return 0;
        }
    }

    private static String safeText(Throwable error) {
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 4) {
            String message = current.getMessage();
            if (message != null) {
                out.append(message.toLowerCase(Locale.US)).append(' ');
            }
            current = current.getCause();
        }
        return out.toString();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
