package com.radiology.reports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

public final class ReportFileService {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ReportFileService() {
    }

    public static Path copyTemplate(Path template, Path outputDirectory, String reportName, LocalDateTime timestamp)
            throws IOException {
        Objects.requireNonNull(template);
        Objects.requireNonNull(outputDirectory);
        Objects.requireNonNull(timestamp);
        if (!Files.isRegularFile(template)) {
            throw new IOException("The selected report template does not exist or is not a file: " + template);
        }

        Files.createDirectories(outputDirectory);
        String safeName = sanitize(reportName);
        if (safeName.isEmpty()) {
            safeName = "Report";
        }
        String extension = extensionOf(template.getFileName().toString());
        String baseName = safeName + "_" + timestamp.format(FILE_TIMESTAMP);
        Path destination = outputDirectory.resolve(baseName + extension);
        int suffix = 1;
        while (Files.exists(destination)) {
            destination = outputDirectory.resolve(baseName + "_" + suffix++ + extension);
        }
        return Files.copy(template, destination);
    }

    private static String sanitize(String reportName) {
        if (reportName == null) {
            return "";
        }
        return reportName.trim()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ")
                .replaceAll("[. ]+$", "");
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? ".docx" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
