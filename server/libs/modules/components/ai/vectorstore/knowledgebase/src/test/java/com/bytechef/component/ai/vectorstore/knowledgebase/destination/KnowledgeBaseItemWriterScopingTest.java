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
 * now carries the job principal into the step's context, so an embedded run for a connected user gets the pool gate:
 * the id must name a knowledge base in the EMBEDDED pool, which is what the first two tests pin. A knowledge base is no
 * longer owned by one account, so there is no owner-refusal case left here -- what separates two accounts sharing it is
 * the owner on the chunks a write produces, asserted below as the {@link Owner} VALUE {@code createSyncedDocument}
 * receives, not anything the admission gate itself can see.
 *
 * <p>
 * A run with no owner gets the weaker, fully unscoped gate: since a knowledge base is never assigned to an account,
 * there is no narrower question left for it to ask, so it resolves whichever knowledge base the id names.
 *
 * <p>
 * Every refusal is asserted against all three mutation paths rather than {@code createSyncedDocument} alone:
 * {@link KnowledgeBaseItemWriter#write} reaches an already-present document through {@code replaceSyncedDocument} and
 * {@code bumpLastSeenAt}, so a gate that only stopped creates would still permit writing into the wrong pool and
 * tombstoning it. The gate sits at lifecycle entry, so a refusal means {@code write} is never entered at all.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseItemWriterScopingTest {

    private static final long ACCOUNT_ID = 42L;

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
     * The whole point of carrying the job principal through the delegate: the pool it narrows to is asserted as a
     * value, since an owned run reading both pools would still satisfy a looser assertion while leaving the AUTOMATION
     * pool open. The owner itself no longer scopes which knowledge base is admitted -- only which chunks the write
     * stamps, asserted below on {@code createSyncedDocument}.
     */
    @Test
    void testAnEmbeddedRunResolvesThroughItsOwnPool() {
        when(ownerResolver.resolveJobPrincipal(JOB_PRINCIPAL_ID, PlatformType.EMBEDDED))
            .thenReturn(Optional.of(Owner.connectedUser(ACCOUNT_ID)));

        KnowledgeBase knowledgeBase = newKnowledgeBase();

        when(knowledgeBaseService.getKnowledgeBase(eq(KB_ID_VALUE), any())).thenReturn(knowledgeBase);
        when(knowledgeBaseDocumentService.findSyncedDocument(anyLong(), anyString(), any()))
            .thenReturn(Optional.empty());

        KnowledgeBaseItemWriter writer = newWriter();

        writer.open(inputParameters, connectionParameters, ownedRunContext(), executionContext);

        // The exact pool rather than any(): a pool of both would still satisfy a looser assertion while leaving the
        // AUTOMATION pool open.
        verify(knowledgeBaseService).getKnowledgeBase(KB_ID_VALUE, List.of(PlatformType.EMBEDDED));

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
     * The service refuses a knowledge base outside the EMBEDDED pool by reporting it as absent, so the step must not go
     * on to write anything. The pool argument it was refused with is pinned here too, since a gate that asked the wrong
     * question and happened to be refused would look identical.
     */
    @Test
    void testAnEmbeddedRunIsRefusedAKnowledgeBaseOutsideItsPool() {
        when(ownerResolver.resolveJobPrincipal(JOB_PRINCIPAL_ID, PlatformType.EMBEDDED))
            .thenReturn(Optional.of(Owner.connectedUser(ACCOUNT_ID)));

        when(knowledgeBaseService.getKnowledgeBase(KB_ID_VALUE, List.of(PlatformType.EMBEDDED)))
            .thenThrow(new RuntimeException("KnowledgeBase not found: " + KB_ID_VALUE));

        KnowledgeBaseItemWriter writer = newWriter();

        assertThatThrownBy(
            () -> writer.open(inputParameters, connectionParameters, ownedRunContext(), executionContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(String.valueOf(KB_ID_VALUE));

        assertNothingWasMutated();
    }

    /**
     * A run whose owner is unknowable -- Community, or a vendor automation sync -- gets the fully unscoped read: since
     * a knowledge base is never assigned to an account, there is no narrower question left to refuse it with, so it
     * resolves whichever knowledge base the id names and writes it, stamping the new chunks with no owner.
     */
    @Test
    void testARunWithNoOwnerResolvesWhicheverKnowledgeBaseTheIdNames() {
        when(knowledgeBaseService.getKnowledgeBase(KB_ID_VALUE)).thenReturn(newKnowledgeBase());
        when(knowledgeBaseDocumentService.findSyncedDocument(anyLong(), anyString(), any()))
            .thenReturn(Optional.empty());

        KnowledgeBaseItemWriter writer = newWriter();

        writer.open(inputParameters, connectionParameters, unownedRunContext(), executionContext);

        verify(knowledgeBaseService, never()).getKnowledgeBase(anyLong(), any());

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

    private static KnowledgeBase newKnowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        knowledgeBase.setId(KB_ID_VALUE);

        return knowledgeBase;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OwnerResolver> providerOf(OwnerResolver ownerResolver) {
        ObjectProvider<OwnerResolver> objectProvider = mock(ObjectProvider.class);

        when(objectProvider.getIfAvailable()).thenReturn(ownerResolver);

        return objectProvider;
    }
}
