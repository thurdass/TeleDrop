package com.thurdass.telegramwatcher.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ChecksumUtils {
    private ChecksumUtils() {
    }

    public static String sha256(Path path, int bufferSize) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[bufferSize];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    public static String sha256String(String value) {
        MessageDigest digest = newSha256();
        return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 não está disponível", exception);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0F, 16));
            result.append(Character.forDigit(value & 0x0F, 16));
        }
        return result.toString();
    }
}
