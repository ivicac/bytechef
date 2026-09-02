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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit test for {@link SchemaGenerator}.
 *
 * @author Ivica Cardic
 */
class SchemaGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SchemaGenerator schemaGenerator = new SchemaGenerator(objectMapper);

    @Test
    void testGenerateSchemaFromArrayOfObjectsDescribesItemsAsObject() {
        JsonNode schema = generate("""
            {"users": [{"name": "Ana", "active": true}]}
            """);

        JsonNode items = schema.path("properties")
            .path("users")
            .path("items");

        assertThat(items.path("type")
            .asString()).isEqualTo("object");
        assertThat(items.path("properties")
            .path("name")
            .path("type")
            .asString()).isEqualTo("string");
        assertThat(items.path("properties")
            .path("active")
            .path("type")
            .asString()).isEqualTo("boolean");
    }

    @Test
    void testGenerateSchemaFromDecimalNumberUsesNumberType() {
        JsonNode schema = generate("""
            {"price": 3.14}
            """);

        assertThat(propertyType(schema, "price")).isEqualTo("number");
    }

    @Test
    void testGenerateSchemaFromLongUsesIntegerType() {
        JsonNode schema = generate("""
            {"views": 9999999999}
            """);

        assertThat(propertyType(schema, "views")).isEqualTo("integer");
    }

    @Test
    void testGenerateSchemaFromArrayOfStringsDescribesItemsAsString() {
        JsonNode schema = generate("""
            {"tags": ["urgent", "billing"]}
            """);

        JsonNode items = schema.path("properties")
            .path("tags")
            .path("items");

        assertThat(items.path("type")
            .asString()).isEqualTo("string");
    }

    @Test
    void testGenerateSchemaFromFlatObjectDescribesEachScalarProperty() {
        JsonNode schema = generate("""
            {"name": "Ana", "age": 42, "active": true}
            """);

        assertThat(schema.path("$schema")
            .asString()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.path("type")
            .asString()).isEqualTo("object");
        assertThat(propertyType(schema, "name")).isEqualTo("string");
        assertThat(propertyType(schema, "age")).isEqualTo("integer");
        assertThat(propertyType(schema, "active")).isEqualTo("boolean");
    }

    @Test
    void testGenerateSchemaFromNestedObjectNestsProperties() {
        JsonNode schema = generate("""
            {"customer": {"email": "ana@example.com"}}
            """);

        JsonNode customer = schema.path("properties")
            .path("customer");

        assertThat(customer.path("type")
            .asString()).isEqualTo("object");
        assertThat(customer.path("properties")
            .path("email")
            .path("type")
            .asString()).isEqualTo("string");
    }

    @Test
    void testGenerateSchemaFromTopLevelArrayDescribesRootAsArray() {
        JsonNode schema = generate("""
            [{"id": 1}]
            """);

        assertThat(schema.path("type")
            .asString()).isEqualTo("array");

        JsonNode items = schema.path("items");

        assertThat(items.path("type")
            .asString()).isEqualTo("object");
        assertThat(items.path("properties")
            .path("id")
            .path("type")
            .asString()).isEqualTo("integer");
    }

    @Test
    void testGenerateSchemaFromEmptyArrayOmitsItems() {
        JsonNode schema = generate("""
            {"tags": []}
            """);

        JsonNode tags = schema.path("properties")
            .path("tags");

        assertThat(tags.path("type")
            .asString()).isEqualTo("array");
        assertThat(tags.has("items")).isFalse();
    }

    @Test
    void testGenerateSchemaFromNullFallsBackToString() {
        JsonNode schema = generate("""
            {"note": null}
            """);

        assertThat(propertyType(schema, "note")).isEqualTo("string");
    }

    @Test
    void testGenerateSchemaFromNestedArrayOfObjectsDescribesItemsAsObject() {
        JsonNode schema = generate("""
            {"order": {"lines": [{"sku": "A-1", "qty": 2}]}}
            """);

        JsonNode items = schema.path("properties")
            .path("order")
            .path("properties")
            .path("lines")
            .path("items");

        assertThat(items.path("type")
            .asString()).isEqualTo("object");
        assertThat(items.path("properties")
            .path("sku")
            .path("type")
            .asString()).isEqualTo("string");
        assertThat(items.path("properties")
            .path("qty")
            .path("type")
            .asString()).isEqualTo("integer");
    }

    private String propertyType(JsonNode schema, String propertyName) {
        return schema.path("properties")
            .path(propertyName)
            .path("type")
            .asString();
    }

    private JsonNode generate(String json) {
        return objectMapper.readTree(schemaGenerator.generateSchemaFromJson(json));
    }
}
