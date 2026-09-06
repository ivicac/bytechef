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

package com.bytechef.automation.configuration.security;

import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Narrows a listing to the environments the current principal holds a scope in. The environment counterpart of
 * {@link ProjectVisibilityFilter}, and deliberately shaped like it: one component every listing goes through, so the
 * rule cannot drift between them.
 *
 * <p>
 * It exists for the listings whose {@code environmentId} argument is nullable. Their gate takes the environment when
 * one is named and checks it, but when none is named there is nothing to check — the call is legitimately permitted,
 * and the query then returns rows from every environment in the workspace, including ones the caller holds no role in.
 * The gate is right and the body is what must narrow. When an environment <em>is</em> named the gate has already
 * checked it, so callers skip this filter rather than paying for it twice.
 *
 * <p>
 * Call {@link #scopedEnvironments(long, String)} once per listing and test rows against the result, or hand the rows to
 * {@link #filterByEnvironment(long, String, Collection, Function)} which does exactly that. What must not happen is a
 * lookup per row: {@link Environment} has three values and the underlying scope check is cached per
 * user/workspace/environment, so the whole question costs at most three cache reads — but only while it stays outside
 * the loop.
 *
 * <p>
 * Lives in {@code -api} rather than {@code -service}, and resolves its {@link PermissionService} through an
 * {@link ObjectProvider}, for the reasons spelled out on {@link ProjectVisibilityFilter}: the distributed EE apps carry
 * this module without the one the authoritative implementation lives in, and they scan {@code com.bytechef}, so a hard
 * dependency would kill their context at startup. An absent service yields no environments rather than all of them — an
 * empty listing rather than an unfiltered one, matching both that filter's posture and the {@code false} that
 * {@code RemotePermissionServiceClient} returns from every check.
 *
 * @author Ivica Cardic
 */
@Component
public class EnvironmentScopeFilter {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentScopeFilter.class);

    private final AtomicBoolean missingPermissionServiceLogged = new AtomicBoolean();
    private final ObjectProvider<PermissionService> permissionServiceProvider;

    @SuppressFBWarnings("EI")
    public EnvironmentScopeFilter(ObjectProvider<PermissionService> permissionServiceProvider) {
        this.permissionServiceProvider = permissionServiceProvider;
    }

    /**
     * The environments the current principal holds {@code scope} in, for the workspace. Returns no environments at all
     * when no {@link PermissionService} is registered.
     */
    public Set<Environment> scopedEnvironments(long workspaceId, String scope) {
        PermissionService permissionService = permissionServiceProvider.getIfAvailable();

        if (permissionService == null) {
            logMissingPermissionServiceOnce();

            return Set.of();
        }

        return permissionService.getMyWorkspaceScopeEnvironments(workspaceId, scope);
    }

    /**
     * The rows whose environment the current principal holds {@code scope} in. A row whose extractor yields no
     * environment is kept: it is not scoped to one, so there is nothing for this filter to decide about it, and
     * dropping it would hide rows on a rule this filter does not own.
     */
    public <T> List<T> filterByEnvironment(
        long workspaceId, String scope, Collection<T> rows, Function<T, Environment> environmentExtractor) {

        if (rows.isEmpty()) {
            return List.of();
        }

        Set<Environment> environments = scopedEnvironments(workspaceId, scope);

        return rows.stream()
            .filter(row -> {
                Environment environment = environmentExtractor.apply(row);

                return environment == null || environments.contains(environment);
            })
            .toList();
    }

    /**
     * Warns once per instance rather than per call, for the reason given on {@link ProjectVisibilityFilter}: this runs
     * on list paths, where a per-call warning would flood the log without adding anything to the first one.
     */
    private void logMissingPermissionServiceOnce() {
        if (missingPermissionServiceLogged.compareAndSet(false, true)) {
            log.warn(
                "No PermissionService is registered — every listing narrowed by this filter is being treated as " +
                    "reaching no environment. This application carries automation-configuration-api without a " +
                    "PermissionService implementation.");
        }
    }
}
