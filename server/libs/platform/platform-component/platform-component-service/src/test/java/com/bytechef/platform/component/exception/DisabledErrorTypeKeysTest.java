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

package com.bytechef.platform.component.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.ClusterElementDefinition;
import org.junit.jupiter.api.Test;

/**
 * Pins the numeric keys of the "disabled" error types. The App REST controllers ({@code AbstractAppApiController})
 * cannot depend on this module, so they answer these errors with 403 by matching the entity class and the literal key;
 * renumbering a constant here must fail a test instead of silently turning those 403s into 422s.
 *
 * @author Ivica Cardic
 */
class DisabledErrorTypeKeysTest {

    @Test
    void testActionDefinitionDisabledKeys() {
        assertThat(ActionDefinitionErrorType.COMPONENT_DISABLED.getErrorKey()).isEqualTo(106);
        assertThat(ActionDefinitionErrorType.ACTION_DISABLED.getErrorKey()).isEqualTo(107);
        assertThat(ActionDefinitionErrorType.COMPONENT_DISABLED.getErrorClass()).isEqualTo(ActionDefinition.class);
        assertThat(ActionDefinitionErrorType.ACTION_DISABLED.getErrorClass()).isEqualTo(ActionDefinition.class);
    }

    @Test
    void testClusterElementDefinitionDisabledKeys() {
        assertThat(ClusterElementDefinitionErrorType.COMPONENT_DISABLED.getErrorKey()).isEqualTo(107);
        assertThat(ClusterElementDefinitionErrorType.ACTION_DISABLED.getErrorKey()).isEqualTo(108);
        assertThat(ClusterElementDefinitionErrorType.COMPONENT_DISABLED.getErrorClass())
            .isEqualTo(ClusterElementDefinition.class);
        assertThat(ClusterElementDefinitionErrorType.ACTION_DISABLED.getErrorClass())
            .isEqualTo(ClusterElementDefinition.class);
    }
}
