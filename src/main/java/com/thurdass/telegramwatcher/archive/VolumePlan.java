package com.thurdass.telegramwatcher.archive;

import java.util.List;

public final class VolumePlan {
    private final int number;
    private final String name;
    private final List<ArchiveEntryPlan> entries;
    private final long estimatedBytes;

    public VolumePlan(int number, String name, List<ArchiveEntryPlan> entries, long estimatedBytes) {
        this.number = number;
        this.name = name;
        this.entries = List.copyOf(entries);
        this.estimatedBytes = estimatedBytes;
    }

    public int number() {
        return number;
    }

    public String name() {
        return name;
    }

    public List<ArchiveEntryPlan> entries() {
        return entries;
    }

    public long estimatedBytes() {
        return estimatedBytes;
    }
}
