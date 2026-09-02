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

package com.bytechef.platform.configuration.web.rest.schema;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * @author Ivica Cardic
 */
@Component
public class SchemaGenerator {

    private static final String SCHEMA_URI = "https://json-schema.org/draft/2020-12/schema";

    private final ObjectMapper objectMapper;

    @SuppressFBWarnings("EI")
    public SchemaGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generateSchemaFromJson(String json) {
        try {
            JsonNode jsonNode = objectMapper.readTree(json);

            ObjectNode schemaNode = objectMapper.createObjectNode();

            schemaNode.put("$schema", SCHEMA_URI);

            for (Map.Entry<String, JsonNode> field : generateNode(jsonNode).properties()) {
                schemaNode.set(field.getKey(), field.getValue());
            }

            return objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(schemaNode);
        } catch (Exception e) {
            throw new RuntimeException("Could not generate JSON schema", e);
        }
    }

    private ObjectNode generateNode(JsonNode jsonNode) {
        ObjectNode node = objectMapper.createObjectNode();

        if (jsonNode.isObject()) {
            node.put("type", "object");

            ObjectNode propertiesNode = node.putObject("properties");

            for (Map.Entry<String, JsonNode> field : jsonNode.properties()) {
                propertiesNode.set(field.getKey(), generateNode(field.getValue()));
            }
        } else if (jsonNode.isArray()) {
            node.put("type", "array");

            if (!jsonNode.isEmpty()) {
                node.set("items", generateNode(jsonNode.get(0)));
            }
        } else {
            node.put("type", getScalarType(jsonNode));
        }

        return node;
    }

    /**
     * A JSON null carries no type information, so it falls through to string, the least surprising default for a
     * sample-derived schema.
     */
    private static String getScalarType(JsonNode jsonNode) {
        if (jsonNode.isBoolean()) {
            return "boolean";
        }

        if (jsonNode.isIntegralNumber()) {
            return "integer";
        }

        if (jsonNode.isNumber()) {
            return "number";
        }

        return "string";
    }
}
