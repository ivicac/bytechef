/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.remote.web.rest.service;

import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceConfigurationWorkflow;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceConfigurationWorkflowConnection;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationWorkflowService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Hidden
@RestController
@RequestMapping("/remote/integration-instance-configuration-workflow-service")
public class RemoteIntegrationInstanceConfigurationWorkflowServiceController {

    private final IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService;

    @SuppressFBWarnings("EI")
    public RemoteIntegrationInstanceConfigurationWorkflowServiceController(
        IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService) {

        this.integrationInstanceConfigurationWorkflowService = integrationInstanceConfigurationWorkflowService;
    }

    @RequestMapping(
        method = RequestMethod.GET,
        value = "/is-connection-used/{connectionId}",
        produces = {
            "application/json"
        })
    public ResponseEntity<Boolean> isConnectionUsed(@PathVariable long connectionId) {
        return ResponseEntity.ok(integrationInstanceConfigurationWorkflowService.isConnectionUsed(connectionId));
    }

    @RequestMapping(
        method = RequestMethod.GET,
        value = "/get-integration-instance-configuration-workflow/{integrationInstanceConfigurationId}/{workflowId}",
        produces = {
            "application/json"
        })
    public ResponseEntity<IntegrationInstanceConfigurationWorkflow> getIntegrationInstanceWorkflow(
        @PathVariable long integrationInstanceConfigurationId, @PathVariable String workflowId) {

        return ResponseEntity.ok(
            integrationInstanceConfigurationWorkflowService.getIntegrationInstanceConfigurationWorkflow(
                integrationInstanceConfigurationId, workflowId));
    }

    @RequestMapping(
        method = RequestMethod.GET,
        value = "/get-integration-instance-configuration-workflow-connection/{integrationInstanceConfigurationId}" +
            "/{workflowId}/{operationName}/{key}",
        produces = {
            "application/json"
        })
    public ResponseEntity<IntegrationInstanceConfigurationWorkflowConnection>
        getIntegrationInstanceConfigurationWorkflowConnection(
            @PathVariable long integrationInstanceConfigurationId, @PathVariable String workflowId,
            @PathVariable String operationName, @PathVariable String key) {

        return ResponseEntity.ok(
            integrationInstanceConfigurationWorkflowService.getIntegrationInstanceConfigurationWorkflowConnection(
                integrationInstanceConfigurationId, workflowId, operationName, key));
    }

    @RequestMapping(
        method = RequestMethod.GET,
        value = "/get-integration-instance-configuration-workflow-connection/{integrationInstanceConfigurationId}" +
            "/{workflowId}/{operationName}",
        produces = {
            "application/json"
        })
    public ResponseEntity<List<IntegrationInstanceConfigurationWorkflowConnection>>
        getIntegrationInstanceWorkflowConnection(
            @PathVariable long integrationInstanceConfigurationId, @PathVariable String workflowId,
            @PathVariable String operationName) {

        return ResponseEntity.ok(
            integrationInstanceConfigurationWorkflowService.getIntegrationInstanceConfigurationWorkflowConnections(
                integrationInstanceConfigurationId, workflowId, operationName));
    }
}
