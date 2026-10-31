package com.careerpilot.integrations;

import java.util.List;

/** The kinds of application events an email can announce. */
public final class EventTypes {
    private EventTypes() {}

    public static final String APPLICATION_RECEIVED = "APPLICATION_RECEIVED", INTERVIEW_INVITATION = "INTERVIEW_INVITATION",
            INTERVIEW_SCHEDULED = "INTERVIEW_SCHEDULED", INFO_REQUESTED = "INFO_REQUESTED", REJECTION = "REJECTION", OFFER = "OFFER",
            FOLLOW_UP = "FOLLOW_UP", NOT_RELEVANT = "NOT_RELEVANT";

    public static final List<String> ALL = List.of(APPLICATION_RECEIVED, INTERVIEW_INVITATION, INTERVIEW_SCHEDULED, INFO_REQUESTED,
            REJECTION, OFFER, FOLLOW_UP, NOT_RELEVANT);
}
