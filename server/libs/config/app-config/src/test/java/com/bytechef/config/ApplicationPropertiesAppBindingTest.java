/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * @author Ivica Cardic
 */
public class ApplicationPropertiesAppBindingTest {

    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    public void testAppPropertiesBind() {
        applicationContextRunner
            .withPropertyValues(
                "bytechef.app.execution.timeout=45s",
                "bytechef.app.proxy.max-request-bytes=1024",
                "bytechef.app.proxy.max-response-bytes=2048",
                "bytechef.app.proxy.timeout=15s")
            .run(context -> {
                assertThat(context).hasNotFailed();

                ApplicationProperties.App app = context.getBean(ApplicationProperties.class)
                    .getApp();

                assertThat(app.getExecution()
                    .getTimeout()).isEqualTo(Duration.ofSeconds(45));
                assertThat(app.getProxy()
                    .getMaxRequestBytes()).isEqualTo(1024L);
                assertThat(app.getProxy()
                    .getMaxResponseBytes()).isEqualTo(2048L);
                assertThat(app.getProxy()
                    .getTimeout()).isEqualTo(Duration.ofSeconds(15));
            });
    }

    @EnableConfigurationProperties(ApplicationProperties.class)
    static class TestConfiguration {
    }
}
