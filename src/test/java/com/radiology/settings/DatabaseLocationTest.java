package com.radiology.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseLocationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesExistingDatabaseAndWalToUserDataDirectory() throws IOException {
        Path legacy = temporaryDirectory.resolve("radiology_records.db");
        Path userData = temporaryDirectory.resolve("user-data");
        Files.writeString(legacy, "legacy database");
        Files.writeString(Path.of(legacy + "-wal"), "pending database changes");
        Files.writeString(Path.of(legacy + "-shm"), "legacy shared memory");
        DatabaseLocation location = new DatabaseLocation(userData, legacy);

        Path resolved = location.resolve();

        assertEquals(userData.resolve("radiology_records.db"), resolved);
        assertEquals("legacy database", Files.readString(resolved));
        assertEquals("pending database changes", Files.readString(Path.of(resolved + "-wal")));
        assertFalse(Files.exists(Path.of(resolved + "-shm")));
    }

    @Test
    void preservesAnExistingDatabaseInTheUserDataDirectory() throws IOException {
        Path userData = Files.createDirectories(temporaryDirectory.resolve("user-data"));
        Path legacy = temporaryDirectory.resolve("radiology_records.db");
        Path existing = userData.resolve("radiology_records.db");
        Files.writeString(legacy, "older copy");
        Files.writeString(existing, "current user data");

        assertEquals(existing, new DatabaseLocation(userData, legacy).resolve());
        assertTrue(Files.readString(existing).equals("current user data"));
    }
}
