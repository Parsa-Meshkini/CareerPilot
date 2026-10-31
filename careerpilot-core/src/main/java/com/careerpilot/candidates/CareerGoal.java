package com.careerpilot.candidates;

import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Getter @Setter
public class CareerGoal {
    public enum WorkModel { ONSITE, HYBRID, REMOTE, ANY }

    @Id
    private UUID id = UUID.randomUUID();
    @OneToOne(optional = false)
    private User user;
    @Enumerated(EnumType.STRING)
    private GoalType goalType = GoalType.COOP;
    @Convert(converter = JsonConverters.StringListConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<String> targetRoles = new ArrayList<>();
    @Convert(converter = JsonConverters.StringListConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<String> preferredLocations = new ArrayList<>();
    private String workTerm = "";
    @Enumerated(EnumType.STRING)
    private WorkModel workModel = WorkModel.ANY;
    private int minMatchScore = 60;
    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(columnDefinition = "CLOB")
    private Map<String, Object> weightsData = new LinkedHashMap<>();
    private Instant updatedAt = Instant.now();

    public String strategyKey() { return goalType.strategyKey; }

    public StrategyWeights weights() {
        return StrategyWeights.fromMap(weightsData, GoalType.defaultsFor(strategyKey()));
    }

    public void setWeights(StrategyWeights weights) { this.weightsData = weights.toMap(); }
}
