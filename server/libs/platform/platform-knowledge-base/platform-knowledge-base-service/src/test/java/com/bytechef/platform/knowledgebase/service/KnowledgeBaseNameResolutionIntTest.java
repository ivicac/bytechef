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
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.config.KnowledgeBaseIntTestConfiguration;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.owner.Owner;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The same resolution rule {@code KnowledgeBaseNameResolutionTest} pins over mocks, run against a real schema.
 *
 * <p>
 * Worth both: the mocked test says which lookup is made and the rule composed from them, and this one says the two
 * derived queries behind them are real SQL that Postgres answers. The owned lookup in particular compares
 * {@code owner_type}, which is an enum ordinal stored as an int and exposed through accessors typed as the enum -- a
 * mock cannot be wrong about that mapping.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = KnowledgeBaseIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class KnowledgeBaseNameResolutionIntTest {

    private static final Owner ACCOUNT_A = Owner.connectedUser(7701L);
    private static final Owner ACCOUNT_B = Owner.connectedUser(7702L);
    private static final int ENVIRONMENT = 0;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Test
    void testTheAccountsOwnKnowledgeBaseWinsOverTheSharedOne() {
        create("nrowned", null);

        KnowledgeBase owned = create("nrowned", ACCOUNT_A);

        assertThat(resolve("nrowned", ACCOUNT_A)).contains(owned);
    }

    @Test
    void testAnAccountWithNoOwnKnowledgeBaseFallsBackToTheSharedOne() {
        KnowledgeBase shared = create("nrfallback", null);

        create("nrfallback", ACCOUNT_A);

        assertThat(resolve("nrfallback", ACCOUNT_B))
            .as("an account with no copy of its own reads the vendor's")
            .contains(shared);
    }

    @Test
    void testAnUnownedRunNeverFallsThroughToAnAccountsKnowledgeBase() {
        create("nronlyowned", ACCOUNT_A);

        assertThat(resolve("nronlyowned", null)).isEmpty();
    }

    @Test
    void testAnUnownedRunResolvesTheSharedKnowledgeBase() {
        KnowledgeBase shared = create("nrvendor", null);

        create("nrvendor", ACCOUNT_A);

        assertThat(resolve("nrvendor", null)).contains(shared);
    }

    /**
     * Physical separation of one environment from another is what the environment column is for, and resolution has to
     * respect it or a DEVELOPMENT run would read PRODUCTION's knowledge base.
     */
    @Test
    void testResolutionDoesNotCrossEnvironments() {
        create("nrperenv", null);

        assertThat(knowledgeBaseService.fetchKnowledgeBase("nrperenv", 1, PlatformType.EMBEDDED, Optional.empty()))
            .isEmpty();
    }

    @Test
    void testResolvingAMissingNameFindsNothing() {
        assertThat(resolve("nrabsent", ACCOUNT_A)).isEmpty();
    }

    private Optional<KnowledgeBase> resolve(String name, @Nullable Owner owner) {
        return knowledgeBaseService.fetchKnowledgeBase(
            name, ENVIRONMENT, PlatformType.EMBEDDED, Optional.ofNullable(owner));
    }

    private KnowledgeBase create(String name, @Nullable Owner owner) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setName(name);
        knowledgeBase.setEnvironment(Environment.values()[ENVIRONMENT]);
        knowledgeBase.setPlatformType(PlatformType.EMBEDDED);

        if (owner != null) {
            OwnerType ownerType = owner.type();

            knowledgeBase.setOwnerId(owner.id());
            knowledgeBase.setOwnerType(ownerType);
        }

        return knowledgeBaseService.createKnowledgeBase(knowledgeBase);
    }
}
