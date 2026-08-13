package com.thurdass.telegramwatcher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Prevents two TeleDrop processes from using the same state directory.
 */
public final class ApplicationLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private ApplicationLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static ApplicationLock acquire(Path lockPath) throws IOException {
        Path normalized = lockPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileChannel channel = FileChannel.open(normalized,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("Outra instância do TeleDrop já está em execução: " + normalized);
            }
            return new ApplicationLock(channel, lock);
        } catch (OverlappingFileLockException exception) {
            closeQuietly(channel);
            throw new IOException("Outra instância do TeleDrop já está em execução: " + normalized, exception);
        } catch (IOException | RuntimeException exception) {
            closeQuietly(channel);
            throw exception;
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException exception) {
            System.err.println("[LOCK] Não foi possível liberar o lock: " + exception.getMessage());
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException exception) {
            System.err.println("[LOCK] Não foi possível fechar o arquivo de lock: " + exception.getMessage());
        }
    }
}
