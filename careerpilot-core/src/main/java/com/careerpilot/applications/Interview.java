package com.careerpilot.applications;

import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter @Setter
public class Interview {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne(optional = false)
    private Application application;
    @Column(name = "round_no")
    private int round = 1;
    private Instant scheduledAt;
    @Column(length = 60) private String format = "";
    @Column(columnDefinition = "CLOB") private String detailsText = "";
    @Column(unique = true)
    private UUID sourceEventId;
    private Instant createdAt = Instant.now();
}
