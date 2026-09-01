/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.knowledgebase.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.knowledgebase.facade.EmbeddedKnowledgeBaseApiFacade.ChunkingSettings;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.audit.KnowledgeBaseAuditPublisher;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import com.bytechef.platform.knowledgebase.repository.KnowledgeBaseRepository;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseServiceImpl;
import com.bytechef.platform.owner.Owner;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What an owned create has to produce, stated as the question the runtime actually asks: after the vendor creates
 * {@code docs} for one account, does a run belonging to that account resolve it, and does a run belonging to nobody
 * still not?
 *
 * <p>
 * Asserting the persisted {@code ownerId} instead would pass on a row that resolves for nobody. Ownership is two
 * columns and both lookups match on a whole owner, so a row carrying an id with no {@code ownerType} is invisible to
 * the account it names AND to the shared lookup, which asks for {@code owner_id IS NULL}.
 *
 * <p>
 * The rule itself is the real {@link KnowledgeBaseServiceImpl} rather than a restatement of it; only the two column
 * predicates the database would apply are modelled here, over the rows the facade actually saved.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddedKnowledgeBaseCreateOwnerResolutionTest {

    private static final long ACCOUNT_ID = 42L;
    private static final int ENVIRONMENT = 0;
    private static final long ENVIRONMENT_ID = 0L;

    private final List<KnowledgeBase> savedKnowledgeBases = new ArrayList<>();

    @Mock
    private KnowledgeBaseAuditPublisher knowledgeBaseAuditPublisher;

    @Mock
    private KnowledgeBaseDocumentService knowledgeBaseDocumentService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade;

    private EmbeddedKnowledgeBaseApiFacade embeddedKnowledgeBaseApiFacade;

    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @BeforeEach
    void setUp() {
        stubRepository();

        knowledgeBaseService = new KnowledgeBaseServiceImpl(
            eventPublisher, knowledgeBaseAuditPublisher, knowledgeBaseDocumentService, knowledgeBaseRepository);

        embeddedKnowledgeBaseApiFacade = new EmbeddedKnowledgeBaseApiFacadeImpl(
            knowledgeBaseDocumentFacade, knowledgeBaseService);
    }

    @Test
    void testAKnowledgeBaseCreatedForAnAccountResolvesForThatAccount() {
        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(ENVIRONMENT_ID, "docs", "d", ACCOUNT_ID,
            ChunkingSettings.none());

        Optional<KnowledgeBase> resolvedKnowledgeBase = knowledgeBaseService.fetchKnowledgeBase(
            "docs", ENVIRONMENT, PlatformType.EMBEDDED, Optional.of(Owner.connectedUser(ACCOUNT_ID)));

        assertThat(resolvedKnowledgeBase)
            .as("the account the vendor named must resolve the knowledge base it was given")
            .isPresent();
    }

    /**
     * The other half of the same row, and the half a persistence assertion cannot see: an owned knowledge base must not
     * be what a vendor run with no named account resolves. A row with an id and no type fails this one too -- it is
     * absent from the shared lookup as well -- so the pair brackets the defect from both sides.
     */
    @Test
    void testAKnowledgeBaseCreatedForAnAccountDoesNotResolveForARunWithNoOwner() {
        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(ENVIRONMENT_ID, "docs", "d", ACCOUNT_ID,
            ChunkingSettings.none());

        Optional<KnowledgeBase> resolvedKnowledgeBase = knowledgeBaseService.fetchKnowledgeBase(
            "docs", ENVIRONMENT, PlatformType.EMBEDDED, Optional.empty());

        assertThat(resolvedKnowledgeBase)
            .as("an account's knowledge base must never be what a run with no owner resolves")
            .isEmpty();
    }

    /**
     * The unowned create still has to land where the shared lookup can see it, so that writing the owner columns at all
     * did not quietly break the case that already worked.
     */
    @Test
    void testAKnowledgeBaseCreatedWithNoOwnerResolvesForARunWithNoOwner() {
        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(ENVIRONMENT_ID, "docs", "d", null, ChunkingSettings.none());

        Optional<KnowledgeBase> resolvedKnowledgeBase = knowledgeBaseService.fetchKnowledgeBase(
            "docs", ENVIRONMENT, PlatformType.EMBEDDED, Optional.empty());

        assertThat(resolvedKnowledgeBase)
            .as("a shared create must remain visible to the vendor's own runs")
            .isPresent();
    }

    /**
     * The whole point of the pool: the account's own copy wins over a shared one of the same name, which is what makes
     * a per-account knowledge base a drop-in override needing no workflow edit.
     */
    @Test
    void testAnAccountsOwnKnowledgeBaseWinsOverTheSharedOneOfTheSameName() {
        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(ENVIRONMENT_ID, "docs", "shared", null,
            ChunkingSettings.none());
        embeddedKnowledgeBaseApiFacade.createKnowledgeBase(ENVIRONMENT_ID, "docs", "the account's own", ACCOUNT_ID,
            ChunkingSettings.none());

        Optional<KnowledgeBase> resolvedKnowledgeBase = knowledgeBaseService.fetchKnowledgeBase(
            "docs", ENVIRONMENT, PlatformType.EMBEDDED, Optional.of(Owner.connectedUser(ACCOUNT_ID)));

        assertThat(resolvedKnowledgeBase.map(KnowledgeBase::getDescription))
            .as("the account's own copy, not the vendor's shared one of the same name")
            .contains("the account's own");
    }

    /**
     * Models the two lookups as the database would run them, over the rows the facade saved. The saved row is given an
     * id because {@code KnowledgeBase#equals} compares ids alone, so id-less fixtures would all be equal to each other.
     */
    private void stubRepository() {
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase knowledgeBase = invocation.getArgument(0);

            knowledgeBase.setId(savedKnowledgeBases.size() + 1L);

            savedKnowledgeBases.add(knowledgeBase);

            return knowledgeBase;
        });

        when(
            knowledgeBaseRepository.findByNameAndEnvironmentAndPlatformTypeAndOwnerIdAndOwnerType(
                anyString(), anyInt(), anyInt(), anyLong(), anyInt())).thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    int environment = invocation.getArgument(1);
                    int platformType = invocation.getArgument(2);
                    Long ownerId = invocation.getArgument(3);
                    Integer ownerType = invocation.getArgument(4);

                    return savedKnowledgeBases.stream()
                        .filter(knowledgeBase -> matchesName(knowledgeBase, name, environment, platformType))
                        .filter(knowledgeBase -> ownerId.equals(knowledgeBase.getOwnerId()))
                        .filter(knowledgeBase -> ownerType.equals(toOrdinal(knowledgeBase.getOwnerType())))
                        .findFirst();
                });

        when(
            knowledgeBaseRepository.findByNameAndEnvironmentAndPlatformTypeAndOwnerIdIsNull(
                anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    int environment = invocation.getArgument(1);
                    int platformType = invocation.getArgument(2);

                    return savedKnowledgeBases.stream()
                        .filter(knowledgeBase -> matchesName(knowledgeBase, name, environment, platformType))
                        .filter(knowledgeBase -> knowledgeBase.getOwnerId() == null)
                        .findFirst();
                });
    }

    private static boolean matchesName(
        KnowledgeBase knowledgeBase, String name, int environment, int platformType) {

        Environment knowledgeBaseEnvironment = knowledgeBase.getEnvironment();
        PlatformType knowledgeBasePlatformType = knowledgeBase.getPlatformType();

        return name.equals(knowledgeBase.getName()) && knowledgeBaseEnvironment.ordinal() == environment &&
            knowledgeBasePlatformType.ordinal() == platformType;
    }

    private static @Nullable Integer toOrdinal(@Nullable OwnerType ownerType) {
        return ownerType == null ? null : ownerType.ordinal();
    }
}
