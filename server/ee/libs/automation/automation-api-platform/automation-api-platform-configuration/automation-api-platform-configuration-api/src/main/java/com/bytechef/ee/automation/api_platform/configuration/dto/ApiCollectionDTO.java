/*
 * Copyright 2023-present ByteChef Inc.
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.api_platform.configuration.dto;

import com.bytechef.automation.configuration.domain.ProjectInstance;
import com.bytechef.ee.automation.api_platform.configuration.domain.ApiCollection;
import com.bytechef.platform.tag.domain.Tag;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
public record ApiCollectionDTO(
    String createdBy, LocalDateTime createdDate, String description, boolean enabled,
    List<ApiCollectionEndpointDTO> endpoints, Long id, String lastModifiedBy, LocalDateTime lastModifiedDate,
    String name,
    long projectId, long projectInstanceId, int projectVersion, List<Tag> tags, int version) {

    public ApiCollectionDTO(
        ApiCollection apiCollection, List<ApiCollectionEndpointDTO> endpoints, ProjectInstance projectInstance,
        List<Tag> tags) {

        this(
            apiCollection.getCreatedBy(), apiCollection.getCreatedDate(), apiCollection.getDescription(),
            projectInstance.isEnabled(), endpoints, apiCollection.getId(), apiCollection.getLastModifiedBy(),
            apiCollection.getLastModifiedDate(), apiCollection.getName(), projectInstance.getProjectId(),
            apiCollection.getProjectInstanceId(), projectInstance.getProjectVersion(), tags,
            apiCollection.getVersion());
    }

    public ApiCollection toApiCollection() {
        ApiCollection apiCollection = new ApiCollection();

        apiCollection.setDescription(description);
        apiCollection.setId(id);
        apiCollection.setName(name);
        apiCollection.setProjectInstanceId(projectInstanceId);
        apiCollection.setTags(tags);
        apiCollection.setVersion(version);

        return apiCollection;
    }
}