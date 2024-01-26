/**
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech) (7.2.0).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */
package com.bytechef.platform.configuration.web.rest;

import com.bytechef.platform.configuration.web.rest.model.WorkflowNodeOutputModel;
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

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2024-01-26T08:17:52.891429+01:00[Europe/Zagreb]")
@Validated
@Tag(name = "workflow-node-output", description = "The Platform Workflow Step Output API")
public interface WorkflowNodeOutputApi {

    default Optional<NativeWebRequest> getRequest() {
        return Optional.empty();
    }

    /**
     * GET /workflows/{workflowId}/workflow-node-outputs/{workflowNodeName} : Get dynamic output schema of an action task or trigger used in a workflow
     * Get dynamic output schema of an action task or trigger used in a workflow.
     *
     * @param workflowId The workflow id (required)
     * @param workflowNodeName The name of a workflow&#39;s action task or trigger (E.g. mailchimp_1) (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getWorkflowNodeOutput",
        summary = "Get dynamic output schema of an action task or trigger used in a workflow",
        description = "Get dynamic output schema of an action task or trigger used in a workflow.",
        tags = { "workflow-node-output" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", schema = @Schema(implementation = WorkflowNodeOutputModel.class))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/workflows/{workflowId}/workflow-node-outputs/{workflowNodeName}",
        produces = { "application/json" }
    )
    
    default ResponseEntity<WorkflowNodeOutputModel> getWorkflowNodeOutput(
        @Parameter(name = "workflowId", description = "The workflow id", required = true, in = ParameterIn.PATH) @PathVariable("workflowId") String workflowId,
        @Parameter(name = "workflowNodeName", description = "The name of a workflow's action task or trigger (E.g. mailchimp_1)", required = true, in = ParameterIn.PATH) @PathVariable("workflowNodeName") String workflowNodeName
    ) {
        getRequest().ifPresent(request -> {
            for (MediaType mediaType: MediaType.parseMediaTypes(request.getHeader("Accept"))) {
                if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                    String exampleString = "{ \"outputSchema\" : { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, \"workflowNodeName\" : \"workflowNodeName\", \"actionDefinition\" : { \"help\" : { \"body\" : \"body\", \"learnMoreUrl\" : \"learnMoreUrl\" }, \"editorDescriptionDefined\" : true, \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"componentName\" : \"componentName\", \"componentVersion\" : 0, \"title\" : \"title\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true } ] }, \"taskDispatcherDefinition\" : { \"taskProperties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true } ], \"variablePropertiesDefined\" : true, \"icon\" : \"icon\", \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"title\" : \"title\", \"version\" : 0, \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true } ] }, \"sampleOutput\" : \"{}\" }";
                    ApiUtil.setExampleResponse(request, "application/json", exampleString);
                    break;
                }
            }
        });
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }


    /**
     * GET /workflows/{workflowId}/workflow-node-outputs : Get all dynamic output schemas used in a workflow
     * Get all dynamic output schemas used in a workflow.
     *
     * @param workflowId The workflow id for which to return all used action definitions (required)
     * @param lastWorkflowNodeName The name of the last workflow node (action task or trigger) up to which include the output schema (E.g. mailchimp_1, airtable_3) (optional)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getWorkflowNodeOutputs",
        summary = "Get all dynamic output schemas used in a workflow",
        description = "Get all dynamic output schemas used in a workflow.",
        tags = { "workflow-node-output" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = WorkflowNodeOutputModel.class)))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/workflows/{workflowId}/workflow-node-outputs",
        produces = { "application/json" }
    )
    
    default ResponseEntity<List<WorkflowNodeOutputModel>> getWorkflowNodeOutputs(
        @Parameter(name = "workflowId", description = "The workflow id for which to return all used action definitions", required = true, in = ParameterIn.PATH) @PathVariable("workflowId") String workflowId,
        @Parameter(name = "lastWorkflowNodeName", description = "The name of the last workflow node (action task or trigger) up to which include the output schema (E.g. mailchimp_1, airtable_3)", in = ParameterIn.QUERY) @Valid @RequestParam(value = "lastWorkflowNodeName", required = false) String lastWorkflowNodeName
    ) {
        getRequest().ifPresent(request -> {
            for (MediaType mediaType: MediaType.parseMediaTypes(request.getHeader("Accept"))) {
                if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                    String exampleString = "[ { \"outputSchema\" : { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, \"workflowNodeName\" : \"workflowNodeName\", \"actionDefinition\" : { \"help\" : { \"body\" : \"body\", \"learnMoreUrl\" : \"learnMoreUrl\" }, \"editorDescriptionDefined\" : true, \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"componentName\" : \"componentName\", \"componentVersion\" : 0, \"title\" : \"title\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true } ] }, \"taskDispatcherDefinition\" : { \"taskProperties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true } ], \"variablePropertiesDefined\" : true, \"icon\" : \"icon\", \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"title\" : \"title\", \"version\" : 0, \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true } ] }, \"sampleOutput\" : \"{}\" }, { \"outputSchema\" : { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, \"workflowNodeName\" : \"workflowNodeName\", \"actionDefinition\" : { \"help\" : { \"body\" : \"body\", \"learnMoreUrl\" : \"learnMoreUrl\" }, \"editorDescriptionDefined\" : true, \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"componentName\" : \"componentName\", \"componentVersion\" : 0, \"title\" : \"title\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true } ] }, \"taskDispatcherDefinition\" : { \"taskProperties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true } ], \"variablePropertiesDefined\" : true, \"icon\" : \"icon\", \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"title\" : \"title\", \"version\" : 0, \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"required\" : true, \"expressionEnabled\" : true } ] }, \"sampleOutput\" : \"{}\" } ]";
                    ApiUtil.setExampleResponse(request, "application/json", exampleString);
                    break;
                }
            }
        });
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }

}