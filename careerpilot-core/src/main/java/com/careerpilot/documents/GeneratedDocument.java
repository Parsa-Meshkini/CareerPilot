package com.careerpilot.documents;

import com.careerpilot.applications.Application;
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
public class GeneratedDocument {
    public static final String RESUME = "RESUME", COVER_LETTER = "COVER_LETTER";
    public static final String PASSED = "PASSED", NEEDS_REVIEW = "NEEDS_REVIEW";

    @Id
    private UUID id = UUID.randomUUID();
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne(optional = false)
    private Application application;
    private String docType;
    private int version = 1;
    /** Structured content the files were rendered from. */
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> content = new LinkedHashMap<>();
    /** Format name to storage key. */
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> files = new LinkedHashMap<>();
    private String verificationStatus = PASSED;
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> verificationReport = new LinkedHashMap<>();
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> citedFactIds = new ArrayList<>();
    private boolean stale;
    private String modelUsed = "";
    private int promptVersion = 1;
    private Instant createdAt = Instant.now();
}
