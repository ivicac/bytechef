/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.contextstore.repository;

import com.bytechef.ee.automation.contextstore.domain.WorkspaceContextStore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC repository for {@link WorkspaceContextStore}. Workspace-scoped lookups for Context Stores flow
 * through this repository — callers join the returned rows with {@code ContextStoreService} to materialise the actual
 * parent domain objects.
 *
 * @author Ivica Cardic
 * @version ee
 */
@Repository
public interface WorkspaceContextStoreRepository extends ListCrudRepository<WorkspaceContextStore, Long> {

    List<WorkspaceContextStore> findAllByWorkspaceId(Long workspaceId);

    Optional<WorkspaceContextStore> findByContextStoreId(Long contextStoreId);

    Optional<WorkspaceContextStore> findByWorkspaceIdAndContextStoreId(Long workspaceId, Long contextStoreId);
}
