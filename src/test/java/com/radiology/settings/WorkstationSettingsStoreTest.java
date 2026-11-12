package com.radiology.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkstationSettingsStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsSettingsFromTheUserSettingsPath() throws IOException {
        Path settingsFile = temporaryDirectory.resolve("user").resolve("workstation.properties");
        WorkstationSettingsStore store = new WorkstationSettingsStore(
                settingsFile, temporaryDirectory.resolve("legacy.properties"));
        Properties expected = new Properties();
        expected.setProperty("output", "C:\\Reports");

        store.save(expected);

        assertTrue(Files.isRegularFile(settingsFile));
        assertEquals("C:\\Reports", store.load().getProperty("output"));
        assertFalse(store.isUsingLegacyFile());
    }

    @Test
    void loadsLegacySettingsAndIdentifiesThemForMigration() throws IOException {
        Path legacyFile = temporaryDirectory.resolve("workstation.properties");
        Files.writeString(legacyFile, "output=C:\\\\OldReports\n");
        WorkstationSettingsStore store = new WorkstationSettingsStore(
                temporaryDirectory.resolve("user").resolve("workstation.properties"), legacyFile);

        assertTrue(store.isUsingLegacyFile());
        assertEquals("C:\\OldReports", store.load().getProperty("output"));
    }

    @Test
    void returnsEmptySettingsWhenNoFileExists() throws IOException {
        WorkstationSettingsStore store = new WorkstationSettingsStore(
                temporaryDirectory.resolve("user").resolve("workstation.properties"),
                temporaryDirectory.resolve("missing.properties"));

        assertTrue(store.load().isEmpty());
        assertFalse(store.isUsingLegacyFile());
    }
}
