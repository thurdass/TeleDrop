package com.thurdass.telegramwatcher.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class FileUtils {
    private FileUtils() {
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1_000L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        int unit = -1;
        while (value >= 1_000.0 && unit < units.length - 1) {
            value /= 1_000.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    public static String archivePath(Path root, Path path) {
        String relative = root.relativize(path).toString();
        return relative.replace(path.getFileSystem().getSeparator(), "/");
    }

    public static boolean isSameOrChild(Path path, Path parent) {
        return path.toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize());
    }

    public static void ensureDirectory(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().normalize());
    }

    public static String formatPercentage(long completed, long total) {
        if (total <= 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", (completed * 100.0) / total);
    }
}
