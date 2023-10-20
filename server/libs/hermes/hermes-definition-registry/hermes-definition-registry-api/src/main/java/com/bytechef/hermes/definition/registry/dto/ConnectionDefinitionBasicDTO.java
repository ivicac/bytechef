
/*
 * Copyright 2021 <your company/name>.
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

package com.bytechef.hermes.definition.registry.dto;

import com.bytechef.commons.util.OptionalUtils;
import com.bytechef.hermes.component.definition.ComponentDefinition;
import com.bytechef.hermes.component.definition.ConnectionDefinition;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Optional;

/**
 * @author Ivica Cardic
 */
@SuppressFBWarnings("EI")
public record ConnectionDefinitionBasicDTO(
    boolean authorizationRequired, Optional<String> componentDescription, String componentName, String componentTitle,
    int version) {

    public ConnectionDefinitionBasicDTO(
        ConnectionDefinition connectionDefinition, ComponentDefinition componentDefinition) {

        this(
            OptionalUtils.orElse(connectionDefinition.getAuthorizationRequired(), true),
            componentDefinition.getDescription(), componentDefinition.getName(),
            ComponentDefinitionDTO.getTitle(
                componentDefinition.getName(), OptionalUtils.orElse(componentDefinition.getTitle(), null)),
            connectionDefinition.getVersion());
    }
}
