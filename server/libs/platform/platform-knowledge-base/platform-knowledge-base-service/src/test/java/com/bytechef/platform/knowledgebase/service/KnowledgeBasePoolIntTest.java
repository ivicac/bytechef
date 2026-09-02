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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * @author Ivica Cardic
 */
@SpringBootTest(classes = KnowledgeBaseIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class KnowledgeBasePoolIntTest {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Test
    void testNeitherPoolListsTheOther() {
        knowledgeBaseService.createKnowledgeBase(knowledgeBase("kbauto", 0, PlatformType.AUTOMATION));
        knowledgeBaseService.createKnowledgeBase(knowledgeBase("kbembedded", 0, PlatformType.EMBEDDED));

        assertThat(names(0, PlatformType.AUTOMATION)).contains("kbauto")
            .doesNotContain("kbembedded");
        assertThat(names(0, PlatformType.EMBEDDED)).contains("kbembedded")
            .doesNotContain("kbauto");
    }

    @Test
    void testTheSameNameCanBeCreatedInASecondEnvironment() {
        knowledgeBaseService.createKnowledgeBase(knowledgeBase("perenv", 0, PlatformType.AUTOMATION));
        knowledgeBaseService.createKnowledgeBase(knowledgeBase("perenv", 1, PlatformType.AUTOMATION));

        assertThat(names(0, PlatformType.AUTOMATION)).contains("perenv");
        assertThat(names(1, PlatformType.AUTOMATION)).contains("perenv");
    }

    private List<String> names(int environment, PlatformType platformType) {
        return knowledgeBaseService.getKnowledgeBases(environment, platformType)
            .stream()
            .map(KnowledgeBase::getName)
            .toList();
    }

    private static KnowledgeBase knowledgeBase(String name, int environment, PlatformType platformType) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setName(name);
        knowledgeBase.setEnvironment(Environment.values()[environment]);
        knowledgeBase.setPlatformType(platformType);

        return knowledgeBase;
    }
}
