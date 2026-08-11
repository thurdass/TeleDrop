package com.thurdass.telegramwatcher.archive;

import java.nio.file.Path;
import java.util.List;

public final class ArchivePlan {
    private final Path sourceFolder;
    private final long fileCount;
    private final long directoryCount;
    private final long totalSourceBytes;
    private final List<VolumePlan> volumes;

    public ArchivePlan(Path sourceFolder, long fileCount, long directoryCount, long totalSourceBytes,
                       List<VolumePlan> volumes) {
        this.sourceFolder = sourceFolder.toAbsolutePath().normalize();
        this.fileCount = fileCount;
        this.directoryCount = directoryCount;
        this.totalSourceBytes = totalSourceBytes;
        this.volumes = List.copyOf(volumes);
    }

    public Path sourceFolder() {
        return sourceFolder;
    }

    public long fileCount() {
        return fileCount;
    }

    public long directoryCount() {
        return directoryCount;
    }

    public long totalSourceBytes() {
        return totalSourceBytes;
    }

    public List<VolumePlan> volumes() {
        return volumes;
    }

    public int totalParts() {
        return volumes.size();
    }
}
