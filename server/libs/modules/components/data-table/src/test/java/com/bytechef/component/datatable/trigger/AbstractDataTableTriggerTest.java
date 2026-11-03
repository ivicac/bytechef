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

package com.bytechef.component.datatable.trigger;

import static com.bytechef.component.datatable.constant.DataTableConstants.TABLE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bytechef.component.definition.ComponentDsl.ModifiableTriggerDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TriggerContext;
import com.bytechef.component.definition.TriggerDefinition.WebhookBody;
import com.bytechef.component.definition.TriggerDefinition.WebhookEnableFunction;
import com.bytechef.component.definition.TriggerDefinition.WebhookEnableOutput;
import com.bytechef.component.definition.TriggerDefinition.WebhookRequestFunction;
import com.bytechef.component.definition.TypeReference;
import com.bytechef.platform.component.definition.TriggerContextAware;
import com.bytechef.platform.data.table.configuration.domain.DataTableInfo;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhookType;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import com.bytechef.platform.data.table.configuration.service.DataTableWebhookService;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.DataTableResolution;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Ivica Cardic
 */
abstract class AbstractDataTableTriggerTest {

    protected static final long DATA_TABLE_ID = 11;
    protected static final String WEBHOOK_URL = "http://localhost/webhooks/workflow-execution-id";
    protected static final String WORKFLOW_EXECUTION_ID = "workflow-execution-id";

    protected static long webhookEnable(
        ModifiableTriggerDefinition triggerDefinition, DataTableService dataTableService,
        DataTableWebhookService dataTableWebhookService, DataTableWebhookType dataTableWebhookType) throws Exception {

        DataTableRef dataTableRef = new DataTableRef("conversations", 1L);

        Parameters inputParameters = mock(Parameters.class);
        TriggerContext triggerContext = mock(
            TriggerContext.class, withSettings().extraInterfaces(TriggerContextAware.class));

        when(inputParameters.getRequiredString(TABLE)).thenReturn("conversations");
        when(((TriggerContextAware) triggerContext).getEnvironmentId()).thenReturn(1L);
        when(dataTableService.fetchDataTableResolution("conversations", 1L))
            .thenReturn(Optional.of(new DataTableResolution(DATA_TABLE_ID, dataTableRef)));
        when(dataTableService.listTables(1L))
            .thenReturn(List.of(new DataTableInfo(DATA_TABLE_ID, "conversations", null, List.of(), Instant.EPOCH)));
        when(dataTableWebhookService.addWebhook(dataTableRef, WORKFLOW_EXECUTION_ID, WEBHOOK_URL, dataTableWebhookType))
            .thenReturn(42L);

        WebhookEnableFunction webhookEnableFunction = triggerDefinition.getWebhookEnable()
            .orElseThrow();

        WebhookEnableOutput webhookEnableOutput = webhookEnableFunction.apply(
            inputParameters, null, WEBHOOK_URL, WORKFLOW_EXECUTION_ID, triggerContext);

        return (Long) webhookEnableOutput.parameters()
            .get("webhookId");
    }

    protected static Map<String, Object> rowContent(String type, Map<String, Object> values) {
        return Map.of("type", type, "table", "conversations", "payload", Map.of("id", 7, "values", values));
    }

    protected static Object webhookRequest(
        ModifiableTriggerDefinition triggerDefinition, Map<String, Object> content) throws Exception {

        WebhookRequestFunction webhookRequestFunction = triggerDefinition.getWebhookRequest()
            .orElseThrow();

        return webhookRequestFunction.apply(null, null, null, null, new TestWebhookBody(content), null, null, null);
    }

    @SuppressFBWarnings("EI")
    private record TestWebhookBody(Map<String, Object> content) implements WebhookBody {

        @Override
        public Object getContent() {
            return content;
        }

        @Override
        public <T> T getContent(Class<T> valueType) {
            return valueType.cast(content);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getContent(TypeReference<T> valueTypeRef) {
            return (T) content;
        }

        @Override
        public ContentType getContentType() {
            return ContentType.JSON;
        }

        @Override
        public String getMimeType() {
            return "application/json";
        }

        @Override
        public String getRawContent() {
            return String.valueOf(content);
        }
    }
}
