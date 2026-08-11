package com.thurdass.telegramwatcher.archive;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

public final class LargeFileSplitter {
    private LargeFileSplitter() {
    }

    public static long crc32(Path source, long offset, long length, int bufferSize) throws IOException {
        CRC32 checksum = new CRC32();
        stream(source, offset, length, bufferSize, (buffer, count) -> checksum.update(buffer, 0, count));
        return checksum.getValue();
    }

    public static long copyRange(Path source, long offset, long length, OutputStream output, int bufferSize)
            throws IOException {
        long[] copied = {0L};
        stream(source, offset, length, bufferSize, (buffer, count) -> {
            output.write(buffer, 0, count);
            copied[0] = Math.addExact(copied[0], count);
        });
        return copied[0];
    }

    private static void stream(Path source, long offset, long length, int bufferSize, ChunkConsumer consumer)
            throws IOException {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset e length devem ser não negativos");
        }
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            long sourceSize = channel.size();
            if (offset > sourceSize || length > sourceSize - offset) {
                throw new IOException("Arquivo foi reduzido durante o processamento: " + source);
            }
            channel.position(offset);
            byte[] bytes = new byte[bufferSize];
            long remaining = length;
            while (remaining > 0) {
                int requested = (int) Math.min(bytes.length, remaining);
                ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, requested);
                int read = channel.read(buffer);
                if (read < 0) {
                    throw new IOException("Fim inesperado ao ler " + source);
                }
                if (read == 0) {
                    continue;
                }
                consumer.accept(bytes, read);
                remaining -= read;
            }
        }
    }

    @FunctionalInterface
    private interface ChunkConsumer {
        void accept(byte[] buffer, int count) throws IOException;
    }
}
