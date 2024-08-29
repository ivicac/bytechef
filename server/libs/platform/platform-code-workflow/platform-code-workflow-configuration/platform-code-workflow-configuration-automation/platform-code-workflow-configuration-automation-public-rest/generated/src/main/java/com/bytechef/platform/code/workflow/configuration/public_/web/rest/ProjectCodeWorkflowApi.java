/**
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech) (7.5.0).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */
package com.bytechef.platform.code.workflow.configuration.public_.web.rest;

import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Generated;

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2024-09-01T09:09:14.620620+02:00[Europe/Zagreb]", comments = "Generator version: 7.5.0")
@Validated
@Tag(name = "project-code-workflow", description = "The Platform Code-Native Project Public API")
public interface ProjectCodeWorkflowApi {

    default Optional<NativeWebRequest> getRequest() {
        return Optional.empty();
    }

    /**
     * POST /projects/deploy : Deploy a new code-native project
     * Deploy a new code-native project.
     *
     * @param workspaceId The id of a workspace this project will belong. (optional)
     * @param projectFile The file of a code-native project. (optional)
     * @return Successful operation. (status code 204)
     */
    @Operation(
        operationId = "deployProject",
        summary = "Deploy a new code-native project",
        description = "Deploy a new code-native project.",
        tags = { "project-code-workflow" },
        responses = {
            @ApiResponse(responseCode = "204", description = "Successful operation.")
        }
    )
    @RequestMapping(
        method = RequestMethod.POST,
        value = "/projects/deploy",
        consumes = { "multipart/form-data" }
    )
    
    default ResponseEntity<Void> deployProject(
        @Parameter(name = "workspaceId", description = "The id of a workspace this project will belong.") @Valid @RequestParam(value = "workspaceId", required = false) Long workspaceId,
        @Parameter(name = "projectFile", description = "The file of a code-native project.") @RequestPart(value = "projectFile", required = false) MultipartFile projectFile
    ) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }

}
