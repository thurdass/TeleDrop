package com.thurdass.telegramwatcher.queue;

import com.thurdass.telegramwatcher.archive.ArchivePlan;
import com.thurdass.telegramwatcher.archive.VolumeBuilder;
import com.thurdass.telegramwatcher.archive.VolumePlan;
import com.thurdass.telegramwatcher.archive.VolumePlanner;
import com.thurdass.telegramwatcher.config.AppConfig;
import com.thurdass.telegramwatcher.manifest.ManifestWriter;
import com.thurdass.telegramwatcher.model.DownloadTask;
import com.thurdass.telegramwatcher.model.VolumeInfo;
import com.thurdass.telegramwatcher.state.UploadState;
import com.thurdass.telegramwatcher.state.UploadStateStore;
import com.thurdass.telegramwatcher.telegram.TelegramClient;
import com.thurdass.telegramwatcher.telegram.TelegramUploadResult;
import com.thurdass.telegramwatcher.util.ChecksumUtils;
import com.thurdass.telegramwatcher.util.FileUtils;
import com.thurdass.telegramwatcher.watcher.FolderCompletionChecker;
import com.thurdass.telegramwatcher.watcher.FolderSnapshot;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

public final class UploadWorker implements Runnable {
    private final UploadQueue queue;
    private final UploadStateStore stateStore;
    private final AppConfig config;
    private final TelegramClient telegramClient;
    private final FolderCompletionChecker completionChecker;

    public UploadWorker(UploadQueue queue, UploadStateStore stateStore, AppConfig config,
                        TelegramClient telegramClient) {
        this.queue = queue;
        this.stateStore = stateStore;
        this.config = config;
        this.telegramClient = telegramClient;
        this.completionChecker = new FolderCompletionChecker(config.tempFolder());
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            UploadQueue.QueueItem item;
            try {
                item = queue.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            if (item.poison()) {
                return;
            }

            try {
                process(item.task());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                markFailed(item.task(), exception);
            } finally {
                queue.completed(item.task());
            }
        }
    }

    private void process(DownloadTask task) throws IOException, InterruptedException {
        Path sourceFolder = task.folder();
        if (!Files.isDirectory(sourceFolder)) {
            System.err.println("[UPLOAD] Pasta não existe mais: " + sourceFolder);
            return;
        }

        UploadState state = stateStore.loadOrCreate(sourceFolder);
        if (state.status() == UploadState.Status.COMPLETED) {
            cleanupTemporaryArtifacts(sourceFolder, state);
            System.out.println("[SKIP] Já concluído: " + sourceFolder.getFileName());
            return;
        }

        FolderSnapshot sourceSnapshot = completionChecker.snapshot(sourceFolder);
        if (!sourceSnapshot.hasFiles()) {
            state.setStatus(UploadState.Status.WAITING);
            state.setError("A pasta não possui arquivos");
            stateStore.save(state);
            return;
        }

        state.setStatus(UploadState.Status.PLANNING);
        state.setError("");
        stateStore.save(state);

        ArchivePlan plan = new VolumePlanner(config.telegramPartMaxBytes(), config.tempFolder()).plan(sourceFolder);
        if (!matchesSnapshot(plan, sourceSnapshot)) {
            throw new IOException("A pasta mudou durante o planejamento; será necessário aguardar nova estabilidade");
        }

        if (state.totalParts() > 0) {
            if (!sameStoredPlan(state, sourceSnapshot, plan)) {
                throw new IOException("A pasta ou o limite de volume mudou depois do início do upload");
            }
        } else {
            state.setPlan(sourceSnapshot.fileCount(), sourceSnapshot.directoryCount(), sourceSnapshot.totalSize(),
                    sourceSnapshot.latestModification(), sourceSnapshot.metadataFingerprint(),
                    config.telegramPartMaxBytes(), plan.totalParts());
            stateStore.save(state);
        }

        Path temporaryFolder = temporaryFolder(sourceFolder);
        Files.createDirectories(temporaryFolder);
        VolumeBuilder volumeBuilder = new VolumeBuilder(config.checksumEnabled(), config.archiveBufferBytes(),
                config.telegramPartMaxBytes());

        for (VolumePlan volume : plan.volumes()) {
            if (volume.number() < state.nextPart()) {
                VolumeInfo previous = state.volumes().get(volume.number());
                if (previous != null && previous.uploaded()) {
                    tryDeleteGeneratedFile(temporaryFolder.resolve(previous.name()));
                }
                continue;
            }
            ensureSourceUnchanged(sourceFolder, state);
            state.setStatus(UploadState.Status.BUILDING);
            stateStore.save(state);
            VolumeInfo info = prepareVolume(volume, temporaryFolder, state, volumeBuilder);
            state.recordVolumeBuilt(info);
            state.setNextPart(volume.number());
            state.setStatus(UploadState.Status.UPLOADING);
            stateStore.save(state);

            Path localFile = temporaryFolder.resolve(volume.name());
            System.out.println("[UPLOAD] Enviando " + volume.name() + " (parte " + volume.number() + "/"
                    + plan.totalParts() + ")");
            TelegramUploadResult result = uploadWithRetry(localFile, volume.name());
            if (!result.success()) {
                state.setStatus(UploadState.Status.FAILED);
                state.setError(result.description());
                stateStore.save(state);
                System.err.println("[FAILED] " + volume.name() + ": " + result.description());
                return;
            }

            state.recordVolumeUploaded(info);
            state.setStatus(UploadState.Status.UPLOADING);
            state.setError("");
            stateStore.save(state);
            deleteGeneratedFile(localFile);
            System.out.println("[SUCCESS] " + volume.name() + " enviado");
            System.out.println("[CLEANUP] " + volume.name() + " removido localmente");
            logProgress(state, plan);
        }

        if (!state.manifestUploaded()) {
            ensureSourceUnchanged(sourceFolder, state);
            Path manifest = temporaryFolder.resolve("manifest.json");
            ManifestWriter.write(manifest, plan, state);
            state.setStatus(UploadState.Status.UPLOADING);
            stateStore.save(state);
            System.out.println("[UPLOAD] Enviando manifest.json");
            TelegramUploadResult result = uploadWithRetry(manifest,
                    sourceFolder.getFileName() + ".manifest.json");
            if (!result.success()) {
                state.setStatus(UploadState.Status.FAILED);
                state.setError(result.description());
                stateStore.save(state);
                System.err.println("[FAILED] manifest.json: " + result.description());
                return;
            }
            state.setManifestUploaded(true);
            state.setStatus(UploadState.Status.COMPLETED);
            state.setError("");
            stateStore.save(state);
            tryDeleteGeneratedFile(manifest);
        }

        state.setStatus(UploadState.Status.COMPLETED);
        state.setError("");
        stateStore.save(state);
        System.out.println("[SUCCESS] Todas as partes de " + sourceFolder.getFileName() + " foram enviadas.");
        cleanupTemporaryArtifacts(sourceFolder, state);
    }

