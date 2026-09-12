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

package com.bytechef.platform.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.config.KnowledgeBaseIntTestConfiguration;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The same resolution rule {@code KnowledgeBaseNameResolutionTest} pins over mocks, run against a real schema.
 *
 * <p>
 * Worth both: the mocked test says which query is made, and this one says that query is real SQL that Postgres answers,
 * including the environment and pool split it is scoped by.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = KnowledgeBaseIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class KnowledgeBaseNameResolutionIntTest {

    private static final int ENVIRONMENT = 0;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Test
    void testResolvingAnExistingNameReturnsIt() {
        KnowledgeBase knowledgeBase = create("nrexisting");

        assertThat(resolve("nrexisting")).contains(knowledgeBase);
    }

    /**
     * Physical separation of one environment from another is what the environment column is for, and resolution has to
     * respect it or a DEVELOPMENT run would read PRODUCTION's knowledge base.
     */
    @Test
    void testResolutionDoesNotCrossEnvironments() {
        create("nrperenv");

        assertThat(knowledgeBaseService.fetchKnowledgeBase("nrperenv", 1, PlatformType.EMBEDDED)).isEmpty();
    }

    /**
     * Physical separation of one pool from another is what the platform_type column is for, and resolution has to
     * respect it or an embedded run would read an automation knowledge base of the same name.
     */
    @Test
    void testResolutionDoesNotCrossPools() {
        create("nrperpool");

        assertThat(knowledgeBaseService.fetchKnowledgeBase("nrperpool", ENVIRONMENT, PlatformType.AUTOMATION))
            .isEmpty();
    }

    @Test
    void testResolvingAMissingNameFindsNothing() {
        assertThat(resolve("nrabsent")).isEmpty();
    }

    private Optional<KnowledgeBase> resolve(String name) {
        return knowledgeBaseService.fetchKnowledgeBase(name, ENVIRONMENT, PlatformType.EMBEDDED);
    }

    private KnowledgeBase create(String name) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setName(name);
        knowledgeBase.setEnvironment(Environment.values()[ENVIRONMENT]);
        knowledgeBase.setPlatformType(PlatformType.EMBEDDED);

        return knowledgeBaseService.createKnowledgeBase(knowledgeBase);
    }
}
