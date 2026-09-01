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

import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.filter.ComponentDefinitionFilter;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.owner.OwnerResolver;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The automation palette.
 *
 * @author Ivica Cardic
 */
@Component
public class ProjectComponentDefinitionFilter implements ComponentDefinitionFilter {

    /**
     * Hidden from every automation palette. These are the embedded surface's own components, and
     * {@code IntegrationComponentDefinitionFilter} is the mirror of this list -- each filter hides the other surface's
     * exclusives.
     */
    private static final List<String> COMPONENT_NAMES = List.of("appEvent", "embeddedWorkflowBuilder", "request");

    /**
     * Hidden additionally from a connected user's own palette. Data tables and knowledge bases are the vendor's
     * infrastructure: the vendor reaches them from integration workflows and from its own automation projects, while a
     * connected user building a workflow of their own has no use for them and no console in which to manage one.
     */
    private static final List<String> VENDOR_ONLY_COMPONENT_NAMES = List.of("dataTable", "knowledgeBase");

    private final ObjectProvider<OwnerResolver> ownerResolverProvider;

    public ProjectComponentDefinitionFilter(ObjectProvider<OwnerResolver> ownerResolverProvider) {
        this.ownerResolverProvider = ownerResolverProvider;
    }

    /**
     * Keyed on who is browsing rather than on which project is open.
     *
     * <p>
     * A bridged connected-user project and the vendor's own project are both {@code AUTOMATION} and are
     * indistinguishable from a {@link ComponentDefinition}, so the project cannot answer this. The caller can: a
     * connected user in the editor is the security principal, and {@code resolveCurrentPrincipal} is exactly the
     * question "is this a connected user".
     *
     * <p>
     * Resolved ONCE for the whole listing. It is a database lookup, and a per-component form would run it several
     * hundred times per palette load. That is why the SPI takes the list.
     *
     * <p>
     * Community Edition has no {@link OwnerResolver} at all, so nothing extra is hidden there; an empty owner is the
     * vendor, who keeps both.
     */
    @Override
    public List<ComponentDefinition> filter(List<ComponentDefinition> componentDefinitions) {
        boolean connectedUser = isConnectedUser();

        return componentDefinitions.stream()
            .filter(componentDefinition -> isVisible(componentDefinition, connectedUser))
            .toList();
    }

    @Override
    public boolean supports(PlatformType type) {
        return PlatformType.AUTOMATION.equals(type);
    }

    private boolean isConnectedUser() {
        OwnerResolver ownerResolver = ownerResolverProvider.getIfAvailable();

        if (ownerResolver == null) {
            return false;
        }

        return ownerResolver.resolveCurrentPrincipal()
            .isPresent();
    }

    private static boolean isVisible(ComponentDefinition componentDefinition, boolean connectedUser) {
        String name = componentDefinition.getName();

        if (COMPONENT_NAMES.contains(name)) {
            return false;
        }

        return !connectedUser || !VENDOR_ONLY_COMPONENT_NAMES.contains(name);
    }
}
