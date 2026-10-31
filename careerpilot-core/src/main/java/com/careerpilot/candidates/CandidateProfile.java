package com.careerpilot.candidates;

import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Getter @Setter
public class CandidateProfile {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @OneToOne(optional = false)
    private User user;
    private String fullName = "";
    private String headline = "";
    @Column(length = 4000)
    private String summary = "";
    private String location = "";
    private String email = "";
    private String phone = "";
    @Convert(converter = JsonConverters.StringListConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<String> links = new ArrayList<>();
    private boolean autoTriage = true;
    private Instant updatedAt = Instant.now();
}
