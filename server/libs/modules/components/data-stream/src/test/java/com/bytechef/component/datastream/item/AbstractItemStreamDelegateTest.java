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

package com.bytechef.component.datastream.item;

import static com.bytechef.component.datastream.constant.DataStreamConstants.CLUSTER_ELEMENT_NAME;
import static com.bytechef.component.datastream.constant.DataStreamConstants.INPUT_PARAMETERS;
import static com.bytechef.component.datastream.constant.DataStreamConstants.MODE_TYPE;
import static com.bytechef.component.datastream.constant.DataStreamConstants.PRINCIPAL_ID;
import static com.bytechef.component.datastream.constant.DataStreamConstants.TENANT_ID;
import static com.bytechef.component.definition.datastream.ItemWriter.DESTINATION;
import static com.bytechef.platform.configuration.constant.WorkflowExtConstants.COMPONENT_NAME;
import static com.bytechef.platform.configuration.constant.WorkflowExtConstants.COMPONENT_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.component.definition.datastream.ItemWriter;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.context.ContextFactory;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * {@code DataStreamStreamActionDefinition} has always written {@code principalId} and {@code modeType} into the Spring
 * Batch job parameters, and until now nothing read them. They are the only route by which a data stream element can
 * learn the owner its run acts for: the batch job runs on its own thread with no security context, so an element that
 * cannot see the job principal resolves an EMPTY owner -- which opens every pool rather than none.
 *
 * <p>
 * {@link AbstractItemStreamDelegate#beforeStep} is shared by every SOURCE, PROCESSOR and DESTINATION element and by the
 * Enterprise context store writer, so these assertions are made through a concrete delegate and cover all of them. The
 * captured context is then followed one frame further, into {@link ItemWriter#open}, because a context built correctly
 * and handed to no one would look identical here.
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class AbstractItemStreamDelegateTest {

    private static final String TEST_TENANT_ID = "test_tenant";

    @Test
    void testCarriesTheJobPrincipalAndPlatformTypeIntoTheClusterElementContext() {
        ContextFactory contextFactory = mock(ContextFactory.class);

        newDelegate(contextFactory, mock(ItemWriter.class))
            .beforeStep(newStepExecution(jobParameters(77L, "EMBEDDED")));

        assertThat(capturedJobPrincipalId(contextFactory)).isEqualTo(77L);
        assertThat(capturedPlatformType(contextFactory)).isEqualTo(PlatformType.EMBEDDED);
    }

    @Test
    void testHandsThatContextToTheItemWriter() {
        ContextFactory contextFactory = mock(ContextFactory.class);
        ClusterElementContext clusterElementContext = mock(ClusterElementContext.class);

        when(
            contextFactory.createClusterElementContext(
                anyString(), anyInt(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenReturn(clusterElementContext);

        ItemWriter itemWriter = mock(ItemWriter.class);

        ItemStreamWriterDelegate delegate = newDelegate(contextFactory, itemWriter);

        delegate.beforeStep(newStepExecution(jobParameters(77L, "EMBEDDED")));
        delegate.open(new ExecutionContext());

        verify(itemWriter).open(any(), any(), same(clusterElementContext), any());
    }

    /**
     * A run with no persisted job principal -- an editor test run -- writes neither parameter, and must land on the
     * pre-existing path rather than on a guessed one.
     */
    @Test
    void testAbsentParametersLeaveBothNull() {
        ContextFactory contextFactory = mock(ContextFactory.class);

        newDelegate(contextFactory, mock(ItemWriter.class))
            .beforeStep(newStepExecution(jobParameters(null, null)));

        assertThat(capturedJobPrincipalId(contextFactory)).isNull();
        assertThat(capturedPlatformType(contextFactory)).isNull();
    }

    /**
     * {@code modeType} is written as a name rather than as an enum, so a value this JVM does not know is possible. Null
     * is the honest answer: a guessed pool would be a silent mis-scoping.
     */
    @Test
    void testAnUnparseablePlatformTypeIsNullRatherThanGuessed() {
        ContextFactory contextFactory = mock(ContextFactory.class);

        newDelegate(contextFactory, mock(ItemWriter.class))
            .beforeStep(newStepExecution(jobParameters(77L, "NOT_A_PLATFORM_TYPE")));

        assertThat(capturedJobPrincipalId(contextFactory)).isEqualTo(77L);
        assertThat(capturedPlatformType(contextFactory)).isNull();
    }

    private static Long capturedJobPrincipalId(ContextFactory contextFactory) {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);

        verify(contextFactory).createClusterElementContext(
            anyString(), anyInt(), anyString(), captor.capture(), any(), any(), anyBoolean());

        return captor.getValue();
    }

    private static PlatformType capturedPlatformType(ContextFactory contextFactory) {
        ArgumentCaptor<PlatformType> captor = ArgumentCaptor.forClass(PlatformType.class);

        verify(contextFactory).createClusterElementContext(
            anyString(), anyInt(), anyString(), any(), any(), captor.capture(), anyBoolean());

        return captor.getValue();
    }

    private static JobParameters jobParameters(Long principalId, String modeType) {
        Map<String, Object> clusterElementMap = new HashMap<>();

        clusterElementMap.put(COMPONENT_NAME, "knowledgeBase");
        clusterElementMap.put(COMPONENT_VERSION, 1);
        clusterElementMap.put(CLUSTER_ELEMENT_NAME, "writeAsDocument");
        clusterElementMap.put(INPUT_PARAMETERS, Map.of("sourceId", 1L));

        Set<JobParameter<?>> parameters = new HashSet<>();

        parameters.add(new JobParameter<>(DESTINATION.name(), clusterElementMap, Map.class));
        parameters.add(new JobParameter<>(TENANT_ID, TEST_TENANT_ID, String.class));
        parameters.add(new JobParameter<>(MetadataConstants.EDITOR_ENVIRONMENT, false, Boolean.class));

        if (principalId != null) {
            parameters.add(new JobParameter<>(PRINCIPAL_ID, principalId, Long.class));
        }

        if (modeType != null) {
            parameters.add(new JobParameter<>(MODE_TYPE, modeType, String.class));
        }

        return new JobParameters(parameters);
    }

    private static ItemStreamWriterDelegate newDelegate(ContextFactory contextFactory, ItemWriter itemWriter) {
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);

        when(clusterElementDefinitionService.<ItemWriter>getClusterElement(any(), any(Integer.class), any()))
            .thenReturn(itemWriter);

        return new ItemStreamWriterDelegate(clusterElementDefinitionService, contextFactory);
    }

    private static StepExecution newStepExecution(JobParameters jobParameters) {
        JobExecution jobExecution = mock(JobExecution.class);
        StepExecution stepExecution = mock(StepExecution.class);

        when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        when(jobExecution.getJobParameters()).thenReturn(jobParameters);

        return stepExecution;
    }
}
