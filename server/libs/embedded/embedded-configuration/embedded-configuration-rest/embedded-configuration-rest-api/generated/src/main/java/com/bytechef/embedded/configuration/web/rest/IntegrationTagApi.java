/**
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech) (7.5.0).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */
package com.bytechef.embedded.configuration.web.rest;

import com.bytechef.embedded.configuration.web.rest.model.TagModel;
import com.bytechef.embedded.configuration.web.rest.model.UpdateTagsRequestModel;
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

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2024-06-12T07:17:52.459616+02:00[Europe/Zagreb]", comments = "Generator version: 7.5.0")
@Validated
@Tag(name = "integration-tag", description = "The Embedded Tag API")
public interface IntegrationTagApi {

    default Optional<NativeWebRequest> getRequest() {
        return Optional.empty();
    }

    /**
     * GET /integrations/tags : Get integration tags
     * Get integration tags.
     *
     * @return A list of integration tags. (status code 200)
     */
    @Operation(
        operationId = "getIntegrationTags",
        summary = "Get integration tags",
        description = "Get integration tags.",
        tags = { "integration-tag" },
        responses = {
            @ApiResponse(responseCode = "200", description = "A list of integration tags.", content = {
                @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = TagModel.class)))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/integrations/tags",
        produces = { "application/json" }
    )
    
    default ResponseEntity<List<TagModel>> getIntegrationTags(
        
    ) {
        getRequest().ifPresent(request -> {
            for (MediaType mediaType: MediaType.parseMediaTypes(request.getHeader("Accept"))) {
                if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                    String exampleString = "[ { \"__version\" : 6, \"createdDate\" : \"2000-01-23T04:56:07.000+00:00\", \"createdBy\" : \"createdBy\", \"lastModifiedDate\" : \"2000-01-23T04:56:07.000+00:00\", \"lastModifiedBy\" : \"lastModifiedBy\", \"name\" : \"name\", \"id\" : 0 }, { \"__version\" : 6, \"createdDate\" : \"2000-01-23T04:56:07.000+00:00\", \"createdBy\" : \"createdBy\", \"lastModifiedDate\" : \"2000-01-23T04:56:07.000+00:00\", \"lastModifiedBy\" : \"lastModifiedBy\", \"name\" : \"name\", \"id\" : 0 } ]";
                    ApiUtil.setExampleResponse(request, "application/json", exampleString);
                    break;
                }
            }
        });
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }


    /**
     * PUT /integrations/{id}/tags : Updates tags of an existing integration
     * Updates tags of an existing integration.
     *
     * @param id The id of an integration. (required)
     * @param updateTagsRequestModel  (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "updateIntegrationTags",
        summary = "Updates tags of an existing integration",
        description = "Updates tags of an existing integration.",
        tags = { "integration-tag" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.")
        }
    )
    @RequestMapping(
        method = RequestMethod.PUT,
        value = "/integrations/{id}/tags",
        consumes = { "application/json" }
    )
    
    default ResponseEntity<Void> updateIntegrationTags(
        @Parameter(name = "id", description = "The id of an integration.", required = true, in = ParameterIn.PATH) @PathVariable("id") Long id,
        @Parameter(name = "UpdateTagsRequestModel", description = "", required = true) @Valid @RequestBody UpdateTagsRequestModel updateTagsRequestModel
    ) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }

}
