package com.careerpilot.documents;

import com.careerpilot.applications.Application;
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
public class ApplicationPackage {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne(optional = false)
    private Application application;
    private String folderName;
    private String storageKey;
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> manifest = new ArrayList<>();
    private boolean needsReview;
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> documentIds = new ArrayList<>();
    private Instant createdAt = Instant.now();
}
