/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.remote.web.rest.facade;

import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.exception.ConnectionNotEntitledException;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.exception.MissingInputException;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserCodeWorkflowReferenceFacade;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@link ConnectedUserCodeWorkflowReferenceFacade} to remote callers -- the embedded-webhook bridge's read and
 * provisioning path for automation-bridge references. The optional JSON body carries the caller's requested connection
 * ids by component name. {@link MissingConnectionException} and {@link MissingInputException} are each translated to
 * HTTP 409 with their own body shape, and {@link ConnectionNotEntitledException} to an HTTP 400 naming the refused
 * component and connection id, mirroring how {@code RequestTriggerApiController} already reports the first to its own
 * caller, so {@code RemoteConnectedUserCodeWorkflowReferenceFacadeClient} can reconstruct the right exception on the
 * other side of the wire instead of losing the distinction as a generic 5xx.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Hidden
@RestController
@RequestMapping("/remote/connected-user-code-workflow-reference-facade")
public class RemoteConnectedUserCodeWorkflowReferenceFacadeController {

    private final ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade;

    @SuppressFBWarnings("EI")
    public RemoteConnectedUserCodeWorkflowReferenceFacadeController(
        ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade) {

        this.connectedUserCodeWorkflowReferenceFacade = connectedUserCodeWorkflowReferenceFacade;
    }

    @RequestMapping(
        method = RequestMethod.GET,
        value = "/get-connected-user-workflows/{connectedUserId}",
        produces = {
            "application/json"
        })
    public ResponseEntity<List<ConnectedUserProjectWorkflow>> getConnectedUserWorkflows(
        @PathVariable long connectedUserId) {

        return ResponseEntity.ok(
            connectedUserCodeWorkflowReferenceFacade.getConnectedUserWorkflows(connectedUserId));
    }

    @RequestMapping(
        method = RequestMethod.POST,
        value = "/get-or-create-reference",
        produces = {
            "application/json"
        })
    public ResponseEntity<?> getOrCreateReference(
        @RequestParam String externalUserId, @RequestParam String catalogWorkflowUuid,
        @RequestParam Environment environment,
        @RequestBody(required = false) @Nullable Map<String, Long> requestedConnectionIds) {

        try {
            ConnectedUserProjectWorkflow reference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
                externalUserId, catalogWorkflowUuid, environment,
                requestedConnectionIds == null ? Map.of() : requestedConnectionIds);

            return ResponseEntity.ok(reference);
        } catch (MissingConnectionException missingConnectionException) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("missingConnectionComponentName", missingConnectionException.getComponentName()));
        } catch (MissingInputException missingInputException) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("missingInputName", missingInputException.getInputName()));
        } catch (ConnectionNotEntitledException connectionNotEntitledException) {
            return ResponseEntity.badRequest()
                .body(
                    Map.of(
                        "componentName", connectionNotEntitledException.getComponentName(),
                        "connectionId", connectionNotEntitledException.getConnectionId()));
        }
    }
}
