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

package com.bytechef.platform.component.filter;

import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.constant.PlatformType;
import java.util.List;

/**
 * Which components a palette shows, for one {@link PlatformType}.
 *
 * @author Ivica Cardic
 */
public interface ComponentDefinitionFilter {

    /**
     * Filters a whole listing at once rather than one component at a time.
     *
     * <p>
     * The list form is what lets a filter depend on something expensive to establish. A per-component form invites a
     * filter to resolve the caller once per component, which for a palette is several hundred lookups per request; this
     * signature makes resolving once the natural way to write it rather than an optimisation to remember.
     *
     * @param componentDefinitions every component the caller could otherwise see
     * @return those the caller may see, in the order given
     */
    List<ComponentDefinition> filter(List<ComponentDefinition> componentDefinitions);

    /**
     * Only ONE filter is used per platform type -- the consumer takes the first that supports it. Two filters claiming
     * the same type means one of them silently never runs, so a new rule extends the existing filter for that type
     * rather than joining it.
     *
     * @param type the platform whose palette is being listed
     * @return whether this filter is the one for that platform
     */
    boolean supports(PlatformType type);
}
