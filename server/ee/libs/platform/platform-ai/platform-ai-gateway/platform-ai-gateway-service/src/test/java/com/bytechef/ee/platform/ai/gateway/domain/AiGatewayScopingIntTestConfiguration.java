/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.domain;

import com.bytechef.liquibase.config.LiquibaseConfiguration;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Minimal Spring context for {@link AiGatewayScopingIntTest}: just enough to run the real Liquibase changelog
 * (platform/ai/gateway/00000000000001_ai_gateway_init.xml) against Testcontainers PostgreSQL and get an autoconfigured
 * {@link org.springframework.jdbc.core.JdbcTemplate}. No repositories, services, encryption, or credential store beans
 * are wired -- the test writes rows with raw SQL (mirrors {@code KnowledgeBaseNameUniqueIndexIntTest}) because the
 * point is what the database refuses, not what the domain layer declines to attempt. Deliberately narrower than
 * {@code AiGatewayEmbeddedSettingsIntTestConfiguration}, which additionally wires {@code PropertyService} and
 * encryption for the unrelated {@code property} table.
 *
 * @version ee
 */
@Import({
    LiquibaseConfiguration.class, PostgreSQLContainerConfiguration.class
})
@EnableAutoConfiguration
@Configuration
class AiGatewayScopingIntTestConfiguration {
}
