package com.thurdass.telegramwatcher.telegram;

public record TelegramUploadResult(
        boolean success,
        int httpStatus,
        int errorCode,
        String description,
        Integer retryAfterSeconds) {

    public static TelegramUploadResult failure(String description) {
        return new TelegramUploadResult(false, 0, 0, description, null);
    }
}
