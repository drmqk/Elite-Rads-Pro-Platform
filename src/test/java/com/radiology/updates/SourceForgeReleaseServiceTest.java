package com.radiology.updates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class SourceForgeReleaseServiceTest {
    private static final String VALID_RELEASE = """
            {
              "platform_releases": {
                "windows": {
                  "filename": "/ERMPv8.4.zip",
                  "sha256sum": "41f2fc2c5603000fb89bfa7d533583d4db51b246a114c57b71b85abc43684970",
                  "date_modified": "2026-11-11 04:20:41"
                }
              }
            }
            """;

    @Test
    void parsesWindowsPackageAndBuildsHttpsDownloadUri() throws IOException {
        SourceForgeReleaseService.WindowsRelease release =
                SourceForgeReleaseService.parseWindowsRelease(VALID_RELEASE);

        assertEquals("ERMPv8.4.zip", release.filename());
        assertEquals("2026-11-11 04:20:41", release.formattedModifiedAt());
        assertEquals(64, release.sha256().length());
        assertTrue(release.downloadUri().toString().startsWith("https://sourceforge.net/"));
    }

    @Test
    void rejectsReleasePathsThatCouldEscapeTheProjectFilesArea() {
        String unsafeRelease = VALID_RELEASE.replace("/ERMPv8.4.zip", "/../other-project/app.zip");

        assertThrows(IOException.class,
                () -> SourceForgeReleaseService.parseWindowsRelease(unsafeRelease));
    }

    @Test
    void rejectsReleaseWithoutValidChecksum() {
        String invalidChecksum = VALID_RELEASE.replace(
                "41f2fc2c5603000fb89bfa7d533583d4db51b246a114c57b71b85abc43684970", "unchecked");

        assertThrows(IOException.class,
                () -> SourceForgeReleaseService.parseWindowsRelease(invalidChecksum));
    }
}
