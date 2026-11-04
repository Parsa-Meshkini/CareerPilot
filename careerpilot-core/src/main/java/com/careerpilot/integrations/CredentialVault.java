package com.careerpilot.integrations;

import com.careerpilot.common.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * Keeps provider refresh tokens encrypted at rest (AES-256-GCM). The key is generated once into the storage root with
 * owner-only permissions, so a copy of the database or of the token files alone reveals nothing.
 */
@Component
public class CredentialVault {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path dir;

    public CredentialVault(AppProperties props) { this.dir = props.getStorageRoot().resolve("vault"); }

    public void put(String name, String secret) {
        try {
            Files.createDirectories(dir);
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve(safe(name) + ".enc"), ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (Exception e) {
            throw new IllegalStateException("cannot store the credential", e);
        }
    }

    public Optional<String> get(String name) {
        Path file = dir.resolve(safe(name) + ".enc");
        if (!Files.exists(file)) return Optional.empty();
        try {
            byte[] all = Files.readAllBytes(file);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return Optional.of(new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Optional.empty();  // unreadable or tampered: the caller treats it as "reconnect"
        }
    }

    public void delete(String name) {
        try {
            Files.deleteIfExists(dir.resolve(safe(name) + ".enc"));
        } catch (IOException ignored) {
            // nothing to clean up
        }
    }

    private SecretKeySpec key() throws IOException {
        Path file = dir.resolve("vault.key");
        if (!Files.exists(file)) {
            byte[] fresh = new byte[32];
            RANDOM.nextBytes(fresh);
            Files.write(file, fresh);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException notPosix) {
                // best effort on Windows
            }
        }
        return new SecretKeySpec(Files.readAllBytes(file), "AES");
    }

    private static String safe(String name) { return name.replaceAll("[^A-Za-z0-9_.-]", "_"); }
}
