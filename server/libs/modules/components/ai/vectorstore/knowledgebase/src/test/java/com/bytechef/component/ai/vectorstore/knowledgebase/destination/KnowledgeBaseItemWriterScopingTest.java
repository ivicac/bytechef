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

package com.bytechef.component.ai.vectorstore.knowledgebase.destination;

import static com.bytechef.component.ai.vectorstore.knowledgebase.destination.KnowledgeBaseItemWriter.MODE;
import static com.bytechef.component.ai.vectorstore.knowledgebase.destination.KnowledgeBaseItemWriter.MODE_FULL_REPLACE;
import static com.bytechef.component.ai.vectorstore.knowledgebase.destination.KnowledgeBaseItemWriter.SOURCE_ID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.datastream.ExecutionContext;
import com.bytechef.platform.component.definition.ClusterElementContextAware;
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseSource;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseSourceService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A {@link KnowledgeBaseSource} row names a knowledge base and constrains nothing else -- it carries no owner, no pool
 * and no environment -- and {@code sourceId} is an ordinary expression-enabled parameter. So the id alone decided which
 * knowledge base a run wrote documents into and, in {@code FULL_REPLACE}, tombstoned.
 *
 * <p>
 * Which question this step can ask depends on whether it can learn the owner its run acts for. The data stream delegate
 * now carries the job principal into the step's context, so an embedded run for a connected user gets the full gate --
 * pool AND owner -- and that is what the first two tests pin. The assertion is on the resolved {@link Owner} VALUE,
 * because an empty owner is not a weaker answer than a wrong one: it opens both pools and admits every account's
 * knowledge base, so a gate reached with one would look tightened while enforcing nothing.
 *
 * <p>
 * A run with no owner still gets the weaker gate, unchanged: any knowledge base assigned to an account is refused,
 * because a caller who cannot name their owner cannot be shown to be that account.
 *
 * <p>
 * Every refusal is asserted against all three mutation paths rather than {@code createSyncedDocument} alone:
 * {@link KnowledgeBaseItemWriter#write} reaches an already-present document through {@code replaceSyncedDocument} and
 * {@code bumpLastSeenAt}, so a gate that only stopped creates would still permit cross-account overwrite and
 * tombstoning. The gate sits at lifecycle entry, so a refusal means {@code write} is never entered at all.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseItemWriterScopingTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long OTHER_ACCOUNT_ID = 43L;

    private static final long JOB_PRINCIPAL_ID = 77L;

    private static final Long SOURCE_ID_VALUE = 200L;
    private static final Long KB_ID_VALUE = 300L;

    private KnowledgeBaseSourceService knowledgeBaseSourceService;
    private KnowledgeBaseDocumentService knowledgeBaseDocumentService;
    private KnowledgeBaseService knowledgeBaseService;
    private OwnerResolver ownerResolver;

    private Parameters inputParameters;
    private Parameters connectionParameters;
    private ExecutionContext executionContext;

    @BeforeEach
    void setUp() {
        knowledgeBaseSourceService = mock(KnowledgeBaseSourceService.class);
        knowledgeBaseDocumentService = mock(KnowledgeBaseDocumentService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        ownerResolver = mock(OwnerResolver.class);

        inputParameters = mock(Parameters.class);
        connectionParameters = mock(Parameters.class);
        executionContext = mock(ExecutionContext.class);

        when(inputParameters.getRequiredLong(SOURCE_ID)).thenReturn(SOURCE_ID_VALUE);
        when(inputParameters.getString(MODE, MODE_FULL_REPLACE)).thenReturn(MODE_FULL_REPLACE);

        KnowledgeBaseSource source = new KnowledgeBaseSource();

        source.setKnowledgeBaseId(KB_ID_VALUE);

        when(knowledgeBaseSourceService.fetch(SOURCE_ID_VALUE)).thenReturn(Optional.of(source));
    }

    /**
     * The whole point of carrying the job principal through the delegate. The owner reaching the gate is asserted as a
     * value, and so is the pool it narrows to: an owned run reads EMBEDDED alone, which is what stops an embedded run
     * writing an unassigned AUTOMATION knowledge base.
     */
    @Test
    void testAnEmbeddedRunResolvesItsOwnAccountAndOnlyItsOwnPool() {
        when(ownerResolver.resolveJobPrincipal(JOB_PRINCIPAL_ID, PlatformType.EMBEDDED))
            .thenReturn(Optional.of(Owner.connectedUser(ACCOUNT_ID)));

        KnowledgeBase knowledgeBase = newKnowledgeBase(ACCOUNT_ID);

        when(knowledgeBaseService.getKnowledgeBase(eq(KB_ID_VALUE), any(), any())).thenReturn(knowledgeBase);
        when(knowledgeBaseDocumentService.findSyncedDocument(anyLong(), anyString(), any()))
            .thenReturn(Optional.empty());

        KnowledgeBaseItemWriter writer = newWriter();

        writer.open(inputParameters, connectionParameters, ownedRunContext(), executionContext);

        // The exact arguments rather than any(): an owner reaching the gate as Optional.empty() would still satisfy a
        // looser assertion while admitting every account's knowledge base, and a pool of both would still satisfy one
        // while leaving the AUTOMATION pool open.
        verify(knowledgeBaseService)
            .getKnowledgeBase(
                KB_ID_VALUE, List.of(PlatformType.EMBEDDED), Optional.of(Owner.connectedUser(ACCOUNT_ID)));

        Map<String, Object> sourceRecord = new LinkedHashMap<>();

        sourceRecord.put("id", "rec1");
        sourceRecord.put("text", "Hello world");

        writer.write(List.of(sourceRecord));

        verify(knowledgeBaseDocumentService)
            .createSyncedDocument(
                eq(KB_ID_VALUE), anyLong(), anyString(), anyString(), anyString(), any(), any(), anyString(), any(),
                eq(Owner.connectedUser(ACCOUNT_ID)));
    }

    /**
     * The service refuses a knowledge base another account owns by reporting it as absent, so the step must not go on
     * to write anything. The pool-and-owner arguments it was refused with are pinned here too, since a gate that asked
     * the wrong question and happened to be refused would look identical.
     */
    @Test
    void testAnEmbeddedRunIsRefusedAnotherAccountsKnowledgeBase() {
        when(ownerResolver.resolveJobPrincipal(JOB_PRINCIPAL_ID, PlatformType.EMBEDDED))
            .thenReturn(Optional.of(Owner.connectedUser(OTHER_ACCOUNT_ID)));

        when(
            knowledgeBaseService.getKnowledgeBase(
                KB_ID_VALUE, List.of(PlatformType.EMBEDDED), Optional.of(Owner.connectedUser(OTHER_ACCOUNT_ID))))
                    .thenThrow(new RuntimeException("KnowledgeBase not found: " + KB_ID_VALUE));

        KnowledgeBaseItemWriter writer = newWriter();

        assertThatThrownBy(
            () -> writer.open(inputParameters, connectionParameters, ownedRunContext(), executionContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(String.valueOf(KB_ID_VALUE));

        assertNothingWasMutated();
    }

    /**
     * A run whose owner is unknowable -- Community, or a vendor automation sync -- keeps the weaker gate. Resolving
     * with the empty owner instead would ADMIT this knowledge base, which is why the two branches exist rather than one
     * call covering both.
     */
    @Test
    void testARunWithNoOwnerIsStillRefusedAnyAssignedKnowledgeBase() {
        when(knowledgeBaseService.getKnowledgeBase(KB_ID_VALUE)).thenReturn(newKnowledgeBase(ACCOUNT_ID));

        KnowledgeBaseItemWriter writer = newWriter();

        assertThatThrownBy(
            () -> writer.open(inputParameters, connectionParameters, unownedRunContext(), executionContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(KB_ID_VALUE));

        verify(knowledgeBaseService, never()).getKnowledgeBase(anyLong(), any(), any());

        assertNothingWasMutated();
    }

    /**
     * The accepted case for a run with no owner also pins which knowledge base it binds, so a gate that refused
     * correctly but wrote somewhere else would still be caught.
     */
    @Test
    void testARunWithNoOwnerStillWritesAnUnassignedKnowledgeBase() {
        when(knowledgeBaseService.getKnowledgeBase(KB_ID_VALUE)).thenReturn(newKnowledgeBase(null));
        when(knowledgeBaseDocumentService.findSyncedDocument(anyLong(), anyString(), any()))
            .thenReturn(Optional.empty());

        KnowledgeBaseItemWriter writer = newWriter();

        writer.open(inputParameters, connectionParameters, unownedRunContext(), executionContext);

        Map<String, Object> sourceRecord = new LinkedHashMap<>();

        sourceRecord.put("id", "rec1");
        sourceRecord.put("text", "Hello world");

        writer.write(List.of(sourceRecord));

        verify(knowledgeBaseDocumentService)
            .createSyncedDocument(
                eq(KB_ID_VALUE), anyLong(), anyString(), anyString(), anyString(), any(), any(), anyString(), any(),
                isNull());
    }

    private void assertNothingWasMutated() {
        verify(knowledgeBaseDocumentService, never())
            .createSyncedDocument(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), anyString(), any(), any());
        verify(knowledgeBaseDocumentService, never())
            .replaceSyncedDocument(anyLong(), anyString(), anyString(), any(), any(), anyString(), any());
        verify(knowledgeBaseDocumentService, never())
            .bumpLastSeenAt(any(), any());
    }

    private KnowledgeBaseItemWriter newWriter() {
        return new KnowledgeBaseItemWriter(
            knowledgeBaseSourceService, knowledgeBaseDocumentService, knowledgeBaseService, providerOf(ownerResolver));
    }

    /**
     * The shape {@code AbstractItemStreamDelegate} now builds for a production run: a cluster-element context carrying
     * the job principal from the Spring Batch job parameters, and no agent action context.
     */
    private static Context ownedRunContext() {
        ClusterElementContextAware clusterElementContextAware = mock(ClusterElementContextAware.class);

        when(clusterElementContextAware.getJobPrincipalId()).thenReturn(JOB_PRINCIPAL_ID);
        when(clusterElementContextAware.getPlatformType()).thenReturn(PlatformType.EMBEDDED);

        return clusterElementContextAware;
    }

    private static Context unownedRunContext() {
        return mock(ClusterElementContextAware.class);
    }

    private static KnowledgeBase newKnowledgeBase(Long ownerId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(KB_ID_VALUE);

        if (ownerId != null) {
            knowledgeBase.setOwnerId(ownerId);
            knowledgeBase.setOwnerType(OwnerType.CONNECTED_USER);
        }

        return knowledgeBase;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OwnerResolver> providerOf(OwnerResolver ownerResolver) {
        ObjectProvider<OwnerResolver> objectProvider = mock(ObjectProvider.class);

        when(objectProvider.getIfAvailable()).thenReturn(ownerResolver);

        return objectProvider;
    }
}
