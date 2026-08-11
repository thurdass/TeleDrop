package com.thurdass.telegramwatcher.watcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class FolderCompletionChecker {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final Path ignoredFolder;

    public FolderCompletionChecker(Path ignoredFolder) {
        this.ignoredFolder = ignoredFolder == null ? null : ignoredFolder.toAbsolutePath().normalize();
    }

    public FolderSnapshot snapshot(Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new NoSuchFileException(root.toString());
        }

        SnapshotAccumulator accumulator = new SnapshotAccumulator(root);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (shouldIgnore(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                accumulator.directoryCount++;
                accumulator.addMetadata(directory, attributes, "D");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                accumulator.addMetadata(file, attributes, "F");
                if (attributes.isRegularFile()) {
                    accumulator.fileCount++;
                    accumulator.totalSize = Math.addExact(accumulator.totalSize, attributes.size());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw exception;
            }
        });
        return new FolderSnapshot(
                accumulator.fileCount,
                accumulator.directoryCount,
                accumulator.totalSize,
                accumulator.latestModification,
                accumulator.fingerprint);
    }

    public void waitUntilStable(
            Path folder,
            Duration stableDuration,
            Duration scanInterval,
            BooleanSupplier shouldContinue,
            Consumer<FolderSnapshot> onScan,
            Consumer<FolderSnapshot> onComplete) throws InterruptedException {
        FolderSnapshot previous = null;
        long stableSince = -1L;

        while (shouldContinue.getAsBoolean()) {
            FolderSnapshot current;
            try {
                current = snapshot(folder);
            } catch (java.nio.file.NoSuchFileException exception) {
                return;
            } catch (IOException exception) {
                System.err.println("[SCAN] Falha ao analisar " + folder + ": " + exception.getMessage());
                sleep(scanInterval, shouldContinue);
                continue;
            }

            onScan.accept(current);
            long now = System.nanoTime();
            if (previous == null || !current.equals(previous)) {
                stableSince = now;
                if (previous != null) {
                    System.out.println("[WAITING] " + folder.getFileName() + " ainda está recebendo alterações");
                }
            } else if (stableSince > 0) {
                long stableNanos = now - stableSince;
                long stableSeconds = Duration.ofNanos(stableNanos).toSeconds();
                System.out.println("[STABLE] " + folder.getFileName() + " - sem alterações por "
                        + stableSeconds + "s");
                if (current.hasFiles() && stableNanos >= stableDuration.toNanos()) {
                    onComplete.accept(current);
                    return;
                }
            }
            previous = current;
            sleep(scanInterval, shouldContinue);
        }
    }

    private void sleep(Duration duration, BooleanSupplier shouldContinue) throws InterruptedException {
        long remainingMillis = duration.toMillis();
        while (remainingMillis > 0 && shouldContinue.getAsBoolean()) {
            long sleepMillis = Math.min(remainingMillis, 1_000L);
            Thread.sleep(sleepMillis);
            remainingMillis -= sleepMillis;
        }
    }

    private boolean shouldIgnore(Path path) {
        if (ignoredFolder == null) {
            return false;
        }
        return path.toAbsolutePath().normalize().startsWith(ignoredFolder);
    }

    private static final class SnapshotAccumulator {
        private final Path root;
        private long fileCount;
        private long directoryCount;
        private long totalSize;
        private long latestModification;
        private long fingerprint = FNV_OFFSET_BASIS;

        private SnapshotAccumulator(Path root) {
            this.root = root;
        }

        private void addMetadata(Path path, BasicFileAttributes attributes, String type) {
            latestModification = Math.max(latestModification, attributes.lastModifiedTime().toMillis());
            String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
            fingerprint = mix(fingerprint, type);
            fingerprint = mix(fingerprint, relative);
            fingerprint = mix(fingerprint, Long.toString(attributes.size()));
            fingerprint = mix(fingerprint, Long.toString(attributes.lastModifiedTime().toMillis()));
        }

        private static long mix(long value, String text) {
            long result = value;
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            for (byte item : bytes) {
                result ^= item & 0xffL;
                result *= FNV_PRIME;
            }
            result ^= 0xffL;
            result *= FNV_PRIME;
            return result;
        }
    }
}
