package com.thurdass.telegramwatcher.state;

import com.thurdass.telegramwatcher.model.VolumeInfo;
import com.thurdass.telegramwatcher.util.ChecksumUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

public final class UploadStateStore {
    private final Path stateFolder;

    public UploadStateStore(Path stateFolder) throws IOException {
        this.stateFolder = stateFolder.toAbsolutePath().normalize();
        Files.createDirectories(this.stateFolder);
    }

    public synchronized UploadState loadOrCreate(Path folder) throws IOException {
        Path normalizedFolder = folder.toAbsolutePath().normalize();
        Path path = statePath(normalizedFolder);
        if (!Files.exists(path)) {
            return new UploadState(stateId(normalizedFolder), normalizedFolder, Instant.now(), UploadState.Status.WAITING);
        }
        return read(path);
    }

    public synchronized List<UploadState> loadAll() throws IOException {
        List<UploadState> states = new ArrayList<>();
        try (Stream<Path> files = Files.list(stateFolder)) {
            files.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            states.add(read(path));
                        } catch (IOException | RuntimeException exception) {
                            System.err.println("[STATE] Ignorando estado inválido " + path + ": " + exception.getMessage());
                        }
                    });
        }
        return states;
    }

    public synchronized void save(UploadState state) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("id", state.id());
        properties.setProperty("folder", state.folder().toString());
        properties.setProperty("created.at", state.createdAt().toString());
        properties.setProperty("updated.at", state.updatedAt().toString());
        properties.setProperty("source.file.count", Long.toString(state.sourceFileCount()));
        properties.setProperty("source.directory.count", Long.toString(state.sourceDirectoryCount()));
        properties.setProperty("source.total.bytes", Long.toString(state.sourceTotalBytes()));
        properties.setProperty("source.latest.modification", Long.toString(state.sourceLatestModification()));
        properties.setProperty("source.fingerprint", Long.toString(state.sourceFingerprint()));
        properties.setProperty("max.volume.bytes", Long.toString(state.maxVolumeBytes()));
        properties.setProperty("total.parts", Integer.toString(state.totalParts()));
        properties.setProperty("next.part", Integer.toString(state.nextPart()));
        properties.setProperty("uploaded.parts", Integer.toString(state.uploadedParts()));
        properties.setProperty("uploaded.bytes", Long.toString(state.uploadedBytes()));
        properties.setProperty("manifest.uploaded", Boolean.toString(state.manifestUploaded()));
        properties.setProperty("status", state.status().name());
        properties.setProperty("last.error", state.lastError());
        for (VolumeInfo volume : state.volumes().values()) {
            String prefix = "volume." + volume.number();
            properties.setProperty(prefix + ".name", volume.name());
            properties.setProperty(prefix + ".size.bytes", Long.toString(volume.sizeBytes()));
            properties.setProperty(prefix + ".sha256", volume.sha256() == null ? "" : volume.sha256());
            properties.setProperty(prefix + ".uploaded", Boolean.toString(volume.uploaded()));
        }

        Path target = statePath(state.folder());
        Path temporary = Files.createTempFile(stateFolder, target.getFileName().toString(), ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "TeleDrop upload state");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path statePath(Path folder) {
        return stateFolder.resolve(stateId(folder) + ".properties");
    }

    private UploadState read(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        Path folder = Path.of(required(properties, "folder")).toAbsolutePath().normalize();
        String id = properties.getProperty("id", stateId(folder));
        Instant createdAt = Instant.parse(required(properties, "created.at"));
        Instant updatedAt = Instant.parse(properties.getProperty("updated.at", createdAt.toString()));
        UploadState.Status status = UploadState.Status.valueOf(properties.getProperty("status", "WAITING"));
        UploadState state = new UploadState(id, folder, createdAt, status);
        state.restorePlan(
                longValue(properties, "source.file.count", 0),
                longValue(properties, "source.directory.count", 0),
                longValue(properties, "source.total.bytes", 0),
                longValue(properties, "source.latest.modification", 0),
                longValue(properties, "source.fingerprint", 0),
                longValue(properties, "max.volume.bytes", 0),
                intValue(properties, "total.parts", 0));
        state.restoreProgress(
                intValue(properties, "next.part", 1),
                intValue(properties, "uploaded.parts", 0),
                longValue(properties, "uploaded.bytes", 0),
                booleanValue(properties, "manifest.uploaded", false));
        state.restoreStatus(status, properties.getProperty("last.error", ""), updatedAt);

        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("volume.") || !key.endsWith(".name")) {
                continue;
            }
            String numberText = key.substring("volume.".length(), key.length() - ".name".length());
            int number = Integer.parseInt(numberText);
            String prefix = "volume." + number;
            state.restoreVolume(new VolumeInfo(
                    number,
                    properties.getProperty(prefix + ".name"),
                    longValue(properties, prefix + ".size.bytes", 0),
                    properties.getProperty(prefix + ".sha256", ""),
                    booleanValue(properties, prefix + ".uploaded", false)));
        }
        return state;
    }

    private String stateId(Path folder) {
        return ChecksumUtils.sha256String(folder.toAbsolutePath().normalize().toString());
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Estado sem propriedade: " + key);
        }
        return value;
    }

    private static long longValue(Properties properties, String key, long defaultValue) {
        String value = properties.getProperty(key, Long.toString(defaultValue));
        return Long.parseLong(value);
    }

    private static int intValue(Properties properties, String key, int defaultValue) {
        return Integer.parseInt(properties.getProperty(key, Integer.toString(defaultValue)));
    }

    private static boolean booleanValue(Properties properties, String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(defaultValue)));
    }
}
