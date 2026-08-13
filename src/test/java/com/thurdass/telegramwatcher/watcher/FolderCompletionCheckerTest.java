package com.thurdass.telegramwatcher.watcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FolderCompletionCheckerTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void snapshotTracksContentAndIgnoresConfiguredGeneratedFiles() throws Exception {
        Path source = temporaryFolder.resolve("download");
        Path ignored = source.resolve(".telegram-upload");
        Files.createDirectories(ignored);
        Files.writeString(source.resolve("visible.txt"), "one");
        Files.writeString(ignored.resolve("generated.zip"), "ignored");

        FolderCompletionChecker checker = new FolderCompletionChecker(ignored);
        FolderSnapshot first = checker.snapshot(source);
        Files.writeString(source.resolve("visible.txt"), "two-more");
        FolderSnapshot second = checker.snapshot(source);

        assertEquals(1, first.fileCount());
        assertEquals(1, first.directoryCount());
        assertNotEquals(first.totalSize(), second.totalSize());
        assertNotEquals(first.metadataFingerprint(), second.metadataFingerprint());
    }
}
