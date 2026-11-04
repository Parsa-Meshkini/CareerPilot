package com.careerpilot.analytics;

import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A bounded, evidence-cited change to one scoring weight. Nothing changes until the student accepts it; accepted ones can be reverted. */
@Entity
@Getter @Setter
public class StrategyAdjustment {
    public enum Status { PROPOSED, ACCEPTED, REJECTED, REVERTED, SUPERSEDED }

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    private String factor;
    private double oldValue;
    private double newValue;
    @Column(length = 600) private String rationale = "";
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> evidence = new LinkedHashMap<>();
    @Enumerated(EnumType.STRING)
    private Status status = Status.PROPOSED;
    private Instant createdAt = Instant.now();
    private Instant decidedAt;
}
