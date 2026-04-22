/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.remote.client.service;

import com.bytechef.ee.automation.configuration.domain.ProjectUser;
import com.bytechef.ee.automation.configuration.service.ProjectUserService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Remote client stub for {@link ProjectUserService}. Wired into lightweight EE app variants (worker, webhook,
 * execution, coordinator, connection) where the authoritative service runs out of process. These methods are not
 * expected to be called from those apps; if they are, it almost certainly indicates a misconfiguration in the bean
 * graph rather than a business request. Each method logs an ERROR for observability and then either returns a
 * fail-closed default (read side) or silently no-ops (idempotent write side). Throwing
 * {@link UnsupportedOperationException} would surface as an opaque 500 with no audit trail.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@SuppressWarnings("PMD.UnusedFormalParameter")
public class RemoteProjectUserServiceClient implements ProjectUserService {

    private static final Logger log = LoggerFactory.getLogger(RemoteProjectUserServiceClient.class);

    @Override
    public long countByCustomRoleId(long customRoleId) {
        log.error(
            "ProjectUserService invoked on a remote client stub (countByCustomRoleId). Returning 0 \u2014 this app "
                + "variant does not host the authoritative ProjectUserService. Check bean wiring.");

        return 0L;
    }

    @Override
    public ProjectUser addProjectUser(long projectId, long userId, int projectRoleOrdinal) {
        log.error(
            "ProjectUserService.addProjectUser invoked on a remote client stub. App variant does not host the "
                + "authoritative service. Throwing to block the mutation.");

        throw new UnsupportedOperationException(
            "ProjectUserService.addProjectUser is not available on this app variant");
    }

    @Override
    public void deleteProjectUser(long projectId, long userId) {
        log.error(
            "ProjectUserService.deleteProjectUser invoked on a remote client stub. App variant does not host the "
                + "authoritative service. Throwing to block the mutation.");

        throw new UnsupportedOperationException(
            "ProjectUserService.deleteProjectUser is not available on this app variant");
    }

    @Override
    public void validateCascadeRemovalDoesNotOrphanAdminProjects(long userId, List<Long> projectIds) {
        log.error(
            "ProjectUserService.validateCascadeRemovalDoesNotOrphanAdminProjects invoked on a remote client stub. "
                + "App variant does not host the authoritative service. Throwing to block the cascade.");

        throw new UnsupportedOperationException(
            "ProjectUserService.validateCascadeRemovalDoesNotOrphanAdminProjects is not available on this app "
                + "variant");
    }

    @Override
    public Optional<ProjectUser> fetchProjectUser(long projectId, long userId) {
        log.error(
            "ProjectUserService.fetchProjectUser invoked on a remote client stub. Returning Optional.empty().");

        return Optional.empty();
    }

    @Override
    public List<ProjectUser> getProjectUsers(long projectId) {
        log.error("ProjectUserService.getProjectUsers invoked on a remote client stub. Returning empty list.");

        return List.of();
    }

    @Override
    public List<ProjectUser> getUserProjectMemberships(long userId) {
        log.error(
            "ProjectUserService.getUserProjectMemberships invoked on a remote client stub. Returning empty list.");

        return List.of();
    }

    @Override
    public ProjectUser updateProjectUserRole(long projectId, long userId, int projectRoleOrdinal) {
        log.error(
            "ProjectUserService.updateProjectUserRole invoked on a remote client stub. App variant does not host "
                + "the authoritative service. Throwing to block the mutation.");

        throw new UnsupportedOperationException(
            "ProjectUserService.updateProjectUserRole is not available on this app variant");
    }
}
