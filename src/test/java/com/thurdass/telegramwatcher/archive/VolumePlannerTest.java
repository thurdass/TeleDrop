package com.thurdass.telegramwatcher.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumePlannerTest {
    private static final long MAX_VOLUME_BYTES = 120_000L;

    @TempDir
    Path temporaryFolder;

    @Test
    void splitsLargeFilesWithoutLoadingThemIntoThePlan() throws Exception {
        Path source = temporaryFolder.resolve("dataset");
        Path nested = source.resolve("nested");
        Files.createDirectories(nested);
        Files.write(nested.resolve("large.bin"), patternedBytes(180_000));
        Files.writeString(source.resolve("small.txt"), "small file");

        ArchivePlan plan = new VolumePlanner(MAX_VOLUME_BYTES, temporaryFolder.resolve("ignored"))
                .plan(source);

        assertEquals(2, plan.fileCount());
        assertEquals(2, plan.directoryCount());
        assertEquals(180_000L + "small file".length(), plan.totalSourceBytes());
        assertTrue(plan.totalParts() > 1);

        Set<String> archiveNames = new HashSet<>();
        long plannedPayloadBytes = 0;
        int splitEntries = 0;
        for (VolumePlan volume : plan.volumes()) {
            assertTrue(volume.estimatedBytes() + 64L * 1024L <= MAX_VOLUME_BYTES);
            for (ArchiveEntryPlan entry : volume.entries()) {
                assertTrue(archiveNames.add(entry.archiveName()));
                plannedPayloadBytes += entry.sizeBytes();
                if (entry.splitPart()) {
                    splitEntries++;
                }
            }
        }

        assertTrue(splitEntries > 1);
        assertEquals(plan.totalSourceBytes(), plannedPayloadBytes);
        assertFalse(archiveNames.isEmpty());
    }

    private static byte[] patternedBytes(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31);
        }
        return bytes;
    }
}
