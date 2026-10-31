package com.careerpilot;

import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.MockLlmProvider;
import com.careerpilot.users.AuthService;
import com.careerpilot.users.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Base class: real Spring context on an in-memory H2 database, with the LLM replaced by MockLlmProvider. */
@SpringBootTest(classes = CareerPilotCore.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:cp-${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.config.import=",
        "careerpilot.llm.provider=mock",
        "spring.main.web-application-type=none",
        "careerpilot.storage-root=target/test-storage"
})
@Transactional
@Import(TestData.class)
public abstract class IntegrationTest {
    @Autowired protected LlmClient llm;
    @Autowired protected AuthService auth;
    @Autowired protected TestData data;
    protected MockLlmProvider mock;
    protected User user;

    @BeforeEach
    void installMockLlm() {
        mock = new MockLlmProvider();
        llm.useProvider(mock, millis -> { });
        user = auth.register("student-" + UUID.randomUUID() + "@yorku.ca", "password123", "Test Student");
    }
}
