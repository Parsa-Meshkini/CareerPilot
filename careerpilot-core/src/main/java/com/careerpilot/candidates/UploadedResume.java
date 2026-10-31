package com.careerpilot.candidates;

import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter @Setter
public class UploadedResume {
    public enum ParseStatus { PENDING, PARSED, FAILED, FAILED_RETRYABLE }

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    private String storageKey;
    private String originalName;
    private String mimeType;
    @Enumerated(EnumType.STRING)
    private ParseStatus parseStatus = ParseStatus.PENDING;
    @Column(length = 400)
    private String statusMessage = "";
    private int extractedCount;
    private Instant createdAt = Instant.now();

    public void markParsed(int count) {
        parseStatus = ParseStatus.PARSED;
        extractedCount = count;
        statusMessage = "";
    }

    public void markFailed(String message, boolean retryable) {
        parseStatus = retryable ? ParseStatus.FAILED_RETRYABLE : ParseStatus.FAILED;
        statusMessage = message.length() > 300 ? message.substring(0, 300) : message;
    }
}
