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

package com.bytechef.platform.component.owner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ClusterElementContextAware;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * @author Ivica Cardic
 */
class OwnerResolutionTest {

    @Test
    void testNoResolverMeansNoOwner() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        Optional<Owner> owner = OwnerResolution.resolve(actionContextAware, emptyProvider());

        assertEquals(Optional.empty(), owner);
    }

    @Test
    void testJobRunResolvesThroughTheJobPrincipal() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(false);
        when(actionContextAware.getJobPrincipalId()).thenReturn(77L);
        when(actionContextAware.getPlatformType()).thenReturn(PlatformType.EMBEDDED);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveJobPrincipal(77L, PlatformType.EMBEDDED))
            .thenReturn(Optional.of(Owner.connectedUser(1055L)));

        Optional<Owner> owner = OwnerResolution.resolve(actionContextAware, providerOf(ownerResolver));

        assertEquals(Optional.of(Owner.connectedUser(1055L)), owner);
    }

    @Test
    void testEditorRunResolvesFromTheSecurityPrincipal() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(true);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.of(Owner.connectedUser(1055L)));

        Optional<Owner> owner = OwnerResolution.resolve(actionContextAware, providerOf(ownerResolver));

        assertEquals(Optional.of(Owner.connectedUser(1055L)), owner);

        verify(actionContextAware, never()).getJobPrincipalId();
    }

    @Test
    void testEditorRunNeverFallsBackToTheJobPrincipal() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(true);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.empty());

        Optional<Owner> owner = OwnerResolution.resolve(actionContextAware, providerOf(ownerResolver));

        assertEquals(Optional.empty(), owner);

        verify(actionContextAware, never()).getJobPrincipalId();
    }

    /**
     * The branch the data stream delegate exists to feed. A cluster element invoked outside an AI agent has no agent
     * action context, but it may still have been created with the job principal the run belongs to.
     */
    @Test
    void testClusterElementRunResolvesThroughTheJobPrincipal() {
        ClusterElementContextAware clusterElementContextAware = mock(ClusterElementContextAware.class);

        when(clusterElementContextAware.getJobPrincipalId()).thenReturn(77L);
        when(clusterElementContextAware.getPlatformType()).thenReturn(PlatformType.EMBEDDED);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveJobPrincipal(77L, PlatformType.EMBEDDED))
            .thenReturn(Optional.of(Owner.connectedUser(1055L)));

        Optional<Owner> owner = OwnerResolution.resolve(clusterElementContextAware, providerOf(ownerResolver));

        assertEquals(Optional.of(Owner.connectedUser(1055L)), owner);

        verify(ownerResolver, never()).resolveCurrentPrincipal();
    }

    /**
     * A context carrying no principal must behave exactly as it did before that branch existed, since every
     * cluster-element context outside the data stream delegate is still built without one.
     */
    @Test
    void testClusterElementRunWithoutAJobPrincipalStillUsesTheSecurityPrincipal() {
        ClusterElementContextAware clusterElementContextAware = mock(ClusterElementContextAware.class);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.of(Owner.connectedUser(1055L)));

        Optional<Owner> owner = OwnerResolution.resolve(clusterElementContextAware, providerOf(ownerResolver));

        assertEquals(Optional.of(Owner.connectedUser(1055L)), owner);

        verify(ownerResolver, never()).resolveJobPrincipal(anyLong(), any());
    }

    /**
     * An agent tool keeps resolving through the agent's action context even when a principal is also present, because
     * that context additionally knows whether the run is an editor test.
     */
    @Test
    void testTheAgentActionContextStillWinsOverTheJobPrincipal() {
        ActionContextAware agentActionContext = mock(ActionContextAware.class);

        when(agentActionContext.isEditorEnvironment()).thenReturn(true);

        ClusterElementContextAware clusterElementContextAware = mock(ClusterElementContextAware.class);

        when(clusterElementContextAware.getAgentActionContext()).thenReturn(agentActionContext);
        when(clusterElementContextAware.getJobPrincipalId()).thenReturn(77L);
        when(clusterElementContextAware.getPlatformType()).thenReturn(PlatformType.EMBEDDED);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.of(Owner.connectedUser(1055L)));

        Optional<Owner> owner = OwnerResolution.resolve(clusterElementContextAware, providerOf(ownerResolver));

        assertEquals(Optional.of(Owner.connectedUser(1055L)), owner);

        verify(ownerResolver, never()).resolveJobPrincipal(anyLong(), any());
    }

    /**
     * The distinction the whole class turns on. An empty owner means "this caller owns nothing in particular and may
     * see everything", and every consumer acts on it -- so a context that cannot say whose run it is must not produce
     * one. It is not a weaker answer than the right one, it is the widest possible answer.
     *
     * <p>
     * Nothing legitimately resolves an owner from a context this incomplete, so failing here costs nothing today and
     * fires the moment that stops being true.
     */
    @Test
    void testAnUnresolvableNonEditorRunFailsRatherThanAnsweringTheVendor() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(false);
        when(actionContextAware.getJobPrincipalId()).thenReturn(null);
        when(actionContextAware.getPlatformType()).thenReturn(PlatformType.EMBEDDED);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        IllegalStateException illegalStateException = assertThrows(
            IllegalStateException.class, () -> OwnerResolution.resolve(actionContextAware, providerOf(ownerResolver)));

        assertTrue(
            illegalStateException.getMessage()
                .contains("jobPrincipalId=null"),
            illegalStateException.getMessage());

        verify(ownerResolver, never()).resolveJobPrincipal(anyLong(), any());
        verify(ownerResolver, never()).resolveCurrentPrincipal();
    }

    /**
     * The other half of the same branch. A principal with no platform type names a project deployment and an
     * integration instance equally well, so it identifies nothing.
     */
    @Test
    void testANonEditorRunWithoutAPlatformTypeFailsToo() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(false);
        when(actionContextAware.getJobPrincipalId()).thenReturn(77L);
        when(actionContextAware.getPlatformType()).thenReturn(null);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        IllegalStateException illegalStateException = assertThrows(
            IllegalStateException.class, () -> OwnerResolution.resolve(actionContextAware, providerOf(ownerResolver)));

        assertTrue(
            illegalStateException.getMessage()
                .contains("platformType=null"),
            illegalStateException.getMessage());

        verify(ownerResolver, never()).resolveJobPrincipal(anyLong(), any());
    }

    /**
     * The easiest thing to get wrong, and the reason the resolver probe stays first. Community Edition ships no
     * resolver, so EVERY context there is "unresolvable" in the shape above -- and answering empty is correct, because
     * no principal below the tenant exists to be wrong about. "No resolver exists" and "the resolver could not be
     * asked" must not collapse into one another in either direction.
     */
    @Test
    void testCommunityEditionStillResolvesToEmptyForTheVerySameContext() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(false);
        when(actionContextAware.getJobPrincipalId()).thenReturn(null);
        when(actionContextAware.getPlatformType()).thenReturn(null);

        Optional<Owner> owner = OwnerResolution.resolve(actionContextAware, emptyProvider());

        assertEquals(Optional.empty(), owner);
    }

    /**
     * A resolver that looked and found nobody is a real answer -- the principal belongs to no connected user, which is
     * the vendor -- and stays empty rather than becoming the failure above.
     */
    @Test
    void testAResolvedVendorPrincipalStillAnswersEmpty() {
        ActionContextAware actionContextAware = mock(ActionContextAware.class);

        when(actionContextAware.isEditorEnvironment()).thenReturn(false);
        when(actionContextAware.getJobPrincipalId()).thenReturn(77L);
        when(actionContextAware.getPlatformType()).thenReturn(PlatformType.AUTOMATION);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveJobPrincipal(77L, PlatformType.AUTOMATION)).thenReturn(Optional.empty());

        Optional<Owner> owner = OwnerResolution.resolve(actionContextAware, providerOf(ownerResolver));

        assertEquals(Optional.empty(), owner);
    }

    /**
     * A cluster element reaches the action form only through an agent's action context, so it inherits the failure
     * rather than keeping a quieter copy of the old behaviour.
     */
    @Test
    void testAnAgentToolInheritsTheFailureFromItsAgentActionContext() {
        ActionContextAware agentActionContext = mock(ActionContextAware.class);

        when(agentActionContext.isEditorEnvironment()).thenReturn(false);
        when(agentActionContext.getJobPrincipalId()).thenReturn(null);

        ClusterElementContextAware clusterElementContextAware = mock(ClusterElementContextAware.class);

        when(clusterElementContextAware.getAgentActionContext()).thenReturn(agentActionContext);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        assertThrows(
            IllegalStateException.class,
            () -> OwnerResolution.resolve(clusterElementContextAware, providerOf(ownerResolver)));

        verify(ownerResolver, never()).resolveCurrentPrincipal();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OwnerResolver> emptyProvider() {
        ObjectProvider<OwnerResolver> objectProvider = mock(ObjectProvider.class);

        when(objectProvider.getIfAvailable()).thenReturn(null);

        return objectProvider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OwnerResolver> providerOf(OwnerResolver ownerResolver) {
        ObjectProvider<OwnerResolver> objectProvider = mock(ObjectProvider.class);

        when(objectProvider.getIfAvailable()).thenReturn(ownerResolver);

        return objectProvider;
    }
}
