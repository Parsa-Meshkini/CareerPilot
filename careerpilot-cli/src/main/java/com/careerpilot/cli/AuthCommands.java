package com.careerpilot.cli;

import com.careerpilot.users.AuthService;
import com.careerpilot.users.User;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.Console;
import java.util.concurrent.Callable;

/** Account commands: register, login, logout, whoami, change-password. */
public final class AuthCommands {
    private AuthCommands() {}

    static String password(String given) {
        if (given != null) return given;
        String env = System.getenv("CAREERPILOT_PASSWORD");
        if (env != null) return env;
        Console console = System.console();
        if (console == null) throw new IllegalArgumentException("pass --password or set CAREERPILOT_PASSWORD (no terminal to prompt on)");
        return new String(console.readPassword("Password: "));
    }

    @Command(name = "register", description = "Create an account and sign in.")
    public static class Register implements Callable<Integer> {
        @Option(names = "--email", required = true) String email;
        @Option(names = "--name", defaultValue = "") String name;
        @Option(names = "--password") String password;

        @Override public Integer call() {
            AuthService auth = CliRuntime.bean(AuthService.class);
            User user = auth.register(email, password(password), name);
            CliRuntime.saveSession(auth.issueToken(user));
            Out.line("Account created. You are signed in as %s.", user.getEmail());
            return 0;
        }
    }

    @Command(name = "login", description = "Sign in and remember the session on this computer.")
    public static class Login implements Callable<Integer> {
        @Option(names = "--email", required = true) String email;
        @Option(names = "--password") String password;

        @Override public Integer call() {
            AuthService auth = CliRuntime.bean(AuthService.class);
            User user = auth.login(email, password(password));
            CliRuntime.saveSession(auth.issueToken(user));
            Out.line("Signed in as %s.", user.getEmail());
            return 0;
        }
    }

    @Command(name = "logout", description = "Forget the saved session.")
    public static class Logout implements Callable<Integer> {
        @Override public Integer call() {
            CliRuntime.clearSession();
            Out.line("Signed out.");
            return 0;
        }
    }

    @Command(name = "whoami", description = "Show the signed-in account.")
    public static class Whoami implements Callable<Integer> {
        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            Out.line("%s%s", u.getEmail(), u.getDisplayName().isEmpty() ? "" : " (" + u.getDisplayName() + ")");
            return 0;
        }
    }

    @Command(name = "change-password", description = "Change the password of the signed-in account.")
    public static class ChangePassword implements Callable<Integer> {
        @Option(names = "--old", required = true) String oldPassword;
        @Option(names = "--new", required = true) String newPassword;

        @Override public Integer call() {
            CliRuntime.bean(AuthService.class).changePassword(CliRuntime.currentUser(), oldPassword, newPassword);
            Out.line("Password changed.");
            return 0;
        }
    }
}
