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

package com.bytechef.platform.connection.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.platform.connection.domain.Connection.CredentialStatus;
import com.bytechef.platform.connection.domain.ConnectionStatus;
import com.bytechef.platform.credential.store.CredentialStoreType;
import com.bytechef.platform.security.domain.ResourceVisibility;
import com.bytechef.platform.tag.domain.Tag;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ConnectionDTO#builder(ConnectionDTO)} to the record's component list.
 *
 * <p>
 * That copy constructor sits on the security-critical
 * {@code ConnectedUserConnectionFacadeImpl.createConnectedUserConnection} path -- it is what forces {@code shared} off
 * on a connected user's own connection while carrying everything else across -- and it assigns each builder field by
 * hand. A component added to the record later and forgotten there compiles cleanly and silently drops that field on
 * every round trip.
 *
 * <p>
 * So nothing here is written out per field. {@link #NON_DEFAULT_VALUES} is checked against
 * {@code ConnectionDTO.class.getRecordComponents()} first, which is what makes a newly added component fail this test
 * automatically rather than quietly escaping it; the DTO is then built through the canonical constructor reflectively
 * and compared component by component through {@link RecordComponent#getAccessor()}. Every value is deliberately
 * distinct from its type's default (non-zero, non-false, non-null, and not the builder's own default for {@code status}
 * and {@code visibility}), so a dropped assignment shows up as a difference rather than coinciding with the value the
 * builder would have produced anyway.
 *
 * @author Ivica Cardic
 */
class ConnectionDTOBuilderCopyTest {

    private static final Map<String, Object> NON_DEFAULT_VALUES = buildNonDefaultValues();

    @Test
    void testEveryRecordComponentHasANonDefaultValue() {
        List<String> componentNames = Arrays.stream(ConnectionDTO.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();

        assertThat(NON_DEFAULT_VALUES.keySet())
            .as(
                "NON_DEFAULT_VALUES must name every ConnectionDTO record component -- add the new one here and " +
                    "check ConnectionDTO.builder(ConnectionDTO) copies it")
            .containsExactlyInAnyOrderElementsOf(componentNames);
    }

    @Test
    void testBuilderCopyRoundTripsEveryRecordComponent() throws Exception {
        ConnectionDTO connectionDTO = newFullyPopulatedConnectionDTO();

        ConnectionDTO copiedConnectionDTO = ConnectionDTO.builder(connectionDTO)
            .build();

        for (RecordComponent recordComponent : ConnectionDTO.class.getRecordComponents()) {
            Object originalValue = recordComponent.getAccessor()
                .invoke(connectionDTO);
            Object copiedValue = recordComponent.getAccessor()
                .invoke(copiedConnectionDTO);

            assertThat(copiedValue)
                .as(
                    "ConnectionDTO.builder(ConnectionDTO) dropped component '%s' -- add the missing assignment",
                    recordComponent.getName())
                .isEqualTo(originalValue);
        }
    }

    private static ConnectionDTO newFullyPopulatedConnectionDTO() throws Exception {
        RecordComponent[] recordComponents = ConnectionDTO.class.getRecordComponents();

        Class<?>[] parameterTypes = Arrays.stream(recordComponents)
            .map(RecordComponent::getType)
            .toArray(Class<?>[]::new);

        Object[] arguments = Arrays.stream(recordComponents)
            .map(recordComponent -> NON_DEFAULT_VALUES.get(recordComponent.getName()))
            .toArray();

        Constructor<ConnectionDTO> constructor = ConnectionDTO.class.getDeclaredConstructor(parameterTypes);

        return constructor.newInstance(arguments);
    }

    private static Map<String, Object> buildNonDefaultValues() {
        Map<String, Object> values = new LinkedHashMap<>();

        values.put("active", true);
        values.put("authorizationType", AuthorizationType.BEARER_TOKEN);
        values.put("authorizationParameters", Map.of("token", "authorization-parameter"));
        values.put("baseUri", "https://base.uri");
        values.put("componentName", "componentName");
        values.put("connectionParameters", Map.of("connectionKey", "connection-parameter"));
        values.put("connectionVersion", 3);
        values.put("createdBy", "createdBy");
        values.put("createdDate", Instant.ofEpochMilli(1_000));
        values.put("credentialStatus", CredentialStatus.VALID);
        values.put("credentialStoreType", CredentialStoreType.HASHICORP_VAULT);
        values.put("environmentId", 7);
        values.put("id", 11L);
        values.put("lastModifiedBy", "lastModifiedBy");
        values.put("lastModifiedDate", Instant.ofEpochMilli(2_000));
        values.put("name", "name");
        values.put("parameters", Map.of("parameterKey", "parameter"));
        values.put("status", ConnectionStatus.REVOKED);
        values.put("tags", List.of(new Tag(13L, "tag")));
        values.put("version", 5);
        values.put("visibility", ResourceVisibility.ORGANIZATION);
        values.put("shared", true);
        values.put("managed", true);

        return values;
    }
}
