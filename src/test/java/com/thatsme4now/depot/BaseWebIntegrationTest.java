package com.thatsme4now.depot;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.annotation.Resource;

/**
 * Base class for controller integration tests: adds {@link MockMvc} on top of
 * {@link BaseIntegrationTest}'s full Spring context + H2 database.
 */
@AutoConfigureMockMvc
public abstract class BaseWebIntegrationTest extends BaseIntegrationTest {

    @Resource
    protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = buildObjectMapper();

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }
}
