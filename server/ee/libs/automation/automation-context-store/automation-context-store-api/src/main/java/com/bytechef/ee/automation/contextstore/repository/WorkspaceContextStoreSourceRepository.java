/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.contextstore.repository;

import com.bytechef.ee.automation.contextstore.domain.WorkspaceContextStoreSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC repository for {@link WorkspaceContextStoreSource}. Workspace-scoped lookups for context store
 * sources flow through this repository — callers join the returned rows with
 * {@link com.bytechef.ee.platform.contextstore.service.ContextStoreSourceService} to materialise the actual source
 * domain objects.
 *
 * @author Ivica Cardic
 * @version ee
 */
@Repository
public interface WorkspaceContextStoreSourceRepository extends ListCrudRepository<WorkspaceContextStoreSource, Long> {

    List<WorkspaceContextStoreSource> findAllByWorkspaceId(Long workspaceId);

    Optional<WorkspaceContextStoreSource> findByContextStoreSourceId(Long contextStoreSourceId);
}
