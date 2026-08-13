package com.thurdass.telegramwatcher.archive;

import com.thurdass.telegramwatcher.model.VolumeInfo;
import com.thurdass.telegramwatcher.util.ChecksumUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeBuilderTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void buildsAStoredZipAndCalculatesItsChecksum() throws Exception {
        Path source = temporaryFolder.resolve("source");
        Files.createDirectories(source);
        byte[] content = patternedBytes(12_345);
        Files.write(source.resolve("file.bin"), content);

        ArchivePlan plan = new VolumePlanner(100_000L, temporaryFolder.resolve("ignored"))
                .plan(source);
        Path output = temporaryFolder.resolve("volume.zip");
        VolumeInfo info = new VolumeBuilder(true, 4 * 1024, 100_000L)
                .build(plan.volumes().getFirst(), output);

        assertTrue(info.sizeBytes() <= 100_000L);
        assertEquals(ChecksumUtils.sha256(output, 4 * 1024), info.sha256());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            ZipEntry entry = zip.getEntry("file.bin");
            assertEquals(ZipEntry.STORED, entry.getMethod());
            assertArrayEquals(content, zip.getInputStream(entry).readAllBytes());
        }
    }

    private static byte[] patternedBytes(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 17);
        }
        return bytes;
    }
}
