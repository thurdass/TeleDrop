package com.thurdass.telegramwatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationLockTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void rejectsASecondProcessUntilTheFirstLockIsReleased() throws Exception {
        Path lockPath = temporaryFolder.resolve("teledrop.lock");

        try (ApplicationLock ignored = ApplicationLock.acquire(lockPath)) {
            assertThrows(IOException.class, () -> ApplicationLock.acquire(lockPath));
        }

        assertDoesNotThrow(() -> {
            try (ApplicationLock ignored = ApplicationLock.acquire(lockPath)) {
                // The lock is available again after the first owner closes it.
            }
        });
    }
}
