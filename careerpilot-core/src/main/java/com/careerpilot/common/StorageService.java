package com.careerpilot.common;

import com.careerpilot.users.User;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Private object storage. Stage 1 planned MinIO/S3 with signed URLs; Stage 2 keeps objects under a local
 * root and hands them out only through authenticated service calls (see the Stage 2 report, deviations).
 */
@Component
public class StorageService {
    private final Path root;

    public StorageService(AppProperties props) {
        this.root = props.getStorageRoot().toAbsolutePath().normalize();
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("invalid storage key");
        return path;
    }

    public String newKey(User user, String folder, String filename) {
        String safe = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
        return user.getId() + "/" + folder + "/" + UUID.randomUUID().toString().replace("-", "") + "_" + safe;
    }

    public String put(String key, byte[] data) {
        try {
            Path path = resolve(key);
            Files.createDirectories(path.getParent());
            Files.write(path, data);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String key) { return Files.exists(resolve(key)); }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
