package com.thurdass.telegramwatcher.archive;

import java.nio.file.Path;

public record ArchiveEntryPlan(
        String archiveName,
        String originalPath,
        Path sourcePath,
        long sourceOffset,
        long sizeBytes,
        boolean directory,
        boolean splitPart,
        int splitPartNumber) {
}
