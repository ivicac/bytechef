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

package com.bytechef.automation.configuration.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Data tables and knowledge bases are the vendor's infrastructure: visible in the vendor's own automation palette,
 * hidden from a connected user building a workflow of their own.
 *
 * @author Ivica Cardic
 */
class ProjectComponentDefinitionFilterTest {

    private static final List<String> BROWSED = List.of(
        "dataTable", "knowledgeBase", "slack", "appEvent", "embeddedWorkflowBuilder", "request");

    @Test
    void testAConnectedUserIsNotOfferedDataTablesOrKnowledgeBases() {
        assertThat(offeredTo(Owner.connectedUser(42L)))
            .containsExactly("slack");
    }

    @Test
    void testTheVendorKeepsThem() {
        // The resolver is present -- this is an EE deployment -- and answers "not a connected user".
        assertThat(offeredTo(null))
            .containsExactly("dataTable", "knowledgeBase", "slack");
    }

    /**
     * Community Edition registers no {@link OwnerResolver} at all, so nothing may depend on one being there.
     */
    @Test
    void testCommunityEditionKeepsThemWithNoResolverAtAll() {
        ObjectProvider<OwnerResolver> ownerResolverProvider = provider(null);

        ProjectComponentDefinitionFilter filter = new ProjectComponentDefinitionFilter(ownerResolverProvider);

        assertThat(names(filter.filter(componentDefinitions())))
            .containsExactly("dataTable", "knowledgeBase", "slack");
    }

    /**
     * The reason the SPI takes a list. {@code resolveCurrentPrincipal} is a database lookup, and a per-component filter
     * would run it once per component -- several hundred times for a real palette. Asserting "exactly once for six
     * components" is what makes that regression impossible to reintroduce silently; asserting merely that it was called
     * would pass at any count.
     */
    @Test
    void testTheCallerIsResolvedOncePerListingRatherThanOncePerComponent() {
        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.of(Owner.connectedUser(42L)));

        ProjectComponentDefinitionFilter filter = new ProjectComponentDefinitionFilter(provider(ownerResolver));

        filter.filter(componentDefinitions());

        verify(ownerResolver, times(1)).resolveCurrentPrincipal();
    }

    @Test
    void testCommunityEditionNeverAsksForAResolver() {
        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        ProjectComponentDefinitionFilter filter = new ProjectComponentDefinitionFilter(provider(null));

        filter.filter(componentDefinitions());

        verify(ownerResolver, never()).resolveCurrentPrincipal();
    }

    /**
     * Only one filter runs per platform type -- {@code ComponentDefinitionServiceImpl} selects with {@code findFirst()}
     * -- so this one must keep claiming AUTOMATION and only AUTOMATION.
     */
    @Test
    void testTheFilterSupportsAutomationOnly() {
        ProjectComponentDefinitionFilter filter = new ProjectComponentDefinitionFilter(provider(null));

        assertThat(filter.supports(PlatformType.AUTOMATION)).isTrue();
        assertThat(filter.supports(PlatformType.EMBEDDED)).isFalse();
    }

    private static List<String> offeredTo(@Nullable Owner owner) {
        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.ofNullable(owner));

        ProjectComponentDefinitionFilter filter = new ProjectComponentDefinitionFilter(provider(ownerResolver));

        return names(filter.filter(componentDefinitions()));
    }

    private static List<ComponentDefinition> componentDefinitions() {
        return BROWSED.stream()
            .map(ProjectComponentDefinitionFilterTest::componentDefinition)
            .toList();
    }

    private static List<String> names(List<ComponentDefinition> componentDefinitions) {
        return componentDefinitions.stream()
            .map(ComponentDefinition::getName)
            .toList();
    }

    private static ComponentDefinition componentDefinition(String name) {
        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getName()).thenReturn(name);

        return componentDefinition;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OwnerResolver> provider(@Nullable OwnerResolver ownerResolver) {
        ObjectProvider<OwnerResolver> ownerResolverProvider = mock(ObjectProvider.class);

        when(ownerResolverProvider.getIfAvailable()).thenReturn(ownerResolver);

        return ownerResolverProvider;
    }
}
