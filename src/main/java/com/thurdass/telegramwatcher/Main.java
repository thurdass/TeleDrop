package com.thurdass.telegramwatcher;

import com.thurdass.telegramwatcher.config.AppConfig;
import com.thurdass.telegramwatcher.queue.UploadQueue;
import com.thurdass.telegramwatcher.queue.UploadWorker;
import com.thurdass.telegramwatcher.state.UploadState;
import com.thurdass.telegramwatcher.state.UploadStateStore;
import com.thurdass.telegramwatcher.telegram.TelegramClient;
import com.thurdass.telegramwatcher.watcher.DownloadWatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Path configPath = args.length == 0 ? Path.of("config.properties") : Path.of(args[0]);
        try {
            AppConfig config = AppConfig.load(configPath);
            run(config);
        } catch (Exception exception) {
            System.err.println("[FATAL] " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(AppConfig config) throws IOException, InterruptedException {
        Files.createDirectories(config.tempFolder());
        UploadStateStore stateStore = new UploadStateStore(config.stateFolder());
        for (UploadState state : stateStore.loadAll()) {
            if (state.status() != UploadState.Status.COMPLETED) {
                System.out.println("[RESUME] " + state.folder().getFileName() + " - parte "
                        + state.nextPart() + "/" + state.totalParts() + " - " + state.status());
            }
        }

        UploadQueue uploadQueue = new UploadQueue(config.telegramConcurrentUploads());
        TelegramClient telegramClient = new TelegramClient(config);
        UploadWorker uploadWorker = new UploadWorker(uploadQueue, stateStore, config, telegramClient);
        ExecutorService uploadExecutor = Executors.newFixedThreadPool(config.telegramConcurrentUploads(), runnable -> {
            Thread thread = new Thread(runnable, "telegram-upload-worker");
            thread.setDaemon(false);
            return thread;
        });
        for (int index = 0; index < config.telegramConcurrentUploads(); index++) {
            uploadExecutor.submit(uploadWorker);
        }

        DownloadWatcher watcher = new DownloadWatcher(
                config.watchFolder(),
                config.tempFolder(),
                config.folderStableDuration(),
                config.folderScanInterval(),
                uploadQueue::add);
        AtomicBoolean shuttingDown = new AtomicBoolean();
        CountDownLatch shutdownSignal = new CountDownLatch(1);
        Thread shutdownHook = new Thread(() -> shutdown(
                shuttingDown, shutdownSignal, watcher, uploadQueue, uploadExecutor, config), "shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            watcher.start();
            System.out.println("[READY] TeleDrop em execução; Ctrl+C para encerrar");
            shutdownSignal.await();
        } finally {
            shutdown(shuttingDown, shutdownSignal, watcher, uploadQueue, uploadExecutor, config);
        }
    }

    private static void shutdown(
            AtomicBoolean shuttingDown,
            CountDownLatch shutdownSignal,
            DownloadWatcher watcher,
            UploadQueue uploadQueue,
            ExecutorService uploadExecutor,
            AppConfig config) {
        if (!shuttingDown.compareAndSet(false, true)) {
            shutdownSignal.countDown();
            return;
        }
        System.out.println("[SHUTDOWN] Parando novos trabalhos e aguardando uploads ativos...");
        watcher.close();
        uploadQueue.close();
        uploadExecutor.shutdown();
        try {
            if (!uploadExecutor.awaitTermination(config.shutdownAwaitDuration().toSeconds(), TimeUnit.SECONDS)) {
                System.out.println("[SHUTDOWN] Tempo limite atingido; interrompendo workers. Arquivos .tmp serão retomados depois.");
                uploadExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            uploadExecutor.shutdownNow();
        } finally {
            shutdownSignal.countDown();
        }
        System.out.println("[SHUTDOWN] Encerramento concluído; a pasta original não foi alterada.");
    }
}
