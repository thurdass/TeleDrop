package com.thurdass.telegramwatcher.model;

import java.nio.file.Path;

public record DownloadTask(Path folder) {
    public DownloadTask {
        folder = folder.toAbsolutePath().normalize();
    }
}
