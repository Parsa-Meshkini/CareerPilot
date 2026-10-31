package com.careerpilot.users;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Opaque bearer token used by the CLI session file and the Chrome extension. */
@Entity
@Getter @Setter
public class AuthToken {
    @Id
    private String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    @ManyToOne(optional = false)
    private User user;
    private Instant createdAt = Instant.now();
}
