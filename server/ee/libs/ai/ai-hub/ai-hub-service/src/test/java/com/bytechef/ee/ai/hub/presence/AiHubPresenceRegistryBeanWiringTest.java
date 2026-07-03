/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/**
 * Pins that {@link AiHubPresenceRegistryImpl} can actually be instantiated BY THE CONTAINER, which every other test of
 * this class structurally cannot show: the unit suite calls the {@link java.time.Clock}-taking constructor directly, so
 * none of it exercises Spring's constructor selection.
 *
 * <p>
 * This is the same guard {@code AiHubAgentConversationRecorderBeanWiringTest} carries, and it is here because that one
 * was written for the recorder alone while this class had the identical shape — two constructors, the production one
 * plus a package-private {@code Clock} overload for tests, and no no-arg fallback. Spring auto-selects a constructor
 * only when there is exactly one candidate; with two and no {@code @Autowired} marker it reaches for a default
 * constructor that does not exist and kills context refresh with {@code NoSuchMethodException: <init>()}. It did, on
 * {@code bootRun}, after every unit and integration suite had passed.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubPresenceRegistryBeanWiringTest {

    @Test
    void testContainerInstantiatesRegistry() {
        try (AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext()) {
            // The registry is @ConditionalOnProperty; without this the bean definition would be skipped and the test
            // would pass vacuously.
            applicationContext.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("bytechef.ai.hub.enabled", "true")));

            applicationContext.register(DependenciesConfiguration.class, AiHubPresenceRegistryImpl.class);
            applicationContext.refresh();

            assertThat(applicationContext.getBean(AiHubPresenceRegistryImpl.class)).isNotNull();
        }
    }

    @Configuration
    static class DependenciesConfiguration {

        @Bean
        CacheManager cacheManager() {
            return mock(CacheManager.class);
        }
    }
}
