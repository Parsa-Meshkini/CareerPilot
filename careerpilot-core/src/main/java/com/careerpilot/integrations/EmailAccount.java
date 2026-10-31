package com.careerpilot.integrations;

import com.careerpilot.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "one_account_per_address", columnNames = {"user_id", "provider", "address"}))
@Getter @Setter
public class EmailAccount {
    public static final String ACTIVE = "ACTIVE", REAUTH_REQUIRED = "REAUTH_REQUIRED", DISCONNECTED = "DISCONNECTED";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    /** GMAIL, EML (uploaded files) or MOCK (scripted inbox). */
    private String provider;
    private String address = "";
    private String status = ACTIVE;
    private Instant lastSyncAt;
    private Instant createdAt = Instant.now();
}