    private VolumeInfo prepareVolume(VolumePlan volume, Path temporaryFolder, UploadState state,
                                    VolumeBuilder volumeBuilder) throws IOException {
        Path finalFile = temporaryFolder.resolve(volume.name());
        Path partialFile = temporaryFolder.resolve(volume.name() + ".tmp");
        VolumeInfo previous = state.volumes().get(volume.number());

        if (Files.exists(finalFile)) {
            long size = Files.size(finalFile);
            String checksum = config.checksumEnabled() ? ChecksumUtils.sha256(finalFile, config.archiveBufferBytes()) : "";
            boolean valid = size <= config.telegramPartMaxBytes()
                    && (previous == null || previous.sha256().isBlank() || previous.sha256().equals(checksum));
            if (valid) {
                System.out.println("[VOLUME] Reutilizando " + volume.name() + " - " + FileUtils.formatBytes(size));
                return new VolumeInfo(volume.number(), volume.name(), size, checksum, false);
            }
            deleteGeneratedFile(finalFile);
        }

        deleteGeneratedFile(partialFile);
        System.out.println("[VOLUME] Criando " + volume.name() + "...");
        VolumeInfo built = volumeBuilder.build(volume, partialFile);
        moveGeneratedFile(partialFile, finalFile);
        System.out.println("[VOLUME] " + volume.name() + " pronto - " + FileUtils.formatBytes(built.sizeBytes()));
        return built;
    }

    private TelegramUploadResult uploadWithRetry(Path file, String fileName) throws InterruptedException {
        TelegramUploadResult last = TelegramUploadResult.failure("upload não executado");
        for (int attempt = 1; attempt <= config.telegramRetryMax(); attempt++) {
            try {
                last = telegramClient.sendDocument(file, fileName,
                        fileName.endsWith(".json") ? "application/json" : "application/zip");
            } catch (IOException exception) {
                last = TelegramUploadResult.failure(exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage());
            }
            if (last.success()) {
                return last;
            }
            System.err.println("[RETRY] " + fileName + " tentativa " + attempt + "/" + config.telegramRetryMax()
                    + ": " + last.description());
            if (attempt < config.telegramRetryMax()) {
                Duration delay = retryDelay(attempt, last);
                Thread.sleep(delay.toMillis());
            }
        }
        return last;
    }

