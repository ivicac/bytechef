/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.service;

import com.bytechef.automation.configuration.service.ConnectionVisibilityResolver;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.platform.security.util.SecurityUtils;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * EE full visibility resolution: WORKSPACE and ORGANIZATION connections are visible to all workspace members; PRIVATE
 * only to the creator (or any admin, for orphan-recovery).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnEEVersion
public class ConnectionVisibilityResolverImpl implements ConnectionVisibilityResolver {

    @Override
    public List<ConnectionDTO> filterVisible(List<ConnectionDTO> connections, long workspaceId) {
        String currentUserLogin = SecurityUtils.getCurrentUserLogin();
        boolean isAdmin = SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN);

        return connections.stream()
            .filter(connection -> switch (connection.visibility()) {
                case ORGANIZATION, WORKSPACE -> true;
                case PRIVATE -> isAdmin || Objects.equals(currentUserLogin, connection.createdBy());
            })
            .toList();
    }
}
