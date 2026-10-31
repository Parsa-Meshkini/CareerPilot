package com.careerpilot.integrations;

import com.careerpilot.users.User;

/** Strategy interface: interchangeable ways to decide what an email says about an application. */
public interface EmailClassificationStrategy {
    Classification classify(EmailMessage email, User user);
}
