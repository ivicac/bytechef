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

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ClusterElementContextAware;
import com.bytechef.platform.component.definition.TriggerContextAware;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The one place a component derives the {@link Owner} it is acting for. Shared rather than duplicated, because the
 * editor branch below is the subtle part and two copies of it would not stay in step.
 *
 * @author Ivica Cardic
 */
public final class OwnerResolution {

    private OwnerResolution() {
    }

    /**
     * Empty carries one meaning only: the caller owns nothing in particular and may see everything -- Community
     * Edition, where no principal below the tenant exists, or a principal the resolver looked up and found belongs to
     * no connected user. It never means "could not tell": a context that cannot identify the run it belongs to fails
     * instead, because every consumer reads empty as the vendor and would open both pools on it.
     *
     * @return the owner this invocation belongs to, or empty per above
     * @throws IllegalStateException when the context is neither an editor run nor able to name its job principal, so
     *                               there is nothing to ask the resolver about
     */
    public static Optional<Owner> resolve(
        ActionContextAware actionContextAware, ObjectProvider<OwnerResolver> ownerResolverProvider) {

        OwnerResolver ownerResolver = ownerResolverProvider.getIfAvailable();

        if (ownerResolver == null) {
            return Optional.empty();
        }

        // An editor test run has no persisted job and therefore no job principal. Falling through to the job branch
        // would read a null principal and answer "no owner" while a connected user drives the Test button, so the two
        // branches are exclusive rather than one being a fallback for the other.
        if (actionContextAware.isEditorEnvironment()) {
            return ownerResolver.resolveCurrentPrincipal();
        }

        Long jobPrincipalId = actionContextAware.getJobPrincipalId();
        PlatformType platformType = actionContextAware.getPlatformType();

        // Unresolvable, not the vendor: an empty owner here would say "sees everything" about a context that cannot
        // say whose run it is.
        if (jobPrincipalId == null || platformType == null) {
            throw new IllegalStateException(
                ("Cannot resolve the owner of this run: the action context is not an editor run and does not identify "
                    + "its job principal (jobPrincipalId=%s, platformType=%s). Answering \"no owner\" here would "
                    + "read as the vendor and open every account's data, so an owner-scoped resource must not be "
                    + "reached from a context this incomplete.")
                        .formatted(jobPrincipalId, platformType));
        }

        return ownerResolver.resolveJobPrincipal(jobPrincipalId, platformType);
    }

    /**
     * Cluster-element form. Three sources, tried in the order of how much they know about the run.
     *
     * <p>
     * A cluster element run as a tool of an AI agent action gets its owner from that action's context
     * ({@link ClusterElementContextAware#getAgentActionContext()}), which is the richest answer because it also knows
     * whether the run is an editor test.
     *
     * <p>
     * Outside an agent there is no such context, but the element may still have been created with the job principal the
     * run belongs to -- the data stream delegate does exactly that, from the Spring Batch job parameters. Reading it
     * here is what lets such an element separate the pools; without it the element resolves nothing and an empty owner
     * opens both.
     *
     * <p>
     * Only when neither is present does this fall back to the security context, which is the editor case. Never wider
     * than the action form.
     */
    public static Optional<Owner> resolve(
        ClusterElementContext clusterElementContext, ObjectProvider<OwnerResolver> ownerResolverProvider) {

        OwnerResolver ownerResolver = ownerResolverProvider.getIfAvailable();

        if (ownerResolver == null) {
            return Optional.empty();
        }

        if (clusterElementContext instanceof ClusterElementContextAware clusterElementContextAware) {
            ActionContext agentActionContext = clusterElementContextAware.getAgentActionContext();

            if (agentActionContext instanceof ActionContextAware actionContextAware) {
                return resolve(actionContextAware, ownerResolverProvider);
            }

            Long jobPrincipalId = clusterElementContextAware.getJobPrincipalId();
            PlatformType platformType = clusterElementContextAware.getPlatformType();

            if (jobPrincipalId != null && platformType != null) {
                return ownerResolver.resolveJobPrincipal(jobPrincipalId, platformType);
            }
        }

        return ownerResolver.resolveCurrentPrincipal();
    }

    /**
     * Trigger form. {@link TriggerContextAware} carries no editor flag, so the job principal is used when there is one
     * and the security context otherwise -- which is the editor case, where a trigger builds its sample output. Never
     * wider than either branch alone.
     */
    public static Optional<Owner> resolve(
        TriggerContextAware triggerContextAware, ObjectProvider<OwnerResolver> ownerResolverProvider) {

        OwnerResolver ownerResolver = ownerResolverProvider.getIfAvailable();

        if (ownerResolver == null) {
            return Optional.empty();
        }

        Long jobPrincipalId = triggerContextAware.getJobPrincipalId();
        PlatformType platformType = triggerContextAware.getType();

        if (jobPrincipalId == null || platformType == null) {
            return ownerResolver.resolveCurrentPrincipal();
        }

        return ownerResolver.resolveJobPrincipal(jobPrincipalId, platformType);
    }
}
