package com.thurdass.telegramwatcher.state;

import com.thurdass.telegramwatcher.model.VolumeInfo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public final class UploadState {
    public enum Status {
        WAITING,
        PLANNING,
        BUILDING,
        UPLOADING,
        FAILED,
        COMPLETED
    }

    private final String id;
    private final Path folder;
    private final Instant createdAt;
    private Instant updatedAt;
    private long sourceFileCount;
    private long sourceDirectoryCount;
    private long sourceTotalBytes;
    private long sourceLatestModification;
    private long sourceFingerprint;
    private long maxVolumeBytes;
    private int totalParts;
    private int nextPart = 1;
    private int uploadedParts;
    private long uploadedBytes;
    private boolean manifestUploaded;
    private Status status;
    private String lastError = "";
    private final Map<Integer, VolumeInfo> volumes = new TreeMap<>();

    public UploadState(String id, Path folder, Instant createdAt, Status status) {
        this.id = id;
        this.folder = folder.toAbsolutePath().normalize();
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = status;
    }

    public String id() {
        return id;
    }

    public Path folder() {
        return folder;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long sourceFileCount() {
        return sourceFileCount;
    }

    public long sourceDirectoryCount() {
        return sourceDirectoryCount;
    }

    public long sourceTotalBytes() {
        return sourceTotalBytes;
    }

    public long sourceLatestModification() {
        return sourceLatestModification;
    }

    public long sourceFingerprint() {
        return sourceFingerprint;
    }

    public long maxVolumeBytes() {
        return maxVolumeBytes;
    }

    public int totalParts() {
        return totalParts;
    }

    public int nextPart() {
        return nextPart;
    }

    public int uploadedParts() {
        return uploadedParts;
    }

    public long uploadedBytes() {
        return uploadedBytes;
    }

    public boolean manifestUploaded() {
        return manifestUploaded;
    }

    public Status status() {
        return status;
    }

    public String lastError() {
        return lastError;
    }

    public Map<Integer, VolumeInfo> volumes() {
        return Collections.unmodifiableMap(volumes);
    }

    public void setPlan(long sourceFileCount, long sourceDirectoryCount, long sourceTotalBytes,
                        long sourceLatestModification, long sourceFingerprint, long maxVolumeBytes,
                        int totalParts) {
        this.sourceFileCount = sourceFileCount;
        this.sourceDirectoryCount = sourceDirectoryCount;
        this.sourceTotalBytes = sourceTotalBytes;
        this.sourceLatestModification = sourceLatestModification;
        this.sourceFingerprint = sourceFingerprint;
        this.maxVolumeBytes = maxVolumeBytes;
        this.totalParts = totalParts;
        touch();
    }

    public void setStatus(Status status) {
        this.status = status;
        touch();
    }

    public void setError(String error) {
        this.lastError = error == null ? "" : error;
        touch();
    }

    public void setManifestUploaded(boolean manifestUploaded) {
        this.manifestUploaded = manifestUploaded;
        touch();
    }

    public void recordVolumeBuilt(VolumeInfo volume) {
        volumes.put(volume.number(), volume);
        touch();
    }

    public void recordVolumeUploaded(VolumeInfo volume) {
        VolumeInfo uploaded = new VolumeInfo(volume.number(), volume.name(), volume.sizeBytes(), volume.sha256(), true);
        volumes.put(volume.number(), uploaded);
        uploadedParts = Math.max(uploadedParts, countUploadedParts());
        uploadedBytes = sumUploadedBytes();
        nextPart = Math.max(nextPart, volume.number() + 1);
        touch();
    }

    public void setNextPart(int nextPart) {
        this.nextPart = nextPart;
        touch();
    }

    public void setUploadedCounters(int uploadedParts, long uploadedBytes) {
        this.uploadedParts = uploadedParts;
        this.uploadedBytes = uploadedBytes;
        touch();
    }

    public void restoreVolume(VolumeInfo volume) {
        volumes.put(volume.number(), volume);
    }

    public void restoreProgress(int nextPart, int uploadedParts, long uploadedBytes, boolean manifestUploaded) {
        this.nextPart = nextPart;
        this.uploadedParts = uploadedParts;
        this.uploadedBytes = uploadedBytes;
        this.manifestUploaded = manifestUploaded;
    }

    public void restorePlan(long sourceFileCount, long sourceDirectoryCount, long sourceTotalBytes,
                            long sourceLatestModification, long sourceFingerprint, long maxVolumeBytes,
                            int totalParts) {
        setPlan(sourceFileCount, sourceDirectoryCount, sourceTotalBytes, sourceLatestModification,
                sourceFingerprint, maxVolumeBytes, totalParts);
    }

    public void restoreStatus(Status status, String lastError, Instant updatedAt) {
        this.status = status;
        this.lastError = lastError == null ? "" : lastError;
        this.updatedAt = updatedAt;
    }

    private int countUploadedParts() {
        int count = 0;
        for (VolumeInfo volume : volumes.values()) {
            if (volume.uploaded()) {
                count++;
            }
        }
        return count;
    }

    private long sumUploadedBytes() {
        long total = 0;
        for (VolumeInfo volume : volumes.values()) {
            if (volume.uploaded()) {
                total = Math.addExact(total, volume.sizeBytes());
            }
        }
        return total;
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
