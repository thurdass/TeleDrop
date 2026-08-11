package com.thurdass.telegramwatcher.archive;

import com.thurdass.telegramwatcher.model.VolumeInfo;
import com.thurdass.telegramwatcher.util.ChecksumUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class VolumeBuilder {
    private final boolean checksumEnabled;
    private final int bufferSize;
    private final long maximumVolumeBytes;

    public VolumeBuilder(boolean checksumEnabled, int bufferSize, long maximumVolumeBytes) {
        this.checksumEnabled = checksumEnabled;
        this.bufferSize = bufferSize;
        this.maximumVolumeBytes = maximumVolumeBytes;
    }

    public VolumeInfo build(VolumePlan plan, Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        MessageDigest digest = checksumEnabled ? ChecksumUtils.newSha256() : null;
        CountingOutputStream counting = null;
        try (OutputStream fileOutput = Files.newOutputStream(output);
             CountingOutputStream counted = new CountingOutputStream(fileOutput);
             OutputStream digestOutput = digest == null ? counted : new java.security.DigestOutputStream(counted, digest);
             BufferedOutputStream buffered = new BufferedOutputStream(digestOutput, bufferSize);
             ZipOutputStream zip = new ZipOutputStream(buffered)) {
            counting = counted;
            for (ArchiveEntryPlan entry : plan.entries()) {
                writeEntry(zip, entry);
            }
        } catch (RuntimeException | IOException exception) {
            throw exception;
        }

        long size = Files.size(output);
        if (size > maximumVolumeBytes) {
            throw new IOException("Volume excedeu o limite configurado: " + size + " > " + maximumVolumeBytes);
        }
        String sha256 = checksumEnabled ? ChecksumUtils.hex(digest.digest()) : "";
        return new VolumeInfo(plan.number(), plan.name(), size, sha256, false);
    }

    private void writeEntry(ZipOutputStream zip, ArchiveEntryPlan entry) throws IOException {
        ZipEntry zipEntry = new ZipEntry(entry.archiveName());
        zipEntry.setTime(0L);
        zipEntry.setMethod(ZipEntry.STORED);
        zipEntry.setSize(entry.directory() ? 0L : entry.sizeBytes());
        zipEntry.setCompressedSize(entry.directory() ? 0L : entry.sizeBytes());
        zipEntry.setCrc(entry.directory() ? 0L : LargeFileSplitter.crc32(
                entry.sourcePath(), entry.sourceOffset(), entry.sizeBytes(), bufferSize));
        zip.putNextEntry(zipEntry);
        if (!entry.directory()) {
            BasicFileAttributes before = Files.readAttributes(entry.sourcePath(), BasicFileAttributes.class);
            if (!before.isRegularFile() || before.size() < entry.sourceOffset() + entry.sizeBytes()) {
                throw new IOException("Arquivo de origem mudou antes da leitura: " + entry.sourcePath());
            }
            long copied = LargeFileSplitter.copyRange(
                    entry.sourcePath(), entry.sourceOffset(), entry.sizeBytes(), zip, bufferSize);
            if (copied != entry.sizeBytes()) {
                throw new IOException("Quantidade inesperada copiada de " + entry.sourcePath());
            }
            BasicFileAttributes after = Files.readAttributes(entry.sourcePath(), BasicFileAttributes.class);
            if (after.size() != before.size()
                    || after.lastModifiedTime().toMillis() != before.lastModifiedTime().toMillis()) {
                throw new IOException("Arquivo de origem mudou durante a leitura: " + entry.sourcePath());
            }
        }
        zip.closeEntry();
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        private CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            count = Math.addExact(count, 1L);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            count = Math.addExact(count, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
