package com.thurdass.telegramwatcher.telegram;

import com.thurdass.telegramwatcher.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TelegramClient {
    private static final Pattern OK_PATTERN = Pattern.compile("\\\"ok\\\"\\s*:\\s*(true|false)");
    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\\\"error_code\\\"\\s*:\\s*(\\d+)");
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("\\\"retry_after\\\"\\s*:\\s*(\\d+)");
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("\\\"description\\\"\\s*:\\s*\\\"");

    private final AppConfig config;
    private final HttpClient httpClient;

    public TelegramClient(AppConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.telegramConnectTimeout())
                .build();
    }

    public TelegramUploadResult sendDocument(Path file, String fileName, String contentType)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(file)) {
            return TelegramUploadResult.failure("Arquivo para upload não existe: " + file);
        }

        String boundary = "----TeleDrop-" + UUID.randomUUID();
        byte[] chatPart = partStart(boundary, "chat_id", null, "text/plain", config.telegramChatId());
        byte[] documentHeader = documentHeader(boundary, fileName, contentType);
        byte[] ending = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(chatPart),
                HttpRequest.BodyPublishers.ofByteArray(documentHeader),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofByteArray(ending));

        HttpRequest request = HttpRequest.newBuilder(sendDocumentUri())
                .timeout(config.telegramRequestTimeout())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(body)
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return parseResponse(response.statusCode(), response.body());
    }

    private URI sendDocumentUri() {
        return URI.create(config.telegramApiBaseUri() + "/bot" + config.telegramBotToken() + "/sendDocument");
    }

    private static byte[] partStart(String boundary, String name, String fileName, String contentType, String value) {
        StringBuilder result = new StringBuilder()
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(escapeHeader(name)).append("\"");
        if (fileName != null) {
            result.append("; filename=\"").append(escapeHeader(fileName)).append("\"");
        }
        result.append("\r\nContent-Type: ").append(contentType).append("\r\n\r\n").append(value).append("\r\n");
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] documentHeader(String boundary, String fileName, String contentType) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"document\"; filename=\""
                + escapeHeader(fileName) + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        return header.getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeHeader(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "_")
                .replace("\n", "_");
    }

    private static TelegramUploadResult parseResponse(int httpStatus, String response) {
        boolean success = booleanValue(OK_PATTERN, response);
        int errorCode = intValue(ERROR_CODE_PATTERN, response, 0);
        Integer retryAfter = nullableIntValue(RETRY_AFTER_PATTERN, response);
        String description = stringValue(DESCRIPTION_PATTERN, response);
        if (description.isBlank()) {
            description = success ? "ok" : "HTTP " + httpStatus;
        }
        return new TelegramUploadResult(success && httpStatus >= 200 && httpStatus < 300,
                httpStatus, errorCode, description, retryAfter);
    }

    private static boolean booleanValue(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }

    private static int intValue(Pattern pattern, String value, int defaultValue) {
        Integer result = nullableIntValue(pattern, value);
        return result == null ? defaultValue : result;
    }

    private static Integer nullableIntValue(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String stringValue(Pattern startPattern, String value) {
        Matcher matcher = startPattern.matcher(value);
        if (!matcher.find()) {
            return "";
        }
        int start = matcher.end();
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                result.append(switch (character) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> character;
                });
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                break;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }
}
