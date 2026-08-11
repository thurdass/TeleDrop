package com.thurdass.telegramwatcher.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class VolumePlanner {
    private static final long LOCAL_HEADER_BYTES = 30L;
    private static final long CENTRAL_DIRECTORY_HEADER_BYTES = 46L;
    private static final long ENTRY_SAFETY_BYTES = 128L;
    private static final long FOOTER_RESERVE_BYTES = 64L * 1024L;

    private final long maximumVolumeBytes;
    private final Path ignoredFolder;

    public VolumePlanner(long maximumVolumeBytes, Path ignoredFolder) {
        if (maximumVolumeBytes <= FOOTER_RESERVE_BYTES) {
            throw new IllegalArgumentException("O limite do volume é pequeno demais para um ZIP seguro");
        }
        this.maximumVolumeBytes = maximumVolumeBytes;
        this.ignoredFolder = ignoredFolder == null ? null : ignoredFolder.toAbsolutePath().normalize();
    }

    public ArchivePlan plan(Path sourceFolder) throws IOException {
        Path root = sourceFolder.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Pasta de origem não existe: " + root);
        }

        PlanningContext context = new PlanningContext(root);
        try {
            context.visitDirectory(root);
        } catch (ArithmeticException exception) {
            throw new IOException("Tamanho total da pasta excede o limite de long", exception);
        }

        int width = Math.max(3, Integer.toString(context.volumes.size()).length());
        List<VolumePlan> volumes = new ArrayList<>();
        String folderName = root.getFileName() == null ? "download" : root.getFileName().toString();
        for (MutableVolume volume : context.volumes) {
            String name = folderName + ".part" + String.format(Locale.ROOT, "%0" + width + "d", volume.number)
                    + ".zip";
            volumes.add(new VolumePlan(volume.number, name, volume.entries, volume.estimatedBytes));
        }
        return new ArchivePlan(root, context.fileCount, context.directoryCount, context.totalSourceBytes, volumes);
    }

    public static long estimatedEntryBytes(String archiveName, long dataBytes) {
        long nameBytes = archiveName.getBytes(StandardCharsets.UTF_8).length;
        try {
            return Math.addExact(dataBytes, Math.addExact(
                    Math.addExact(LOCAL_HEADER_BYTES, CENTRAL_DIRECTORY_HEADER_BYTES),
                    Math.addExact(Math.multiplyExact(nameBytes, 2L), ENTRY_SAFETY_BYTES)));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Entrada ZIP grande demais: " + archiveName, exception);
        }
    }

    private boolean shouldIgnore(Path path) {
        return ignoredFolder != null
                && path.toAbsolutePath().normalize().startsWith(ignoredFolder);
    }

    private static String archivePath(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private final class PlanningContext {
        private final Path root;
        private final List<MutableVolume> volumes = new ArrayList<>();
        private final Set<String> archiveNames = new HashSet<>();
        private MutableVolume current;
        private long fileCount;
        private long directoryCount;
        private long totalSourceBytes;

        private PlanningContext(Path root) {
            this.root = root;
        }

        private void visitDirectory(Path directory) throws IOException {
            if (shouldIgnore(directory)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new IOException("Entrada não é um diretório: " + directory);
            }
            directoryCount++;
            if (!directory.equals(root)) {
                String relative = archivePath(root, directory);
                addWholeEntry(new ArchiveEntryPlan(
                        relative + "/", relative, directory, 0, 0, true, false, 0));
            }

            try (Stream<Path> children = Files.list(directory)) {
                children.sorted().forEach(child -> {
                    try {
                        BasicFileAttributes childAttributes = Files.readAttributes(child,
                                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (childAttributes.isDirectory()) {
                            visitDirectory(child);
                        } else if (childAttributes.isRegularFile()) {
                            fileCount++;
                            totalSourceBytes = Math.addExact(totalSourceBytes, childAttributes.size());
                            addFile(child, childAttributes.size());
                        } else {
                            System.err.println("[VOLUME] Ignorando link ou entrada não regular: " + child);
                        }
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
        }

        private void addFile(Path source, long size) throws IOException {
            String original = archivePath(root, source);
            long wholeEntryCapacity = payloadCapacity(original, 0);
            if (size <= wholeEntryCapacity) {
                addWholeEntry(new ArchiveEntryPlan(original, original, source, 0, size, false, false, 0));
                return;
            }

            long offset = 0;
            int partNumber = 1;
            while (offset < size) {
                String partName = original + ".part" + splitNumber(partNumber);
                long capacity = payloadCapacity(partName, currentBytes());
                if (capacity <= 0) {
                    startNewVolume();
                    capacity = payloadCapacity(partName, currentBytes());
                }
                long partSize = Math.min(size - offset, capacity);
                if (partSize <= 0) {
                    throw new IOException("Não foi possível dividir o arquivo dentro do limite: " + source);
                }
                addEntry(new ArchiveEntryPlan(partName, original, source, offset, partSize,
                        false, true, partNumber));
                offset = Math.addExact(offset, partSize);
                partNumber++;
            }
        }

        private void addWholeEntry(ArchiveEntryPlan entry) throws IOException {
            long estimated = estimatedEntryBytes(entry.archiveName(), entry.sizeBytes());
            if (estimated + FOOTER_RESERVE_BYTES > maximumVolumeBytes) {
                throw new IOException("Uma entrada não cabe em um volume: " + entry.archiveName());
            }
            if (current == null || current.estimatedBytes + estimated + FOOTER_RESERVE_BYTES > maximumVolumeBytes) {
                startNewVolume();
            }
            addEntry(entry);
        }

        private void addEntry(ArchiveEntryPlan entry) throws IOException {
            if (!archiveNames.add(entry.archiveName())) {
                throw new IOException("Nome duplicado dentro do ZIP: " + entry.archiveName());
            }
            if (current == null) {
                startNewVolume();
            }
            long estimated = estimatedEntryBytes(entry.archiveName(), entry.sizeBytes());
            if (current.estimatedBytes + estimated + FOOTER_RESERVE_BYTES > maximumVolumeBytes) {
                startNewVolume();
                if (estimated + FOOTER_RESERVE_BYTES > maximumVolumeBytes) {
                    throw new IOException("Entrada não cabe nem em um volume vazio: " + entry.archiveName());
                }
            }
            current.entries.add(entry);
            current.estimatedBytes = Math.addExact(current.estimatedBytes, estimated);
        }

        private long payloadCapacity(String archiveName, long alreadyEstimated) {
            long overhead = estimatedEntryBytes(archiveName, 0);
            long available = maximumVolumeBytes - FOOTER_RESERVE_BYTES - alreadyEstimated - overhead;
            return Math.max(0, available);
        }

        private long currentBytes() {
            return current == null ? 0 : current.estimatedBytes;
        }

        private void startNewVolume() {
            current = new MutableVolume(volumes.size() + 1);
            volumes.add(current);
        }

        private String splitNumber(int partNumber) {
            return String.format(Locale.ROOT, "%03d", partNumber);
        }
    }

    private static final class MutableVolume {
        private final int number;
        private final List<ArchiveEntryPlan> entries = new ArrayList<>();
        private long estimatedBytes;

        private MutableVolume(int number) {
            this.number = number;
        }
    }
}
