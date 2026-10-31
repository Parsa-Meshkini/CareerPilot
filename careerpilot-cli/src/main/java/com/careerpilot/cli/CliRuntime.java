package com.careerpilot.cli;

import com.careerpilot.CareerPilotCore;
import com.careerpilot.common.DomainException;
import com.careerpilot.users.AuthService;
import com.careerpilot.users.User;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Boots the shared core once (headless) and keeps the signed-in session in ~/.careerpilot/session. */
public final class CliRuntime {
    private static ConfigurableApplicationContext context;

    private CliRuntime() {}

    public static synchronized <T> T bean(Class<T> type) {
        if (context == null) {
            // keep the terminal clean: log only errors unless the student asks for more with -Dlogging.level.com.careerpilot=INFO
            System.setProperty("logging.level.com.careerpilot", System.getProperty("logging.level.com.careerpilot", "ERROR"));
            context = new SpringApplicationBuilder(CareerPilotCore.class).web(WebApplicationType.NONE).logStartupInfo(false)
                    .properties("spring.main.banner-mode=off", "logging.level.root=ERROR").run();
        }
        return context.getBean(type);
    }

    public static synchronized void shutdown() {
        if (context != null) context.close();
        context = null;
    }

    static Path sessionFile() {
        String home = System.getenv("CAREERPILOT_HOME");
        if (home == null || home.isBlank()) home = System.getProperty("careerpilot.home");
        Path dir = home == null || home.isBlank() ? Path.of(System.getProperty("user.home"), ".careerpilot") : Path.of(home);
        return dir.resolve("session");
    }

    public static void saveSession(String token) {
        try {
            Path file = sessionFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, token);
            try {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the session file", e);
        }
    }

    public static void clearSession() {
        try {
            Files.deleteIfExists(sessionFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The signed-in user, or a 401-style error telling the student to log in. */
    public static User currentUser() {
        try {
            Path file = sessionFile();
            if (!Files.exists(file)) throw new DomainException("unauthenticated", 401, "You are not signed in. Run: careerpilot login");
            return bean(AuthService.class).authenticate(Files.readString(file).strip());
        } catch (IOException e) {
            throw new DomainException("unauthenticated", 401, "You are not signed in. Run: careerpilot login");
        }
    }
}
