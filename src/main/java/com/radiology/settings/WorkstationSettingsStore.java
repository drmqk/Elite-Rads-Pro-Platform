package com.radiology.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class WorkstationSettingsStore {
    private final Path settingsFile;
    private final Path legacySettingsFile;

    public WorkstationSettingsStore(Path settingsFile, Path legacySettingsFile) {
        this.settingsFile = settingsFile.toAbsolutePath();
        this.legacySettingsFile = legacySettingsFile.toAbsolutePath();
    }

    public static WorkstationSettingsStore forCurrentUser(String fileName) {
        Path settingsFile = Path.of(System.getProperty("user.home"), ".eliterads", fileName);
        Path legacySettingsFile = Path.of(fileName);
        return new WorkstationSettingsStore(settingsFile, legacySettingsFile);
    }

    public Properties load() throws IOException {
        Properties properties = new Properties();
        Path source = Files.isRegularFile(settingsFile) ? settingsFile : legacySettingsFile;
        if (Files.isRegularFile(source)) {
            try (InputStream input = Files.newInputStream(source)) {
                properties.load(input);
            }
        }
        return properties;
    }

    public Path settingsFile() {
        return settingsFile;
    }

    public boolean isUsingLegacyFile() {
        return !Files.isRegularFile(settingsFile) && Files.isRegularFile(legacySettingsFile);
    }

    public void save(Properties properties) throws IOException {
        Path parent = settingsFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, settingsFile.getFileName().toString(), ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                properties.store(output, null);
            }
            try {
                Files.move(temporaryFile, settingsFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }
}
