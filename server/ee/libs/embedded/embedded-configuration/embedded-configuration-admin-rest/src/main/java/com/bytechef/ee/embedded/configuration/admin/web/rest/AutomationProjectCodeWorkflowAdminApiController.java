/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.admin.web.rest;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.embedded.configuration.admin.web.rest.model.AutomationProjectCodeWorkflowDeployResultModel;
import com.bytechef.ee.embedded.configuration.facade.AutomationWorkflowProjectCodeWorkflowFacade;
import com.bytechef.ee.platform.codeworkflow.configuration.domain.CodeWorkflowContainer.Language;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Platform admin-API counterpart of {@code AutomationProjectCodeWorkflowApiController} (embedded internal). That
 * internal endpoint is reachable only through {@code EmbeddedApiKeySecurityConfigurer}'s connected-user auth, which
 * requires a {@code /v<n>/{externalUserId}/} path segment and grants zero authorities -- a plain platform API-key
 * bearer token can never satisfy the facade's {@code ROLE_ADMIN} guard through it. This controller is mounted on the
 * {@code /api/platform/v1/**} surface instead, exactly mirroring {@code CustomComponentApiController}: it is matched by
 * {@code PlatformApiKeySecurityConfigurer} (path pattern {@code ^/api/platform/v[0-9]+/.+}), which authenticates the
 * bearer token via {@code ApiKeyService} and grants the underlying user's real Spring authorities.
 *
 * <p>
 * Deploying through here creates the same embedded relation as the internal endpoint: the resulting catalog
 * {@code Project} is resolved/created through {@code AutomationWorkflowProjectFacade}'s marker convention, so the
 * automation project stays hidden behind the embedded automation-workflow-project entity. Never expose {@code Project}
 * ids on this surface -- only embedded-entity identifiers are meant to reach embedded callers.
 *
 * <p>
 * Authorization note: the {@code ROLE_ADMIN} guard lives on
 * {@code AutomationWorkflowProjectCodeWorkflowFacadeImpl#save}, so it is enforced identically regardless of which
 * controller reaches the facade. The internal endpoint keeps working unchanged for the admin console (browser session);
 * this endpoint exists in addition to it, for token-authenticated callers such as the CLI.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@RestController("com.bytechef.ee.embedded.configuration.admin.web.rest.AutomationProjectCodeWorkflowAdminApiController")
@RequestMapping("${openapi.openAPIDefinition.base-path.platform:}/v1")
@ConditionalOnCoordinator
@ConditionalOnEEVersion
public class AutomationProjectCodeWorkflowAdminApiController implements AutomationProjectCodeWorkflowAdminApi {

    private final AutomationWorkflowProjectCodeWorkflowFacade automationWorkflowProjectCodeWorkflowFacade;

    @SuppressFBWarnings("EI")
    public AutomationProjectCodeWorkflowAdminApiController(
        AutomationWorkflowProjectCodeWorkflowFacade automationWorkflowProjectCodeWorkflowFacade) {

        this.automationWorkflowProjectCodeWorkflowFacade = automationWorkflowProjectCodeWorkflowFacade;
    }

    @Override
    public ResponseEntity<AutomationProjectCodeWorkflowDeployResultModel> deployAutomationProjectCodeWorkflow(
        MultipartFile projectFile) {

        List<String> warnings;

        try {
            warnings = automationWorkflowProjectCodeWorkflowFacade.save(
                projectFile.getBytes(),
                Language.of(Objects.requireNonNull(projectFile.getOriginalFilename())));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return ResponseEntity.ok(new AutomationProjectCodeWorkflowDeployResultModel().warnings(warnings));
    }
}
