package com.careerpilot.users;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** CD-2 User. Every user-owned entity carries a reference to this one. */
@Entity
@Table(name = "app_user")
@Getter @Setter
public class User {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(unique = true, nullable = false)
    private String email;
    private String displayName = "";
    @Column(nullable = false)
    private String passwordHash;
    private Instant createdAt = Instant.now();

    @Override public String toString() { return email; }
}
