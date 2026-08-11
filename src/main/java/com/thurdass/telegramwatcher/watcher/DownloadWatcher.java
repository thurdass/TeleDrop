package com.thurdass.telegramwatcher.watcher;

import com.thurdass.telegramwatcher.model.DownloadTask;
import com.thurdass.telegramwatcher.util.FileUtils;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class DownloadWatcher implements AutoCloseable {
    private final Path watchFolder;
    private final Path ignoredFolder;
    private final java.time.Duration stableDuration;
    private final java.time.Duration scanInterval;
    private final Consumer<DownloadTask> onCompleted;
    private final FolderCompletionChecker completionChecker;
    private final ExecutorService folderMonitors;
    private final Map<Path, Future<?>> monitoredFolders = new ConcurrentHashMap<>();
    private final WatchService watchService;
    private volatile boolean running = true;
    private Thread watchThread;

    public DownloadWatcher(
            Path watchFolder,
            Path ignoredFolder,
            java.time.Duration stableDuration,
            java.time.Duration scanInterval,
            Consumer<DownloadTask> onCompleted) throws IOException {
        this.watchFolder = watchFolder.toAbsolutePath().normalize();
        this.ignoredFolder = ignoredFolder.toAbsolutePath().normalize();
        this.stableDuration = stableDuration;
        this.scanInterval = scanInterval;
        this.onCompleted = onCompleted;
        this.completionChecker = new FolderCompletionChecker(this.ignoredFolder);
        int monitorThreads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.folderMonitors = Executors.newFixedThreadPool(monitorThreads, runnable -> {
            Thread thread = new Thread(runnable, "folder-monitor");
            thread.setDaemon(true);
            return thread;
        });
        this.watchService = FileSystems.getDefault().newWatchService();
    }

    public void start() throws IOException {
        watchFolder.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        System.out.println("[WATCHER] Monitorando " + watchFolder);
        scanExistingFolders();
        watchThread = new Thread(this::watchLoop, "download-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void scanExistingFolders() throws IOException {
        try (var entries = Files.list(watchFolder)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> !isIgnored(path))
                    .forEach(this::monitorFolderSafely);
        }
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException exception) {
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    System.err.println("[WATCHER] OVERFLOW; estados existentes serão reavaliados no próximo reinício");
                    continue;
                }
                if (event.kind() != StandardWatchEventKinds.ENTRY_CREATE) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path created = watchFolder.resolve(pathEvent.context()).normalize();
                if (isIgnored(created)) {
                    continue;
                }
                if (Files.isDirectory(created)) {
                    System.out.println("[NEW] " + created.getFileName() + " detectado");
                    monitorFolderSafely(created);
                }
            }

            if (!key.reset()) {
                return;
            }
        }
    }

    private void monitorFolderSafely(Path folder) {
        try {
            monitorFolder(folder);
        } catch (RuntimeException exception) {
            System.err.println("[WATCHER] Não foi possível monitorar " + folder + ": " + exception.getMessage());
        }
    }

    private void monitorFolder(Path folder) {
        Path normalized = folder.toAbsolutePath().normalize();
        if (!running || isIgnored(normalized) || !Files.isDirectory(normalized)) {
            return;
        }
        monitoredFolders.computeIfAbsent(normalized, path -> folderMonitors.submit(() -> {
            try {
                completionChecker.waitUntilStable(
                        path,
                        stableDuration,
                        scanInterval,
                        () -> running,
                        snapshot -> logScan(path, snapshot),
                        snapshot -> {
                            System.out.println("[COMPLETE] Download concluído: " + path.getFileName());
                            System.out.println("[SIZE] " + FileUtils.formatBytes(snapshot.totalSize()));
                            onCompleted.accept(new DownloadTask(path));
                        });
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                monitoredFolders.remove(path);
            }
        }));
    }

    private void logScan(Path path, FolderSnapshot snapshot) {
        System.out.println("[SCAN] " + path.getFileName() + " - " + FileUtils.formatBytes(snapshot.totalSize())
                + " - " + snapshot.fileCount() + " arquivos - " + snapshot.directoryCount() + " diretórios");
    }

    private boolean isIgnored(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return (normalized.getFileName() != null
                && normalized.getFileName().toString().equals(".telegram-upload"))
                || normalized.startsWith(ignoredFolder);
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        folderMonitors.shutdownNow();
        try {
            watchService.close();
        } catch (IOException exception) {
            System.err.println("[WATCHER] Falha ao fechar WatchService: " + exception.getMessage());
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }
}
