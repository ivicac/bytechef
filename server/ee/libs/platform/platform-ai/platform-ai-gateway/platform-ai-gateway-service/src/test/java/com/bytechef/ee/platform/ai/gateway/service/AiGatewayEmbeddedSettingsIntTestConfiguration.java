/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.service;

import com.bytechef.commons.data.jdbc.converter.EncryptedMapWrapperToStringConverter;
import com.bytechef.commons.data.jdbc.converter.EncryptedStringToMapWrapperConverter;
import com.bytechef.encryption.Encryption;
import com.bytechef.encryption.EncryptionImpl;
import com.bytechef.encryption.EncryptionKey;
import com.bytechef.jackson.config.JacksonConfiguration;
import com.bytechef.liquibase.config.LiquibaseConfiguration;
import com.bytechef.platform.configuration.repository.PropertyRepository;
import com.bytechef.platform.configuration.service.PropertyService;
import com.bytechef.platform.configuration.service.PropertyServiceImpl;
import com.bytechef.platform.credential.store.CredentialStore;
import com.bytechef.platform.credential.store.service.DatabaseCredentialStore;
import com.bytechef.test.config.jdbc.AbstractIntTestJdbcConfiguration;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring test configuration for {@code AiGatewayEmbeddedSettingsServiceIntTest}. Wires the real
 * {@link PropertyServiceImpl} over the real {@code property} table (Testcontainers PostgreSQL), the default
 * {@link DatabaseCredentialStore}, and {@link AiGatewayEmbeddedSettingsServiceImpl} on top of it -- so the test
 * exercises the real {@code uk_property_key_scope_environment_null_scope_id} partial unique index and the JDBC
 * value-encryption round trip instead of a mocked {@link PropertyService}. Modeled directly on
 * {@code VariableIntTestConfiguration} in {@code platform-variable-service}, which solved this exact problem for the
 * same {@code property} table first.
 *
 * <p>
 * Unlike that precedent, whose {@code VariableServiceImpl} is public, {@link AiGatewayEmbeddedSettingsServiceImpl} is
 * package-private, so this configuration lives in its package (rather than a sibling {@code config} package) so it can
 * construct it directly.
 *
 * <p>
 * Beans are declared explicitly rather than via a broad {@code @ComponentScan} of
 * {@code com.bytechef.ee.platform.ai.gateway.service} so the module's other services in that package (routing policy,
 * provider, spend, budget, rate limit, model deployment) -- most of which need Redis, cost calculators, or other
 * collaborators this test does not have -- are not dragged into the context.
 *
 * @version ee
 */
@Import({
    JacksonConfiguration.class, LiquibaseConfiguration.class, PostgreSQLContainerConfiguration.class
})
@EnableAutoConfiguration
@Configuration
class AiGatewayEmbeddedSettingsIntTestConfiguration {

    @Bean
    EncryptionKey encryptionKey() {
        return () -> "tTB1/UBIbYLuCXVi4PPfzA==";
    }

    @Bean
    Encryption encryption(EncryptionKey encryptionKey) {
        return new EncryptionImpl(encryptionKey);
    }

    @Bean
    CredentialStore databaseCredentialStore() {
        return new DatabaseCredentialStore();
    }

    @Bean
    PropertyService propertyService(List<CredentialStore> credentialStores, PropertyRepository propertyRepository) {
        return new PropertyServiceImpl(credentialStores, propertyRepository);
    }

    @Bean
    AiGatewayEmbeddedSettingsService aiGatewayEmbeddedSettingsService(PropertyService propertyService) {
        return new AiGatewayEmbeddedSettingsServiceImpl(propertyService);
    }

    /**
     * Registers the {@code EncryptedMapWrapper} converters used by {@code Property.value} -- both directions, unlike
     * {@code PlatformConfigurationIntTestConfiguration} in platform-configuration-service, which registers only the
     * write-side {@link EncryptedMapWrapperToStringConverter}. Without the read-side
     * {@link EncryptedStringToMapWrapperConverter} too, Spring Data JDBC has no converter from the {@code TEXT} column
     * back to {@code EncryptedMapWrapper} and a saved property could not be read back.
     */
    @EnableJdbcAuditing(auditorAwareRef = "auditorProvider", dateTimeProviderRef = "auditingDateTimeProvider")
    public static class AiGatewayEmbeddedSettingsIntTestJdbcConfiguration extends AbstractIntTestJdbcConfiguration {

        private final Encryption encryption;
        private final ObjectMapper objectMapper;

        @SuppressFBWarnings("EI2")
        public AiGatewayEmbeddedSettingsIntTestJdbcConfiguration(Encryption encryption, ObjectMapper objectMapper) {
            this.encryption = encryption;
            this.objectMapper = objectMapper;
        }

        @Override
        protected List<?> userConverters() {
            return Arrays.asList(
                new EncryptedMapWrapperToStringConverter(encryption, objectMapper),
                new EncryptedStringToMapWrapperConverter(encryption, objectMapper));
        }
    }
}
