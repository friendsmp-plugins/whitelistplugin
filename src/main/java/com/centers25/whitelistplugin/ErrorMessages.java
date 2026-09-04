package com.centers25.whitelistplugin;

final class ErrorMessages {
    private ErrorMessages() { }

    static String safe(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getMessage() == null) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) return current.getClass().getSimpleName();
        message = message.replaceAll("(?i)(access_token|refresh_token|token)[=: ]+[^ ,}\"]+", "$1=[redacted]");
        return message.length() > 500 ? message.substring(0, 500) + "…" : message;
    }
}
