/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.public_.web.rest.mapper;

import com.bytechef.ee.automation.configuration.service.ProjectCodeWorkflowService;
import com.bytechef.ee.embedded.configuration.dto.AutomationWorkflowProjectDTO;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserWorkflowTemplateDTO;
import com.bytechef.ee.embedded.configuration.public_.web.rest.mapper.config.EmbeddedConfigurationPublicMapperSpringConfig;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.AutomationWorkflowProjectComponentModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.AutomationWorkflowProjectModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.AutomationWorkflowProjectWorkflowTemplateModel;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.converter.Converter;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Mapper(
    config = EmbeddedConfigurationPublicMapperSpringConfig.class,
    implementationName = "EmbeddedPublic<CLASS_NAME>Impl")
public abstract class AutomationWorkflowProjectMapper
    implements Converter<AutomationWorkflowProjectDTO, AutomationWorkflowProjectModel> {

    @Autowired
    private ProjectCodeWorkflowService projectCodeWorkflowService;

    @Override
    @Mapping(target = "kind", ignore = true)
    public abstract AutomationWorkflowProjectModel convert(AutomationWorkflowProjectDTO automationWorkflowProjectDTO);

    public abstract AutomationWorkflowProjectComponentModel toComponentModel(
        ConnectedUserWorkflowTemplateDTO.Component component);

    @Mapping(target = "id", source = "workflowUuid")
    public abstract AutomationWorkflowProjectWorkflowTemplateModel toWorkflowTemplateModel(
        ConnectedUserWorkflowTemplateDTO workflowTemplateDTO);

    /**
     * A catalog project's {@code kind} tells clients whether copying one of its workflow templates creates a per-user
     * copy ({@code COPY}) or a shared reference ({@code REFERENCE}) -- mirroring the same code-workflow-project
     * existence check the catalog deploy path relies on to know which projects are code-workflow-backed.
     */
    @AfterMapping
    protected void afterMapping(
        AutomationWorkflowProjectDTO automationWorkflowProjectDTO,
        @MappingTarget AutomationWorkflowProjectModel automationWorkflowProjectModel) {

        boolean codeWorkflowProject = projectCodeWorkflowService.getCodeWorkflowProjectIds()
            .contains(automationWorkflowProjectDTO.id());

        automationWorkflowProjectModel.setKind(
            codeWorkflowProject
                ? AutomationWorkflowProjectModel.KindEnum.REFERENCE
                : AutomationWorkflowProjectModel.KindEnum.COPY);
    }
}
