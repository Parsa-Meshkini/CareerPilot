package com.careerpilot.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CareerPilotServer.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:server-test;DB_CLOSE_DELAY=-1", "spring.config.import=",
        "careerpilot.llm.provider=mock", "careerpilot.storage-root=target/test-storage"})
@AutoConfigureMockMvc
class ServerApiTest {
    @Autowired MockMvc mvc;

    private String token() throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ext-" + System.nanoTime() + "@yorku.ca\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("token").asText();
    }

    @Test
    void protectedRoutesNeedAToken() throws Exception {
        mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("unauthenticated"));
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void extensionBatchImportReportsPerRecordOutcomes() throws Exception {
        String t = token();
        String payload = "{\"source\":\"york\",\"payload\":{\"extractor_version\":\"york-list-1\",\"records\":["
                + "{\"url\":\"https://experience.yorku.ca/p/1\",\"fields\":{\"Job ID\":\"9\",\"Job Title\":\"QA Co-op\",\"Organization\":\"Initech\",\"Application Deadline\":\"2026-11-30\"}},"
                + "{\"fields\":{\"Organization\":\"No Title Ltd\"}}]}}";
        mvc.perform(post("/api/jobs/batches").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL_FAILED"))
                .andExpect(jsonPath("$.imported").value(1)).andExpect(jsonPath("$.failed").value(1));
        mvc.perform(get("/api/jobs").header("Authorization", "Bearer " + t)).andExpect(jsonPath("$[0].title").value("QA Co-op"));
    }

    @Test
    void domainErrorsComeBackAsJson() throws Exception {
        String t = token();
        mvc.perform(post("/api/jobs/batches").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"linkedin\",\"payload\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
    }
}
