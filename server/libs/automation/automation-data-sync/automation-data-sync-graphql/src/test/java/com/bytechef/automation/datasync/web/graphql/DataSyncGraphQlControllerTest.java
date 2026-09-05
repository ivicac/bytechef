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

package com.bytechef.automation.datasync.web.graphql;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.datasync.domain.DataSync;
import com.bytechef.automation.datasync.domain.DataSync.TriggerType;
import com.bytechef.automation.datasync.domain.DataSyncElement;
import com.bytechef.automation.datasync.domain.DataSyncElement.Kind;
import com.bytechef.automation.datasync.dto.DataSyncDTO;
import com.bytechef.automation.datasync.facade.DataSyncFacade;
import com.bytechef.automation.datasync.web.graphql.config.DataSyncGraphQlConfigurationSharedMocks;
import com.bytechef.automation.datasync.web.graphql.config.DataSyncGraphQlTestConfiguration;
import com.bytechef.platform.security.domain.ResourceVisibility;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ContextConfiguration;

@ContextConfiguration(classes = {
    DataSyncGraphQlTestConfiguration.class,
    DataSyncGraphQlController.class
})
@GraphQlTest(
    controllers = DataSyncGraphQlController.class,
    properties = "spring.graphql.schema.locations=classpath*:/graphql/")
@DataSyncGraphQlConfigurationSharedMocks
class DataSyncGraphQlControllerTest {

    @Autowired
    private DataSyncFacade dataSyncFacade;

    @Autowired
    private GraphQlTester graphQlTester;

    @Test
    void testDataSyncsReturnsMappedRows() {
        DataSync dataSync = new DataSync();

        dataSync.setId(1L);
        dataSync.setName("crm-to-db");
        dataSync.setTitle("CRM to DB");
        dataSync.setWorkspaceId(10L);
        dataSync.setProjectId(100L);
        dataSync.setUuid(UUID.randomUUID());
        dataSync.setTriggerType(TriggerType.SCHEDULE);
        dataSync.setTriggerParameters(Map.of("expression", "0 9 * * ?"));

        DataSyncElement source = new DataSyncElement(1L, Kind.SOURCE);

        source.setId(5L);
        source.setComponentName("csvFile");
        source.setComponentVersion(1);
        source.setOperationName("read");

        when(dataSyncFacade.getDataSyncs(10L)).thenReturn(List.of(new DataSyncDTO(
            dataSync, List.of(source), true, 0, null, List.of(), ResourceVisibility.WORKSPACE, "wf-1")));

        graphQlTester.document("""
            query {
                dataSyncs(workspaceId: "10") {
                    id
                    name
                    triggerType
                    triggerParameters
                    draftWorkflowId
                    elements { id kind componentName componentVersion operationName }
                }
            }
            """)
            .execute()
            .path("dataSyncs[0].name")
            .entity(String.class)
            .isEqualTo("crm-to-db")
            .path("dataSyncs[0].triggerType")
            .entity(String.class)
            .isEqualTo("SCHEDULE")
            .path("dataSyncs[0].draftWorkflowId")
            .entity(String.class)
            .isEqualTo("wf-1")
            .path("dataSyncs[0].elements[0].kind")
            .entity(String.class)
            .isEqualTo("SOURCE");
    }

    @Test
    void testSetDataSyncElementPassesEveryArgument() {
        DataSyncElement element = new DataSyncElement(1L, Kind.DESTINATION);

        element.setId(9L);
        element.setComponentName("postgresql");
        element.setComponentVersion(1);
        element.setOperationName("insert");

        when(dataSyncFacade.setDataSyncElement(
            eq(1L), eq(Kind.DESTINATION), eq("postgresql"), eq(1), eq("insert"), eq(Map.of("table", "t")), eq(3L)))
                .thenReturn(element);

        graphQlTester.document("""
            mutation {
                setDataSyncElement(input: {
                    dataSyncId: "1", kind: DESTINATION, componentName: "postgresql", componentVersion: 1,
                    operationName: "insert", parameters: {table: "t"}, connectionId: "3"
                }) { id kind }
            }
            """)
            .execute()
            .path("setDataSyncElement.id")
            .entity(String.class)
            .isEqualTo("9");

        verify(dataSyncFacade).setDataSyncElement(1L, Kind.DESTINATION, "postgresql", 1, "insert", Map.of("table", "t"),
            3L);
    }

    @Test
    void testRunDataSyncDeploymentReturnsJobId() {
        when(dataSyncFacade.runDataSyncDeployment(1L, 20L)).thenReturn(77L);

        graphQlTester.document("""
            mutation { runDataSyncDeployment(id: "1", projectDeploymentId: "20") }
            """)
            .execute()
            .path("runDataSyncDeployment")
            .entity(String.class)
            .isEqualTo("77");
    }
}
