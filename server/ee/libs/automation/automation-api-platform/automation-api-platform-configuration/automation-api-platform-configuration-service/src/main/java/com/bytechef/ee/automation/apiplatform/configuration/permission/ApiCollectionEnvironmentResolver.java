/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.apiplatform.configuration.permission;

import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.security.ResourceEnvironmentResolver;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.ee.automation.apiplatform.configuration.domain.ApiCollection;
import com.bytechef.ee.automation.apiplatform.configuration.service.ApiCollectionService;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serializable;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reports the environment an API collection lives in, read off the synthetic {@code __API_COLLECTION__}
 * {@link ProjectDeployment} backing it. The fourth resolver of its kind, joining {@code Connection},
 * {@code ProjectDeployment} and {@code McpServer}.
 *
 * <p>
 * <b>An API collection genuinely lives in an environment</b>, which is what the SPI requires before contributing one:
 * the collection IS a deployment underneath, deployments are per-environment, and the same project is routinely
 * published as separate collections in Development and Production. This is unlike a project or a workflow definition,
 * where inventing an environment would deny operations that are legitimately environment-independent.
 *
 * <p>
 * Registering this is what makes every by-id gate on the API-collection facade environment-aware without threading an
 * environment argument through nine signatures: {@code hasResourceScope} consults this resolver and then checks the
 * caller's role in <em>that</em> environment, rather than unioning every environment they can reach. Without it, those
 * gates would authorize a Development-only member against a Production collection -- the exact defect ticket 732 spent
 * its sweep closing elsewhere.
 *
 * <p>
 * Returns empty rather than denying when the collection or its deployment cannot be found, per the SPI: an unanswerable
 * environment falls back to the environment-unaware check instead of turning a working permission into a failure. The
 * ownership resolver beside this one is what fails closed on a missing row.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class ApiCollectionEnvironmentResolver implements ResourceEnvironmentResolver {

    private final ApiCollectionService apiCollectionService;
    private final ProjectDeploymentService projectDeploymentService;

    @SuppressFBWarnings("EI")
    public ApiCollectionEnvironmentResolver(
        ApiCollectionService apiCollectionService, ProjectDeploymentService projectDeploymentService) {

        this.apiCollectionService = apiCollectionService;
        this.projectDeploymentService = projectDeploymentService;
    }

    @Override
    public String resourceType() {
        return "ApiCollection";
    }

    @Override
    public Optional<Environment> fetchEnvironment(Serializable id) {
        if (!(id instanceof Number number)) {
            return Optional.empty();
        }

        return apiCollectionService.fetchApiCollection(number.longValue())
            .map(ApiCollection::getProjectDeploymentId)
            .flatMap(projectDeploymentService::fetchProjectDeployment)
            .map(ProjectDeployment::getEnvironment);
    }
}
