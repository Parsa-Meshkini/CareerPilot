package com.careerpilot.users;

import com.careerpilot.common.DomainException;
import com.careerpilot.common.ValidationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthService {
    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository users, AuthTokenRepository tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    public User register(String email, String password, String displayName) {
        String normalised = email == null ? "" : email.trim().toLowerCase();
        if (!normalised.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new ValidationException("Enter a valid email address.");
        if (password == null || password.length() < 8) throw new ValidationException("Password must be at least 8 characters.");
        if (users.findByEmailIgnoreCase(normalised).isPresent())
            throw new ValidationException("An account with this email already exists.");
        User user = new User();
        user.setEmail(normalised);
        user.setDisplayName(displayName == null ? "" : displayName.trim());
        user.setPasswordHash(encoder.encode(password));
        return users.save(user);
    }

    public User login(String email, String password) {
        User user = users.findByEmailIgnoreCase(email == null ? "" : email.trim()).orElse(null);
        if (user == null || password == null || !encoder.matches(password, user.getPasswordHash()))
            throw new DomainException("invalid_credentials", 401, "Wrong email or password.");
        return user;
    }

    /** Issues a bearer token for the CLI session file or the browser extension. */
    public String issueToken(User user) {
        AuthToken token = new AuthToken();
        token.setUser(user);
        return tokens.save(token).getToken();
    }

    @Transactional(readOnly = true)
    public User authenticate(String token) {
        return tokens.findById(token == null ? "" : token).map(AuthToken::getUser)
                .orElseThrow(() -> new DomainException("unauthenticated", 401, "Sign in again."));
    }

    public void changePassword(User user, String oldPassword, String newPassword) {
        if (!encoder.matches(oldPassword, user.getPasswordHash())) throw new ValidationException("Current password is wrong.");
        if (newPassword == null || newPassword.length() < 8) throw new ValidationException("Password must be at least 8 characters.");
        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);
    }
}
