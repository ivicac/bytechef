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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.config.KnowledgeBaseIntTestConfiguration;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The shape of the registry key, asserted against the database rather than against the changelog text. A near-clone of
 * {@code DataTableNameUniqueIndexIntTest}, with an environment axis added: the knowledge base key is
 * {@code (name, platform_type, environment)}, not {@code (name, platform_type)}.
 *
 * <p>
 * This is what {@code uk_knowledge_base_name_platform_type_environment} exists to protect, and what
 * {@code 20260902000001_platform_knowledge_base_drop_name_environment_unique} exists to keep protected: the same name
 * living in both the AUTOMATION and EMBEDDED pools is not a duplicate, because
 * {@code uk_knowledge_base_name_environment} -- a narrower constraint keyed on {@code (name, environment)} alone,
 * arriving separately from 0_732 -- would otherwise forbid it. That property is inert on this tree today, since the
 * 0_732 changeset has not landed here yet, but this test still pins the registry key's own behavior so a future merge
 * that reintroduces the narrower constraint without going through the extracted drop fails here first, rather than
 * surfacing later as a confusing unique-constraint violation.
 *
 * <p>
 * Rows are written with raw SQL on purpose, because the point is what the database refuses rather than what the domain
 * declines to attempt.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = KnowledgeBaseIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class KnowledgeBaseNameUniqueIndexIntTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testOnePoolAndEnvironmentCannotHoldTheSameNameTwice() {
        insert("uqkbdocs", PlatformType.EMBEDDED, Environment.DEVELOPMENT);

        assertThatThrownBy(() -> insert("uqkbdocs", PlatformType.EMBEDDED, Environment.DEVELOPMENT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The pool is part of the key, so the same name in the other pool is a different knowledge base rather than a
     * duplicate.
     */
    @Test
    void testTheSameNameMayExistInBothPools() {
        insert("uqkbpools", PlatformType.AUTOMATION, Environment.DEVELOPMENT);

        assertThatCode(() -> insert("uqkbpools", PlatformType.EMBEDDED, Environment.DEVELOPMENT))
            .doesNotThrowAnyException();
    }

    /**
     * The environment is part of the key too, so the same name in the same pool but a different environment is also not
     * a duplicate.
     */
    @Test
    void testTheSameNameMayExistInDifferentEnvironments() {
        insert("uqkbenvs", PlatformType.EMBEDDED, Environment.DEVELOPMENT);

        assertThatCode(() -> insert("uqkbenvs", PlatformType.EMBEDDED, Environment.STAGING))
            .doesNotThrowAnyException();
    }

    private void insert(String name, PlatformType platformType, Environment environment) {
        jdbcTemplate.update(
            "INSERT INTO knowledge_base (name, platform_type, environment, created_date, created_by, " +
                "last_modified_date, last_modified_by, version) " +
                "VALUES (?, ?, ?, now(), 'test', now(), 'test', 0)",
            name, platformType.ordinal(), environment.ordinal());
    }
}
