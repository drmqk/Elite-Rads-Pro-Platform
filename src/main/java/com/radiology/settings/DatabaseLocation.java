package com.radiology.settings;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class DatabaseLocation {
    private final Path applicationDataDirectory;
    private final Path legacyDatabase;

    public DatabaseLocation(Path applicationDataDirectory, Path legacyDatabase) {
        this.applicationDataDirectory = applicationDataDirectory.toAbsolutePath();
        this.legacyDatabase = legacyDatabase.toAbsolutePath();
    }

    public static DatabaseLocation forCurrentUser(String legacyFilename) {
        String appData = System.getenv("APPDATA");
        Path userData = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".eliterads")
                : Path.of(appData, "EliteradsPro");
        return new DatabaseLocation(userData, Path.of(legacyFilename));
    }

    public Path resolve() throws IOException {
        Files.createDirectories(applicationDataDirectory);
        Path destination = applicationDataDirectory.resolve(legacyDatabase.getFileName());
        if (!Files.exists(destination) && Files.isRegularFile(legacyDatabase)) {
            Path legacyWal = Path.of(legacyDatabase + "-wal");
            Path destinationWal = Path.of(destination + "-wal");
            Path stagedDatabase = Files.createTempFile(applicationDataDirectory, "database-migration-", ".db");
            Path stagedWal = null;
            try {
                Files.copy(legacyDatabase, stagedDatabase, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                if (Files.isRegularFile(legacyWal)) {
                    stagedWal = Files.createTempFile(applicationDataDirectory, "database-migration-", ".wal");
                    Files.copy(legacyWal, stagedWal, StandardCopyOption.REPLACE_EXISTING);
                    move(stagedWal, destinationWal);
                    stagedWal = null;
                } else {
                    Files.deleteIfExists(destinationWal);
                }
                move(stagedDatabase, destination);
            } finally {
                Files.deleteIfExists(stagedDatabase);
                if (stagedWal != null) {
                    Files.deleteIfExists(stagedWal);
                }
            }
        }
        return destination;
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path applicationDataDirectory() {
        return applicationDataDirectory;
    }
}
