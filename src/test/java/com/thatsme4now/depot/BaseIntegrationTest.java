package com.thatsme4now.depot;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.thatsme4now.depot.config.DataInitializer;
import com.thatsme4now.depot.config.HistoricalPriceSeeder;
import com.thatsme4now.depot.config.MonthlyPriceSeeder;

/**
 * Base class for integration tests: boots the full Spring context against an
 * in-memory H2 database (schema-h2.sql), with the startup demo-data seeders
 * mocked out so every test starts from a clean, deterministic database. Each
 * test method runs in its own transaction that is rolled back afterwards.
 */
@SpringBootTest
@TestPropertySource(properties = "depot.db=inmemory")
@Transactional
public abstract class BaseIntegrationTest {

    // Seeders are @Component beans that run on ApplicationReadyEvent and would
    // otherwise insert demo positions/transactions/prices before every test class.
    @MockBean
    protected DataInitializer dataInitializer;

    @MockBean
    protected HistoricalPriceSeeder historicalPriceSeeder;

    @MockBean
    protected MonthlyPriceSeeder monthlyPriceSeeder;
}
