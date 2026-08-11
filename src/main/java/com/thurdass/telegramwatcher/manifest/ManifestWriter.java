package com.thurdass.telegramwatcher.manifest;

import com.thurdass.telegramwatcher.archive.ArchiveEntryPlan;
import com.thurdass.telegramwatcher.archive.ArchivePlan;
import com.thurdass.telegramwatcher.archive.VolumePlan;
import com.thurdass.telegramwatcher.model.VolumeInfo;
import com.thurdass.telegramwatcher.state.UploadState;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ManifestWriter {
    private ManifestWriter() {
    }

    public static void write(Path output, ArchivePlan plan, UploadState state) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Map<String, List<SplitPart>> splitFiles = splitFiles(plan);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writer.write("{\n");
            field(writer, "version", "1", false, 1);
            field(writer, "archiveFormat", "\"ZIP STORED\"", false, 1);
            field(writer, "originalFolder", json(plan.sourceFolder().getFileName() == null
                    ? plan.sourceFolder().toString() : plan.sourceFolder().getFileName().toString()), false, 1);
            field(writer, "sourceSizeBytes", Long.toString(state.sourceTotalBytes()), false, 1);
            field(writer, "sourceFileCount", Long.toString(state.sourceFileCount()), false, 1);
            field(writer, "sourceDirectoryCount", Long.toString(state.sourceDirectoryCount()), false, 1);
            field(writer, "volumeCount", Integer.toString(plan.totalParts()), false, 1);
            writer.write("  \"volumes\": [\n");
            for (int index = 0; index < plan.volumes().size(); index++) {
                VolumePlan volume = plan.volumes().get(index);
                VolumeInfo info = state.volumes().get(volume.number());
                if (info == null) {
                    throw new IOException("Estado sem informações do volume " + volume.number());
                }
                writer.write("    {\"number\": ");
                writer.write(Integer.toString(volume.number()));
                writer.write(", \"name\": ");
                writer.write(json(info.name()));
                writer.write(", \"sizeBytes\": ");
                writer.write(Long.toString(info.sizeBytes()));
                writer.write(", \"sha256\": ");
                writer.write(json(info.sha256()));
                writer.write("}");
                writer.write(index + 1 == plan.volumes().size() ? "\n" : ",\n");
            }
            writer.write("  ],\n");
            writer.write("  \"splitFiles\": [\n");
            int fileIndex = 0;
            for (Map.Entry<String, List<SplitPart>> entry : splitFiles.entrySet()) {
                writer.write("    {\"originalPath\": ");
                writer.write(json(entry.getKey()));
                writer.write(", \"partCount\": ");
                writer.write(Integer.toString(entry.getValue().size()));
                writer.write(", \"parts\": [\n");
                for (int index = 0; index < entry.getValue().size(); index++) {
                    SplitPart part = entry.getValue().get(index);
                    writer.write("      {\"partNumber\": ");
                    writer.write(Integer.toString(part.partNumber()));
                    writer.write(", \"archivePath\": ");
                    writer.write(json(part.archivePath()));
                    writer.write(", \"volume\": ");
                    writer.write(Integer.toString(part.volumeNumber()));
                    writer.write(", \"offset\": ");
                    writer.write(Long.toString(part.offset()));
                    writer.write(", \"sizeBytes\": ");
                    writer.write(Long.toString(part.sizeBytes()));
                    writer.write("}");
                    writer.write(index + 1 == entry.getValue().size() ? "\n" : ",\n");
                }
                writer.write("    ]}");
                writer.write(++fileIndex == splitFiles.size() ? "\n" : ",\n");
            }
            writer.write("  ]\n");
            writer.write("}\n");
        }
    }

    private static Map<String, List<SplitPart>> splitFiles(ArchivePlan plan) {
        Map<String, List<SplitPart>> result = new LinkedHashMap<>();
        for (VolumePlan volume : plan.volumes()) {
            for (ArchiveEntryPlan entry : volume.entries()) {
                if (!entry.splitPart()) {
                    continue;
                }
                result.computeIfAbsent(entry.originalPath(), ignored -> new ArrayList<>())
                        .add(new SplitPart(entry.splitPartNumber(), entry.archiveName(), volume.number(),
                                entry.sourceOffset(), entry.sizeBytes()));
            }
        }
        return result;
    }

    private static void field(BufferedWriter writer, String name, String value, boolean last, int indent)
            throws IOException {
        writer.write("  \"");
        writer.write(name);
        writer.write("\": ");
        writer.write(value);
        writer.write(last ? "\n" : ",\n");
    }

    private static String json(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private record SplitPart(int partNumber, String archivePath, int volumeNumber, long offset, long sizeBytes) {
    }
}
