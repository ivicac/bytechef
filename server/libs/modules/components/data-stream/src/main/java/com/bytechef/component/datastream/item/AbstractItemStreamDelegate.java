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
import static com.bytechef.component.datastream.constant.DataStreamConstants.COMPONENT_CONNECTION;
import static com.bytechef.component.datastream.constant.DataStreamConstants.INPUT_PARAMETERS;
import static com.bytechef.component.datastream.constant.DataStreamConstants.MODE_TYPE;
import static com.bytechef.component.datastream.constant.DataStreamConstants.PRINCIPAL_ID;
import static com.bytechef.component.datastream.constant.DataStreamConstants.TENANT_ID;
import static com.bytechef.platform.configuration.constant.WorkflowExtConstants.COMPONENT_NAME;
import static com.bytechef.platform.configuration.constant.WorkflowExtConstants.COMPONENT_VERSION;

import com.bytechef.commons.util.MapUtils;
import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.component.definition.ClusterElementDefinition.ClusterElementType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.context.ContextFactory;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.constant.PlatformType;
import java.util.Map;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

/**
 * @author Ivica Cardic
 */
public abstract class AbstractItemStreamDelegate {

    protected boolean editorEnvironment;
    protected String componentName;
    protected String clusterElementName;
    protected int componentVersion;
    protected @Nullable ComponentConnection componentConnection;
    protected @Nullable Parameters connectionParameters;
    protected ClusterElementContext clusterElementContext;
    protected Parameters inputParameters;
    protected String tenantId;

    private final ClusterElementType clusterElementType;
    private final ContextFactory contextFactory;

    protected AbstractItemStreamDelegate(ClusterElementType clusterElementType, ContextFactory contextFactory) {
        this.contextFactory = contextFactory;
        this.clusterElementType = clusterElementType;
    }

    @BeforeStep
    @SuppressWarnings("unchecked")
    public void beforeStep(final StepExecution stepExecution) {
        JobParameters jobParameters = stepExecution.getJobExecution()
            .getJobParameters();

        JobParameter<?> jobParameter = jobParameters.getParameter(clusterElementType.name());

        if (jobParameter == null) {
            return;
        }

        Map<String, ?> clusterElementMap = (Map<String, ?>) jobParameter.value();

        componentName = MapUtils.getRequiredString(clusterElementMap, COMPONENT_NAME);
        componentVersion = MapUtils.getRequiredInteger(clusterElementMap, COMPONENT_VERSION);
        clusterElementName = MapUtils.getRequiredString(clusterElementMap, CLUSTER_ELEMENT_NAME);
        componentConnection = MapUtils.get(clusterElementMap, COMPONENT_CONNECTION, ComponentConnection.class);

        connectionParameters = componentConnection == null
            ? null : ParametersFactory.create(componentConnection.getParameters());

        inputParameters = ParametersFactory.create(MapUtils.getRequiredMap(clusterElementMap, INPUT_PARAMETERS));

        jobParameter = Validate.notNull(jobParameters.getParameter(TENANT_ID), "tenantId is required");

        tenantId = (String) jobParameter.value();

        jobParameter = Validate.notNull(
            jobParameters.getParameter(MetadataConstants.EDITOR_ENVIRONMENT), "editorEnvironment is required");

        editorEnvironment = (boolean) jobParameter.value();

        // DataStreamStreamActionDefinition has always written these two, and until now nothing read them. They are
        // what lets a SOURCE, PROCESSOR or DESTINATION element derive the owner its run acts for: the Spring Batch job
        // runs on its own thread with no security context, so an element that cannot see the job principal resolves an
        // EMPTY owner -- which means "sees every pool" rather than "sees none".
        Long jobPrincipalId = readJobPrincipalId(jobParameters);
        PlatformType platformType = readPlatformType(jobParameters);

        clusterElementContext = contextFactory.createClusterElementContext(
            componentName, componentVersion, clusterElementName, jobPrincipalId, componentConnection, platformType,
            editorEnvironment);

        doBeforeStep(stepExecution);
    }

    protected abstract void doBeforeStep(StepExecution stepExecution);

    private static @Nullable Long readJobPrincipalId(JobParameters jobParameters) {
        JobParameter<?> jobParameter = jobParameters.getParameter(PRINCIPAL_ID);

        if (jobParameter == null) {
            return null;
        }

        Object value = jobParameter.value();

        if (value instanceof Number number) {
            return number.longValue();
        }

        return null;
    }

    /**
     * {@code MODE_TYPE} is written as {@code String.valueOf(platformType)}, so it comes back as a name rather than as
     * an enum. A missing or unrecognised value yields null rather than a guessed pool: a wrong pool would be a silent
     * mis-scoping, whereas null lands on the same path a run with no principal already takes.
     */
    private static @Nullable PlatformType readPlatformType(JobParameters jobParameters) {
        JobParameter<?> jobParameter = jobParameters.getParameter(MODE_TYPE);

        if (jobParameter == null) {
            return null;
        }

        Object value = jobParameter.value();

        if (value == null) {
            return null;
        }

        try {
            return PlatformType.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException illegalArgumentException) {
            return null;
        }
    }
}
