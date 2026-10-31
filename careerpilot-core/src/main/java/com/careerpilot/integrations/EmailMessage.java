package com.careerpilot.integrations;

import com.careerpilot.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** A short excerpt of one email. Mail that is not job related keeps only its id, for de-duplication. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "one_row_per_message", columnNames = {"account_id", "providerMessageId"}))
@Getter @Setter
public class EmailMessage {
    public static final int EXCERPT_LIMIT = 4096;

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne(optional = false)
    private EmailAccount account;
    @Column(length = 300)
    private String providerMessageId;
    @Column(length = 300) private String threadId = "";
    @Column(length = 320) private String sender = "";
    @Column(length = 500) private String subject = "";
    private Instant receivedAt;
    @Column(columnDefinition = "CLOB") private String excerpt = "";
    private boolean relevant = true;
    private Instant createdAt = Instant.now();

    public String senderDomain() {
        if (!sender.contains("@")) return "";
        String domain = sender.substring(sender.lastIndexOf('@') + 1);
        return domain.replaceAll("^[> ]+|[> ]+$", "").toLowerCase();
    }

    public void discardContent() {
        excerpt = "";
        relevant = false;
    }
}
