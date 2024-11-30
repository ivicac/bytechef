/**
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech) (7.10.0).
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

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2024-11-30T08:22:07.952410+01:00[Europe/Zagreb]", comments = "Generator version: 7.10.0")
@Validated
@Tag(name = "workflow-node-output", description = "The Platform Workflow Node Output Internal API")
public interface WorkflowNodeOutputApi {

    default Optional<NativeWebRequest> getRequest() {
        return Optional.empty();
    }

    /**
     * GET /workflows/{id}/outputs : Get all dynamic workflow node outputs used in a workflow
     * Get all workflow node outputs used in a workflow.
     *
     * @param id The workflow id for which to return all used action definitions (required)
     * @param lastWorkflowNodeName The name of the last workflow node (action task or trigger) up to which include the output schema (E.g. mailchimp_1, airtable_3) (optional)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getPreviousWorkflowNodeOutputs",
        summary = "Get all dynamic workflow node outputs used in a workflow",
        description = "Get all workflow node outputs used in a workflow.",
        tags = { "workflow-node-output" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = WorkflowNodeOutputModel.class)))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/workflows/{id}/outputs",
        produces = { "application/json" }
    )
    
    default ResponseEntity<List<WorkflowNodeOutputModel>> getPreviousWorkflowNodeOutputs(
        @Parameter(name = "id", description = "The workflow id for which to return all used action definitions", required = true, in = ParameterIn.PATH) @PathVariable("id") String id,
        @Parameter(name = "lastWorkflowNodeName", description = "The name of the last workflow node (action task or trigger) up to which include the output schema (E.g. mailchimp_1, airtable_3)", in = ParameterIn.QUERY) @Valid @RequestParam(value = "lastWorkflowNodeName", required = false) String lastWorkflowNodeName
    ) {
        getRequest().ifPresent(request -> {
            for (MediaType mediaType: MediaType.parseMediaTypes(request.getHeader("Accept"))) {
                if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                    String exampleString = "[ { \"triggerDefinition\" : { \"help\" : { \"body\" : \"body\", \"learnMoreUrl\" : \"learnMoreUrl\" }, \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"componentName\" : \"componentName\", \"componentVersion\" : 0, \"title\" : \"title\", \"type\" : \"STATIC_WEBHOOK\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ], \"workflowNodeDescriptionDefined\" : true }, \"outputSchema\" : { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, \"workflowNodeName\" : \"workflowNodeName\", \"actionDefinition\" : { \"help\" : { \"body\" : \"body\", \"learnMoreUrl\" : \"learnMoreUrl\" }, \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"componentName\" : \"componentName\", \"componentVersion\" : 0, \"title\" : \"title\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ], \"workflowNodeDescriptionDefined\" : true }, \"taskDispatcherDefinition\" : { \"taskProperties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ], \"variablePropertiesDefined\" : true, \"icon\" : \"icon\", \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"title\" : \"title\", \"version\" : 0, \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ] }, \"sampleOutput\" : \"{}\" }, { \"triggerDefinition\" : { \"help\" : { \"body\" : \"body\", \"learnMoreUrl\" : \"learnMoreUrl\" }, \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"componentName\" : \"componentName\", \"componentVersion\" : 0, \"title\" : \"title\", \"type\" : \"STATIC_WEBHOOK\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ], \"workflowNodeDescriptionDefined\" : true }, \"outputSchema\" : { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, \"workflowNodeName\" : \"workflowNodeName\", \"actionDefinition\" : { \"help\" : { \"body\" : \"body\", \"learnMoreUrl\" : \"learnMoreUrl\" }, \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"componentName\" : \"componentName\", \"componentVersion\" : 0, \"title\" : \"title\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ], \"workflowNodeDescriptionDefined\" : true }, \"taskDispatcherDefinition\" : { \"taskProperties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ], \"variablePropertiesDefined\" : true, \"icon\" : \"icon\", \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"title\" : \"title\", \"version\" : 0, \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ] }, \"sampleOutput\" : \"{}\" } ]";
                    ApiUtil.setExampleResponse(request, "application/json", exampleString);
                    break;
                }
            }
        });
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }


    /**
     * GET /workflows/{id}/outputs/{workflowNodeName} : Get workflow node output of an action task or trigger used in a workflow
     * Get workflow node output of an action task or trigger used in a workflow.
     *
     * @param id The workflow id (required)
     * @param workflowNodeName The name of a workflow&#39;s action task or trigger (E.g. mailchimp_1) (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getWorkflowNodeOutput",
        summary = "Get workflow node output of an action task or trigger used in a workflow",
        description = "Get workflow node output of an action task or trigger used in a workflow.",
        tags = { "workflow-node-output" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", schema = @Schema(implementation = WorkflowNodeOutputModel.class))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/workflows/{id}/outputs/{workflowNodeName}",
        produces = { "application/json" }
    )
    
    default ResponseEntity<WorkflowNodeOutputModel> getWorkflowNodeOutput(
        @Parameter(name = "id", description = "The workflow id", required = true, in = ParameterIn.PATH) @PathVariable("id") String id,
        @Parameter(name = "workflowNodeName", description = "The name of a workflow's action task or trigger (E.g. mailchimp_1)", required = true, in = ParameterIn.PATH) @PathVariable("workflowNodeName") String workflowNodeName
    ) {
        getRequest().ifPresent(request -> {
            for (MediaType mediaType: MediaType.parseMediaTypes(request.getHeader("Accept"))) {
                if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                    String exampleString = "{ \"triggerDefinition\" : { \"help\" : { \"body\" : \"body\", \"learnMoreUrl\" : \"learnMoreUrl\" }, \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"componentName\" : \"componentName\", \"componentVersion\" : 0, \"title\" : \"title\", \"type\" : \"STATIC_WEBHOOK\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ], \"workflowNodeDescriptionDefined\" : true }, \"outputSchema\" : { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, \"workflowNodeName\" : \"workflowNodeName\", \"actionDefinition\" : { \"help\" : { \"body\" : \"body\", \"learnMoreUrl\" : \"learnMoreUrl\" }, \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"componentName\" : \"componentName\", \"componentVersion\" : 0, \"title\" : \"title\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ], \"workflowNodeDescriptionDefined\" : true }, \"taskDispatcherDefinition\" : { \"taskProperties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ], \"variablePropertiesDefined\" : true, \"icon\" : \"icon\", \"name\" : \"name\", \"description\" : \"description\", \"outputDefined\" : true, \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"title\" : \"title\", \"version\" : 0, \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"hidden\" : false, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : false, \"type\" : \"ARRAY\", \"required\" : false, \"expressionEnabled\" : true } ] }, \"sampleOutput\" : \"{}\" }";
                    ApiUtil.setExampleResponse(request, "application/json", exampleString);
                    break;
                }
            }
        });
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);

    }

}
