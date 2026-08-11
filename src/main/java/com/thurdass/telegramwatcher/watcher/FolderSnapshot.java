package com.thurdass.telegramwatcher.watcher;

public record FolderSnapshot(
        long fileCount,
        long directoryCount,
        long totalSize,
        long latestModification,
        long metadataFingerprint) {

    public boolean hasFiles() {
        return fileCount > 0;
    }
}
