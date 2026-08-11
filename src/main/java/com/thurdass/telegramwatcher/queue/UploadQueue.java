package com.thurdass.telegramwatcher.queue;

import com.thurdass.telegramwatcher.model.DownloadTask;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public final class UploadQueue implements AutoCloseable {
    private final BlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>();
    private final Set<Path> queuedFolders = ConcurrentHashMap.newKeySet();
    private final int workerCount;
    private volatile boolean closed;

    public UploadQueue(int workerCount) {
        this.workerCount = workerCount;
    }

    public boolean add(DownloadTask task) {
        if (closed) {
            return false;
        }
        Path folder = task.folder();
        if (!queuedFolders.add(folder)) {
            return false;
        }
        queue.offer(QueueItem.task(task));
        System.out.println("[QUEUE] Adicionado à fila de upload: " + folder.getFileName());
        return true;
    }

    public QueueItem take() throws InterruptedException {
        return queue.take();
    }

    public void completed(DownloadTask task) {
        queuedFolders.remove(task.folder());
    }

    public int size() {
        return queue.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int index = 0; index < workerCount; index++) {
            queue.offer(QueueItem.stopItem());
        }
    }

    public record QueueItem(DownloadTask task, boolean poison) {
        public static QueueItem task(DownloadTask task) {
            return new QueueItem(task, false);
        }

        public static QueueItem stopItem() {
            return new QueueItem(null, true);
        }
    }
}
