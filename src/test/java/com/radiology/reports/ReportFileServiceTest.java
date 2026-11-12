package com.radiology.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportFileServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesReportTemplateToChosenOutputFolderAndReopensItsContent() throws IOException {
        Path template = temporaryDirectory.resolve("template.docx");
        Path outputDirectory = temporaryDirectory.resolve("reports").resolve("chosen");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("Report content for persistence verification");
            try (var output = Files.newOutputStream(template)) {
                document.write(output);
            }
        }

        Path saved = ReportFileService.copyTemplate(
                template, outputDirectory, "Patient: Example", LocalDateTime.of(2026, 11, 11, 6, 30, 0));

        assertTrue(Files.isRegularFile(saved));
        assertTrue(saved.startsWith(outputDirectory));
        assertTrue(saved.getFileName().toString().startsWith("Patient_ Example_"));
        try (XWPFDocument reopened = new XWPFDocument(Files.newInputStream(saved))) {
            assertEquals("Report content for persistence verification",
                    reopened.getParagraphs().get(0).getText());
        }
    }

    @Test
    void avoidsOverwritingReportsCreatedInTheSameSecond() throws IOException {
        Path template = temporaryDirectory.resolve("template.docx");
        Files.writeString(template, "report template");
        Path outputDirectory = temporaryDirectory.resolve("reports");
        LocalDateTime timestamp = LocalDateTime.of(2026, 11, 11, 6, 30, 0);

        Path first = ReportFileService.copyTemplate(template, outputDirectory, "Report", timestamp);
        Path second = ReportFileService.copyTemplate(template, outputDirectory, "Report", timestamp);

        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
        assertNotEquals(first, second);
        assertEquals("report template", Files.readString(second));
    }

    @Test
    void reportsMissingSelectedTemplateInsteadOfCreatingAnEmptyReport() {
        Path missingTemplate = temporaryDirectory.resolve("missing.docx");

        assertThrows(IOException.class, () -> ReportFileService.copyTemplate(
                missingTemplate, temporaryDirectory.resolve("reports"), "Report", LocalDateTime.now()));
    }
}
