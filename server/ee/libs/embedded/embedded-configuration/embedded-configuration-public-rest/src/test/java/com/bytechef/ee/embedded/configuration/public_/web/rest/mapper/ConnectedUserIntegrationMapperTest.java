/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.public_.web.rest.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserIntegrationDTO;
import com.bytechef.ee.embedded.configuration.dto.IntegrationInstanceConfigurationWorkflowDTO;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.InputModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.InputTypeModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationInstanceModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationInstanceWorkflowModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationWorkflowModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.McpIntegrationInstanceToolModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.McpToolModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.OAuth2Model;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class ConnectedUserIntegrationMapperTest {

    private final ConnectedUserIntegrationMapper.ConnectedUserIntegrationToIntegrationMapper mapper =
        new ConnectedUserIntegrationMapper.ConnectedUserIntegrationToIntegrationMapper() {

            @Override
            public IntegrationModel convert(ConnectedUserIntegrationDTO source) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OAuth2Model map(ConnectedUserIntegrationDTO.OAuth2 oAuth2) {
                throw new UnsupportedOperationException();
            }

            @Override
            public IntegrationWorkflowModel map(
                IntegrationInstanceConfigurationWorkflowDTO integrationInstanceConfigurationWorkflowDTO) {

                throw new UnsupportedOperationException();
            }

            @Override
            public McpToolModel map(ConnectedUserIntegrationDTO.McpToolInfo mcpToolInfo) {
                throw new UnsupportedOperationException();
            }

            @Override
            public IntegrationWorkflowModel map(ConnectedUserIntegrationDTO.McpWorkflowInfo mcpWorkflowInfo) {
                throw new UnsupportedOperationException();
            }

            @Override
            public McpIntegrationInstanceToolModel map(
                ConnectedUserIntegrationDTO.McpInstanceToolInfo mcpInstanceToolInfo) {

                throw new UnsupportedOperationException();
            }

            @Override
            public IntegrationInstanceModel map(
                ConnectedUserIntegrationDTO.ConnectedUserIntegrationInstance integrationInstance) {

                throw new UnsupportedOperationException();
            }

            @Override
            public IntegrationInstanceWorkflowModel map(
                ConnectedUserIntegrationDTO.ConnectedUserIntegrationInstanceWorkflow integrationInstanceWorkflow) {

                throw new UnsupportedOperationException();
            }
        };

    @Test
    void testFieldMappingInputMapsTypeAndObjectName() {
        Workflow.Input input = new Workflow.Input(
            "contactMapping", "Contact Mapping", "field_mapping", false, null, "Contacts");

        InputModel model = mapper.map(input);

        assertEquals(InputTypeModel.FIELD_MAPPING, model.getType());
        assertEquals("Contacts", model.getObjectName());
    }
}
