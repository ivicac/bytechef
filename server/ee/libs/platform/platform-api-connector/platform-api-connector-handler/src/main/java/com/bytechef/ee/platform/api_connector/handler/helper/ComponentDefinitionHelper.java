/*
 * Copyright 2023-present ByteChef Inc.
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

package com.bytechef.ee.platform.api_connector.handler.helper;

import com.bytechef.commons.util.OptionalUtils;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.ComponentDSL;
import com.bytechef.component.definition.Property;
import com.bytechef.ee.platform.api_connector.configuration.domain.ApiConnector;
import com.bytechef.platform.api_connector.file.storage.ApiConnectorFileStorage;
import com.bytechef.platform.component.definition.ActionDefinitionWrapper;
import com.bytechef.platform.component.definition.ComponentDefinitionWrapper;
import com.bytechef.platform.component.util.OpenAPIClientUtils;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * @author Ivica Cardic
 */
@Component
public class ComponentDefinitionHelper {

    private static final Function<ActionDefinition, ActionDefinition.SingleConnectionPerformFunction> PERFORM_FUNCTION_FUNCTION =
        actionDefinition -> (inputParameters, connectionParameters, context) -> OpenAPIClientUtils.execute(
            inputParameters, OptionalUtils.orElse(actionDefinition.getProperties(), List.of()),
            OptionalUtils.orElse(actionDefinition.getOutputResponse(), null),
            OptionalUtils.orElse(actionDefinition.getMetadata(), Map.of()),
            OptionalUtils.orElse(actionDefinition.getProcessErrorResponse(), null), context);

    private final ApiConnectorFileStorage apiConnectorFileStorage;
    private final ObjectMapper objectMapper;

    public ComponentDefinitionHelper(
        ApiConnectorFileStorage apiConnectorFileStorage, ObjectMapper objectMapper) {

        this.apiConnectorFileStorage = apiConnectorFileStorage;
        this.objectMapper = objectMapper.copy()
            .addMixIn(Property.class, PropertyMixIn.class);
    }

    public ComponentDefinitionWrapper readComponentDefinition(ApiConnector apiConnector) {
        ComponentDSL.ModifiableComponentDefinition componentDefinition;

        try {
            componentDefinition = objectMapper.readValue(
                apiConnectorFileStorage.readApiConnectorDefinition(apiConnector.getDefinition()),
                ComponentDSL.ModifiableComponentDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }

        return new ComponentDefinitionWrapper(
            componentDefinition,
            componentDefinition.getActions()
                .map(actionDefinitions -> actionDefinitions.stream()
                    .map(actionDefinition -> (ActionDefinition) new ActionDefinitionWrapper(
                        actionDefinition, PERFORM_FUNCTION_FUNCTION.apply(actionDefinition)))
                    .toList())
                .orElse(List.of()));
    }

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableArrayProperty.class, name = "ARRAY"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableBooleanProperty.class, name = "BOOLEAN"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableDateProperty.class, name = "DATE"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableDateTimeProperty.class, name = "DATE_TIME"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableDynamicPropertiesProperty.class, name = "DYNAMIC_PROPERTIES"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableFileEntryProperty.class, name = "FILE_ENTRY"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableIntegerProperty.class, name = "INTEGER"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableNumberProperty.class, name = "NUMBER"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableNullProperty.class, name = "NULL"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableObjectProperty.class, name = "OBJECT"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableStringProperty.class, name = "STRING"),
        @JsonSubTypes.Type(value = ComponentDSL.ModifiableTimeProperty.class, name = "TIME"),
    })
    @SuppressFBWarnings("NM_SAME_SIMPLE_NAME_AS_SUPERCLASS")
    public abstract static class PropertyMixIn {
    }
}
