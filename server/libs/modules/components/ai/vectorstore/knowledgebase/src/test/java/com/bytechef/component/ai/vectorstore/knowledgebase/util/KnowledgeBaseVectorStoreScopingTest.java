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

package com.bytechef.component.ai.vectorstore.knowledgebase.util;

import static com.bytechef.component.ai.vectorstore.knowledgebase.constant.KnowledgeBaseVectorStoreConstants.KNOWLEDGE_BASE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.definition.ai.agent.VectorStoreFunction;
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.file.storage.KnowledgeBaseFileStorage;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentChunkService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentTagService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The read hole this closes: {@code knowledgeBaseId} is an ordinary expression-enabled integer property of the
 * {@code VECTOR_STORE} cluster element, and the element used to wrap whatever id the run named -- no pool, no owner --
 * and hand the resulting store to an agent's retriever.
 *
 * <p>
 * Three separate claims are proven here, because two of them are the ways this fix could look done and enforce nothing.
 *
 * <ol>
 * <li>The context-carrying {@code createVectorStore} is the form the PUBLISHED element actually invokes. A default
 * overload redirects nothing on its own: Java resolves overloads by arity, so leaving the element's lambda on the
 * three-argument call would make the override below unreachable while every other assertion still passed.</li>
 * <li>The owner that reaches the resolution is NON-EMPTY. An empty owner is not a weaker answer but the opposite one --
 * {@code poolFor} opens both pools for it and {@code isReadableBy} admits every account's rows -- so a fix that
 * resolved with one would close this visually and leave it open.</li>
 * <li>A run belonging to one account is refused another account's knowledge base and accepted its own.</li>
 * </ol>
 *
 * <p>
 * The knowledge base service is answered by the real readability rule rather than by a per-test stub, so the refusal is
 * a consequence of the owner and pool that arrive rather than something the test arranges. That is what makes the
 * refusal case double as a control on claim 2: with an empty owner the pools would be both and account 43's knowledge
 * base would resolve cleanly.
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class KnowledgeBaseVectorStoreScopingTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long OTHER_ACCOUNT_ID = 43L;
    private static final Owner OWNER = Owner.connectedUser(ACCOUNT_ID);

    private static final long OWN_KNOWLEDGE_BASE_ID = 7L;
    private static final long OTHER_KNOWLEDGE_BASE_ID = 8L;

    // The agent action's context carries the job principal, not the account; the resolver is what turns one into the
    // other, exactly as it does for the actions beside this element.
    private static final long JOB_PRINCIPAL_ID = 11L;

    private final Map<Long, KnowledgeBase> knowledgeBases = new HashMap<>();

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final OwnerResolver ownerResolver = mock(OwnerResolver.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OwnerResolver> ownerResolverProvider = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        knowledgeBases.put(OWN_KNOWLEDGE_BASE_ID, knowledgeBaseOwnedBy(OWN_KNOWLEDGE_BASE_ID, ACCOUNT_ID));
        knowledgeBases.put(OTHER_KNOWLEDGE_BASE_ID, knowledgeBaseOwnedBy(OTHER_KNOWLEDGE_BASE_ID, OTHER_ACCOUNT_ID));

        when(ownerResolverProvider.getIfAvailable()).thenReturn(ownerResolver);
        when(ownerResolver.resolveJobPrincipal(JOB_PRINCIPAL_ID, PlatformType.EMBEDDED))
            .thenReturn(Optional.of(OWNER));

        when(knowledgeBaseService.getKnowledgeBase(anyLong(), any(), any()))
            .thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                List<PlatformType> platformTypes = invocation.getArgument(1);
                Optional<Owner> owner = invocation.getArgument(2);

                KnowledgeBase knowledgeBase = knowledgeBases.get(id);

                if (knowledgeBase == null || !platformTypes.contains(knowledgeBase.getPlatformType()) ||
                    !isReadableBy(knowledgeBase, owner)) {

                    throw new RuntimeException("KnowledgeBase not found: " + id);
                }

                return knowledgeBase;
            });
    }

    /**
     * Claim 1. Mutation-checked: restoring the element's lambda to the three-argument
     * {@code kbVectorStore.createVectorStore(inputParameters, ..., null)} leaves the scoped overload uninvoked and
     * turns this red, which is what separates "the override exists" from "the override runs".
     */
    @Test
    void testThePublishedElementResolvesThroughTheContextCarryingForm() throws Exception {
        applyElement(OWN_KNOWLEDGE_BASE_ID);

        verify(knowledgeBaseService).getKnowledgeBase(
            OWN_KNOWLEDGE_BASE_ID, List.of(PlatformType.EMBEDDED), Optional.of(OWNER));
        verify(knowledgeBaseService, never()).getKnowledgeBase(anyLong());
    }

    /**
     * Claim 2. Not "resolve was called" but what value arrived: an owner, and therefore a single pool. Both halves are
     * asserted because either one alone would pass against an empty owner in some arrangement -- {@code poolFor} only
     * narrows to EMBEDDED when the owner is present.
     */
    @Test
    void testTheOwnerReachingTheResolutionIsTheAccountTheRunActsFor() throws Exception {
        applyElement(OWN_KNOWLEDGE_BASE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlatformType>> platformTypesCaptor = ArgumentCaptor.forClass(List.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<Owner>> ownerCaptor = ArgumentCaptor.forClass(Optional.class);

        verify(knowledgeBaseService).getKnowledgeBase(
            eq(OWN_KNOWLEDGE_BASE_ID), platformTypesCaptor.capture(), ownerCaptor.capture());

        assertThat(ownerCaptor.getValue()).contains(OWNER);
        assertThat(platformTypesCaptor.getValue()).containsExactly(PlatformType.EMBEDDED);
    }

    /**
     * Claim 3, refusal half. The exception has to escape rather than be caught and downgraded to the raw id -- a
     * wrapper built from the input instead of from the resolved knowledge base would address account 43's store with
     * the refusal already behind it.
     */
    @Test
    void testARunNamingAnotherAccountsKnowledgeBaseIsRefused() {
        assertThatThrownBy(() -> applyElement(OTHER_KNOWLEDGE_BASE_ID))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining(String.valueOf(OTHER_KNOWLEDGE_BASE_ID));
    }

    /** Claim 3, acceptance half. A gate that refused everything would satisfy the case above and break the product. */
    @Test
    void testARunNamingItsOwnKnowledgeBaseSucceeds() throws Exception {
        assertThat(applyElement(OWN_KNOWLEDGE_BASE_ID)).isNotNull();
    }

    private org.springframework.ai.vectorstore.VectorStore applyElement(long knowledgeBaseId) throws Exception {
        ClusterElementDefinition<VectorStoreFunction> clusterElementDefinition = KnowledgeBaseVectorStore.of(
            mock(org.springframework.ai.vectorstore.VectorStore.class), mock(KnowledgeBaseDocumentChunkService.class),
            mock(KnowledgeBaseDocumentService.class), mock(KnowledgeBaseFileStorage.class), knowledgeBaseService,
            mock(KnowledgeBaseDocumentTagService.class), ownerResolverProvider);

        VectorStoreFunction vectorStoreFunction = clusterElementDefinition.getElement();

        Parameters inputParameters = ParametersFactory.create(Map.of(KNOWLEDGE_BASE_ID, knowledgeBaseId));

        return vectorStoreFunction.apply(
            inputParameters, ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), Map.of(),
            agentActionContext());
    }

    /**
     * The shape the agent RAG path hands this element: {@code AbstractAiAgentChatAction.getRagAdvisor} passes the
     * action's own {@code ActionContext}, which is an {@link ActionContextAware} carrying the job principal and the
     * pool of the run.
     */
    private static ActionContextAware agentActionContext() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(false);
        when(actionContextAware.getJobPrincipalId()).thenReturn(JOB_PRINCIPAL_ID);
        when(actionContextAware.getPlatformType()).thenReturn(PlatformType.EMBEDDED);

        return actionContextAware;
    }

    /** The rule {@code KnowledgeBaseServiceImpl} applies, so the refusal here is derived rather than arranged. */
    private static boolean isReadableBy(KnowledgeBase knowledgeBase, Optional<Owner> owner) {
        Long ownerId = knowledgeBase.getOwnerId();

        if (ownerId == null || owner.isEmpty()) {
            return true;
        }

        Owner curOwner = owner.get();

        return curOwner.id() == ownerId && curOwner.type() == knowledgeBase.getOwnerType();
    }

    private static KnowledgeBase knowledgeBaseOwnedBy(long id, long ownerId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(id);
        knowledgeBase.setOwnerId(ownerId);
        knowledgeBase.setOwnerType(OwnerType.CONNECTED_USER);
        knowledgeBase.setPlatformType(PlatformType.EMBEDDED);

        return knowledgeBase;
    }
}
