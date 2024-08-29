/**
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech) (7.5.0).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */
package com.bytechef.embedded.configuration.web.rest;

import com.bytechef.embedded.configuration.web.rest.model.UnifiedIntegrationModel;
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

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2024-08-11T10:36:01.178541+02:00[Europe/Zagreb]", comments = "Generator version: 7.5.0")
@Validated
@Tag(name = "unified-integration", description = "The Embedded Unified Integration Management Internal API")
public interface UnifiedIntegrationApi {

    default Optional<NativeWebRequest> getRequest() {
        return Optional.empty();
    }

    /**
     * PATCH /unified-integrations/{componentName}/enable/{enable} : Enable/disable a unified integration
     * Enable/disable a unified integration.
     *
     * @param componentName The name of a component which supports unified integration. (required)
     * @param enable Enable/disable the unified integration. (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "enableUnifiedIntegration",
        summary = "Enable/disable a unified integration",
        description = "Enable/disable a unified integration.",
        tags = { "unified-integration" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.")
        }
    )
    @RequestMapping(
        method = RequestMethod.PATCH,
        value = "/unified-integrations/{componentName}/enable/{enable}"
    )
    
    default ResponseEntity<Void> enableUnifiedIntegration(
        @Parameter(name = "componentName", description = "The name of a component which supports unified integration.", required = true, in = ParameterIn.PATH) @PathVariable("componentName") String componentName,
        @Parameter(name = "enable", description = "Enable/disable the unified integration.", required = true, in = ParameterIn.PATH) @PathVariable("enable") Boolean enable
    ) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }


    /**
     * GET /unified-integrations : Get unified integrations
     * Get unified integrations.
     *
     * @return The list of unified integrations. (status code 200)
     */
    @Operation(
        operationId = "getUnifiedIntegrations",
        summary = "Get unified integrations",
        description = "Get unified integrations.",
        tags = { "unified-integration" },
        responses = {
            @ApiResponse(responseCode = "200", description = "The list of unified integrations.", content = {
                @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = UnifiedIntegrationModel.class)))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/unified-integrations",
        produces = { "application/json" }
    )
    
    default ResponseEntity<List<UnifiedIntegrationModel>> getUnifiedIntegrations(
        
    ) {
        getRequest().ifPresent(request -> {
            for (MediaType mediaType: MediaType.parseMediaTypes(request.getHeader("Accept"))) {
                if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                    String exampleString = "[ { \"__version\" : 6, \"createdDate\" : \"2000-01-23T04:56:07.000+00:00\", \"createdBy\" : \"createdBy\", \"lastModifiedDate\" : \"2000-01-23T04:56:07.000+00:00\", \"lastModifiedBy\" : \"lastModifiedBy\", \"id\" : 0, \"componentName\" : \"componentName\", \"categoryName\" : \"categoryName\" }, { \"__version\" : 6, \"createdDate\" : \"2000-01-23T04:56:07.000+00:00\", \"createdBy\" : \"createdBy\", \"lastModifiedDate\" : \"2000-01-23T04:56:07.000+00:00\", \"lastModifiedBy\" : \"lastModifiedBy\", \"id\" : 0, \"componentName\" : \"componentName\", \"categoryName\" : \"categoryName\" } ]";
                    ApiUtil.setExampleResponse(request, "application/json", exampleString);
                    break;
                }
            }
        });
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }

}