    private Duration retryDelay(int attempt, TelegramUploadResult result) {
        int index = Math.min(attempt - 1, config.telegramRetryDelays().size() - 1);
        Duration configured = config.telegramRetryDelays().get(index);
        if (result.retryAfterSeconds() == null) {
            return configured;
        }
        return configured.compareTo(Duration.ofSeconds(result.retryAfterSeconds())) >= 0
                ? configured : Duration.ofSeconds(result.retryAfterSeconds());
    }

    private void ensureSourceUnchanged(Path sourceFolder, UploadState state) throws IOException {
        FolderSnapshot current = completionChecker.snapshot(sourceFolder);
        if (current.fileCount() != state.sourceFileCount()
                || current.directoryCount() != state.sourceDirectoryCount()
                || current.totalSize() != state.sourceTotalBytes()
                || current.latestModification() != state.sourceLatestModification()
                || current.metadataFingerprint() != state.sourceFingerprint()) {
            throw new IOException("A pasta original foi modificada durante o processamento: " + sourceFolder);
        }
    }

    private static boolean matchesSnapshot(ArchivePlan plan, FolderSnapshot snapshot) {
        return plan.fileCount() == snapshot.fileCount()
                && plan.directoryCount() == snapshot.directoryCount()
                && plan.totalSourceBytes() == snapshot.totalSize();
    }

    private boolean sameStoredPlan(UploadState state, FolderSnapshot snapshot, ArchivePlan plan) {
        return state.sourceFileCount() == snapshot.fileCount()
                && state.sourceDirectoryCount() == snapshot.directoryCount()
                && state.sourceTotalBytes() == snapshot.totalSize()
                && state.sourceLatestModification() == snapshot.latestModification()
                && state.sourceFingerprint() == snapshot.metadataFingerprint()
                && state.maxVolumeBytes() == config.telegramPartMaxBytes()
                && state.totalParts() == plan.totalParts();
    }

    private Path temporaryFolder(Path sourceFolder) throws IOException {
        Path folderName = sourceFolder.getFileName();
        if (folderName == null) {
            throw new IOException("Não foi possível determinar o nome da pasta de download");
        }
        Path result = config.tempFolder().resolve(folderName.toString()).toAbsolutePath().normalize();
        if (result.startsWith(sourceFolder.toAbsolutePath().normalize())) {
            throw new IOException("temp.folder não pode ficar dentro da pasta original");
        }
        if (Files.exists(config.tempFolder())) {
            Path sourceReal = sourceFolder.toRealPath();
            Path tempReal = config.tempFolder().toRealPath();
            if (tempReal.startsWith(sourceReal)) {
                throw new IOException("temp.folder aponta para dentro da pasta original");
            }
        }
        return result;
    }

    private void cleanupTemporaryArtifacts(Path sourceFolder, UploadState state) throws IOException {
        Path temporaryFolder = temporaryFolder(sourceFolder);
        for (VolumeInfo volume : state.volumes().values()) {
            tryDeleteGeneratedFile(temporaryFolder.resolve(volume.name()));
        }
        tryDeleteGeneratedFile(temporaryFolder.resolve("manifest.json"));
        try {
            Files.deleteIfExists(temporaryFolder);
        } catch (java.nio.file.DirectoryNotEmptyException exception) {
            System.err.println("[CLEANUP] Restaram arquivos não reconhecidos em " + temporaryFolder);
        }
    }

    private void logProgress(UploadState state, ArchivePlan plan) {
        System.out.println("[PROGRESS] parte " + state.uploadedParts() + "/" + plan.totalParts() + " - "
                + FileUtils.formatPercentage(state.uploadedParts(), plan.totalParts()));
        System.out.println("[DATA] " + FileUtils.formatBytes(state.uploadedBytes()) + " / "
                + FileUtils.formatBytes(state.sourceTotalBytes()) + " enviados");
    }

    private void markFailed(DownloadTask task, Exception exception) {
        System.err.println("[FAILED] " + task.folder().getFileName() + ": " + exception.getMessage());
        try {
            UploadState state = stateStore.loadOrCreate(task.folder());
            if (state.status() != UploadState.Status.COMPLETED) {
                state.setStatus(UploadState.Status.FAILED);
                state.setError(exception.getMessage() == null ? exception.getClass().getSimpleName()
                        : exception.getMessage());
                stateStore.save(state);
            }
        } catch (IOException stateException) {
            System.err.println("[STATE] Não foi possível salvar falha: " + stateException.getMessage());
        }
    }

    private static void moveGeneratedFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteGeneratedFile(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    private static void tryDeleteGeneratedFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            System.err.println("[CLEANUP] Não foi possível remover " + file + ": " + exception.getMessage());
        }
    }
}
