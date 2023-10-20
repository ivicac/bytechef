/**
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech) (6.5.0).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */
package com.bytechef.hermes.definition.registry.web.rest;

import com.bytechef.hermes.definition.registry.web.rest.model.ActionDefinitionBasicModel;
import com.bytechef.hermes.definition.registry.web.rest.model.ActionDefinitionModel;
import com.bytechef.hermes.definition.registry.web.rest.model.ComponentDefinitionBasicModel;
import com.bytechef.hermes.definition.registry.web.rest.model.ComponentDefinitionModel;
import com.bytechef.hermes.definition.registry.web.rest.model.ConnectionDefinitionBasicModel;
import com.bytechef.hermes.definition.registry.web.rest.model.ConnectionDefinitionModel;
import com.bytechef.hermes.definition.registry.web.rest.model.TriggerDefinitionBasicModel;
import com.bytechef.hermes.definition.registry.web.rest.model.TriggerDefinitionModel;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.http.codec.multipart.Part;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Generated;

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2023-04-05T18:35:34.469553+02:00[Europe/Zagreb]")
@Validated
@Tag(name = "action-definitions", description = "the action-definitions API")
public interface ComponentDefinitionsApi {

    /**
     * GET /component-definitions/{componentName}/{componentVersion}/action-definitions/{actionName} : Get an action definition of a component.
     * Get an action definition of a component.
     *
     * @param componentName The name of the component. (required)
     * @param componentVersion The version of the component to get. (required)
     * @param actionName The name of the action to get. (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getComponentActionDefinition",
        summary = "Get an action definition of a component.",
        description = "Get an action definition of a component.",
        tags = { "action-definitions" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", schema = @Schema(implementation = ActionDefinitionModel.class))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/component-definitions/{componentName}/{componentVersion}/action-definitions/{actionName}",
        produces = { "application/json" }
    )
    default Mono<ResponseEntity<ActionDefinitionModel>> getComponentActionDefinition(
        @Parameter(name = "componentName", description = "The name of the component.", required = true, in = ParameterIn.PATH) @PathVariable("componentName") String componentName,
        @Parameter(name = "componentVersion", description = "The version of the component to get.", required = true, in = ParameterIn.PATH) @PathVariable("componentVersion") Integer componentVersion,
        @Parameter(name = "actionName", description = "The name of the action to get.", required = true, in = ParameterIn.PATH) @PathVariable("actionName") String actionName,
        @Parameter(hidden = true) final ServerWebExchange exchange
    ) {
        Mono<Void> result = Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED);
        for (MediaType mediaType : exchange.getRequest().getHeaders().getAccept()) {
            if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                String exampleString = "{ \"outputSchema\" : [ { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true } ], \"exampleOutput\" : \"{}\", \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true } ] }";
                result = ApiUtil.getExampleResponse(exchange, mediaType, exampleString);
                break;
            }
        }
        return result.then(Mono.empty());

    }


    /**
     * GET /component-definitions/{componentName}/{componentVersion}/action-definitions : Get a list of action definitions for a component.
     * Get a list of action definitions for a component.
     *
     * @param componentName The name of the component. (required)
     * @param componentVersion The version of the component to get. (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getComponentActionDefinitions",
        summary = "Get a list of action definitions for a component.",
        description = "Get a list of action definitions for a component.",
        tags = { "action-definitions" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ActionDefinitionBasicModel.class)))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/component-definitions/{componentName}/{componentVersion}/action-definitions",
        produces = { "application/json" }
    )
    default Mono<ResponseEntity<Flux<ActionDefinitionBasicModel>>> getComponentActionDefinitions(
        @Parameter(name = "componentName", description = "The name of the component.", required = true, in = ParameterIn.PATH) @PathVariable("componentName") String componentName,
        @Parameter(name = "componentVersion", description = "The version of the component to get.", required = true, in = ParameterIn.PATH) @PathVariable("componentVersion") Integer componentVersion,
        @Parameter(hidden = true) final ServerWebExchange exchange
    ) {
        Mono<Void> result = Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED);
        for (MediaType mediaType : exchange.getRequest().getHeaders().getAccept()) {
            if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                String exampleString = "[ { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" } }, { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" } } ]";
                result = ApiUtil.getExampleResponse(exchange, mediaType, exampleString);
                break;
            }
        }
        return result.then(Mono.empty());

    }


    /**
     * GET /component-definitions/{componentName}/{componentVersion}/connection-definition : Get connection definition for a component.
     * Get connection definition for a component.
     *
     * @param componentName The name of a component. (required)
     * @param componentVersion The version of a component. (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getComponentConnectionDefinition",
        summary = "Get connection definition for a component.",
        description = "Get connection definition for a component.",
        tags = { "connection-definitions" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", schema = @Schema(implementation = ConnectionDefinitionModel.class))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/component-definitions/{componentName}/{componentVersion}/connection-definition",
        produces = { "application/json" }
    )
    default Mono<ResponseEntity<ConnectionDefinitionModel>> getComponentConnectionDefinition(
        @Parameter(name = "componentName", description = "The name of a component.", required = true, in = ParameterIn.PATH) @PathVariable("componentName") String componentName,
        @Parameter(name = "componentVersion", description = "The version of a component.", required = true, in = ParameterIn.PATH) @PathVariable("componentVersion") Integer componentVersion,
        @Parameter(hidden = true) final ServerWebExchange exchange
    ) {
        Mono<Void> result = Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED);
        for (MediaType mediaType : exchange.getRequest().getHeaders().getAccept()) {
            if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                String exampleString = "{ \"authorizationRequired\" : true, \"baseUri\" : \"baseUri\", \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"authorizations\" : [ { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true } ] }, { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true } ] } ], \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"version\" : 0, \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true } ] }";
                result = ApiUtil.getExampleResponse(exchange, mediaType, exampleString);
                break;
            }
        }
        return result.then(Mono.empty());

    }


    /**
     * GET /component-definitions/{componentName}/{componentVersion}/connection-definitions : Get all compatible connection definitions for a component.
     * Get all compatible connection definitions for a component.
     *
     * @param componentName The name of a component. (required)
     * @param componentVersion The version of a component. (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getComponentConnectionDefinitions",
        summary = "Get all compatible connection definitions for a component.",
        description = "Get all compatible connection definitions for a component.",
        tags = { "connection-definitions" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ConnectionDefinitionBasicModel.class)))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/component-definitions/{componentName}/{componentVersion}/connection-definitions",
        produces = { "application/json" }
    )
    default Mono<ResponseEntity<Flux<ConnectionDefinitionBasicModel>>> getComponentConnectionDefinitions(
        @Parameter(name = "componentName", description = "The name of a component.", required = true, in = ParameterIn.PATH) @PathVariable("componentName") String componentName,
        @Parameter(name = "componentVersion", description = "The version of a component.", required = true, in = ParameterIn.PATH) @PathVariable("componentVersion") Integer componentVersion,
        @Parameter(hidden = true) final ServerWebExchange exchange
    ) {
        Mono<Void> result = Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED);
        for (MediaType mediaType : exchange.getRequest().getHeaders().getAccept()) {
            if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                String exampleString = "[ { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"version\" : 0 }, { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"version\" : 0 } ]";
                result = ApiUtil.getExampleResponse(exchange, mediaType, exampleString);
                break;
            }
        }
        return result.then(Mono.empty());

    }


    /**
     * GET /component-definitions/{componentName} : Get a component definition.
     * Get a component definition.
     *
     * @param componentName The name of a component to get. (required)
     * @param componentVersion The version of a component to get. If not set, teh latest version is returned. (optional)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getComponentDefinition",
        summary = "Get a component definition.",
        description = "Get a component definition.",
        tags = { "component-definitions" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", schema = @Schema(implementation = ComponentDefinitionModel.class))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/component-definitions/{componentName}",
        produces = { "application/json" }
    )
    default Mono<ResponseEntity<ComponentDefinitionModel>> getComponentDefinition(
        @Parameter(name = "componentName", description = "The name of a component to get.", required = true, in = ParameterIn.PATH) @PathVariable("componentName") String componentName,
        @Parameter(name = "componentVersion", description = "The version of a component to get. If not set, teh latest version is returned.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "componentVersion", required = false) Integer componentVersion,
        @Parameter(hidden = true) final ServerWebExchange exchange
    ) {
        Mono<Void> result = Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED);
        for (MediaType mediaType : exchange.getRequest().getHeaders().getAccept()) {
            if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                String exampleString = "{ \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"connection\" : { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"version\" : 0 }, \"triggers\" : [ { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\" }, { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\" } ], \"actions\" : [ { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" } }, { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" } } ], \"version\" : 0 }";
                result = ApiUtil.getExampleResponse(exchange, mediaType, exampleString);
                break;
            }
        }
        return result.then(Mono.empty());

    }


    /**
     * GET /component-definitions/{componentName}/versions : Get all component definition versions of a component.
     * Get all component definition versions of a component.
     *
     * @param componentName The name of a component to get. (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getComponentDefinitionVersions",
        summary = "Get all component definition versions of a component.",
        description = "Get all component definition versions of a component.",
        tags = { "component-definitions" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ComponentDefinitionBasicModel.class)))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/component-definitions/{componentName}/versions",
        produces = { "application/json" }
    )
    default Mono<ResponseEntity<Flux<ComponentDefinitionBasicModel>>> getComponentDefinitionVersions(
        @Parameter(name = "componentName", description = "The name of a component to get.", required = true, in = ParameterIn.PATH) @PathVariable("componentName") String componentName,
        @Parameter(hidden = true) final ServerWebExchange exchange
    ) {
        Mono<Void> result = Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED);
        for (MediaType mediaType : exchange.getRequest().getHeaders().getAccept()) {
            if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                String exampleString = "[ { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" } }, { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" } } ]";
                result = ApiUtil.getExampleResponse(exchange, mediaType, exampleString);
                break;
            }
        }
        return result.then(Mono.empty());

    }


    /**
     * GET /component-definitions : Get all component definitions.
     * Get all component definitions.
     *
     * @param actionDefinitions Use for filtering components which define action definitions. (optional)
     * @param connectionDefinitions Use for filtering components which define connection definitions. (optional)
     * @param connectionInstances Use for filtering components which have connection instances created. (optional)
     * @param triggerDefinitions Use for filtering components which define trigger definitions. (optional)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getComponentDefinitions",
        summary = "Get all component definitions.",
        description = "Get all component definitions.",
        tags = { "component-definitions" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ComponentDefinitionBasicModel.class)))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/component-definitions",
        produces = { "application/json" }
    )
    default Mono<ResponseEntity<Flux<ComponentDefinitionBasicModel>>> getComponentDefinitions(
        @Parameter(name = "actionDefinitions", description = "Use for filtering components which define action definitions.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "actionDefinitions", required = false) Boolean actionDefinitions,
        @Parameter(name = "connectionDefinitions", description = "Use for filtering components which define connection definitions.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "connectionDefinitions", required = false) Boolean connectionDefinitions,
        @Parameter(name = "connectionInstances", description = "Use for filtering components which have connection instances created.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "connectionInstances", required = false) Boolean connectionInstances,
        @Parameter(name = "triggerDefinitions", description = "Use for filtering components which define trigger definitions.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "triggerDefinitions", required = false) Boolean triggerDefinitions,
        @Parameter(hidden = true) final ServerWebExchange exchange
    ) {
        Mono<Void> result = Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED);
        for (MediaType mediaType : exchange.getRequest().getHeaders().getAccept()) {
            if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                String exampleString = "[ { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" } }, { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" } } ]";
                result = ApiUtil.getExampleResponse(exchange, mediaType, exampleString);
                break;
            }
        }
        return result.then(Mono.empty());

    }


    /**
     * GET /component-definitions/{componentName}/{componentVersion}/trigger-definitions/{triggerName} : Get a trigger definition of a component.
     * Get a trigger definition of a component.
     *
     * @param componentName The name of the component. (required)
     * @param componentVersion The version of the component to get. (required)
     * @param triggerName The name of the action to get. (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getComponentTriggerDefinition",
        summary = "Get a trigger definition of a component.",
        description = "Get a trigger definition of a component.",
        tags = { "trigger-definitions" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", schema = @Schema(implementation = TriggerDefinitionModel.class))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/component-definitions/{componentName}/{componentVersion}/trigger-definitions/{triggerName}",
        produces = { "application/json" }
    )
    default Mono<ResponseEntity<TriggerDefinitionModel>> getComponentTriggerDefinition(
        @Parameter(name = "componentName", description = "The name of the component.", required = true, in = ParameterIn.PATH) @PathVariable("componentName") String componentName,
        @Parameter(name = "componentVersion", description = "The version of the component to get.", required = true, in = ParameterIn.PATH) @PathVariable("componentVersion") Integer componentVersion,
        @Parameter(name = "triggerName", description = "The name of the action to get.", required = true, in = ParameterIn.PATH) @PathVariable("triggerName") String triggerName,
        @Parameter(hidden = true) final ServerWebExchange exchange
    ) {
        Mono<Void> result = Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED);
        for (MediaType mediaType : exchange.getRequest().getHeaders().getAccept()) {
            if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                String exampleString = "{ \"outputSchema\" : [ { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true } ], \"exampleOutput\" : \"{}\", \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\", \"resources\" : { \"documentationUrl\" : \"documentationUrl\" }, \"properties\" : [ { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true }, { \"displayCondition\" : \"displayCondition\", \"metadata\" : { \"key\" : \"{}\" }, \"hidden\" : true, \"name\" : \"name\", \"description\" : \"description\", \"advancedOption\" : true, \"label\" : \"label\", \"placeholder\" : \"placeholder\", \"required\" : true, \"expressionEnabled\" : true } ] }";
                result = ApiUtil.getExampleResponse(exchange, mediaType, exampleString);
                break;
            }
        }
        return result.then(Mono.empty());

    }


    /**
     * GET /component-definitions/{componentName}/{componentVersion}/trigger-definitions : Get a list of trigger definitionss for a component.
     * Get a list of trigger definitionss for a component.
     *
     * @param componentName The name of the component. (required)
     * @param componentVersion The version of the component to get. (required)
     * @return Successful operation. (status code 200)
     */
    @Operation(
        operationId = "getComponentTriggerDefinitions",
        summary = "Get a list of trigger definitionss for a component.",
        description = "Get a list of trigger definitionss for a component.",
        tags = { "trigger-definitions" },
        responses = {
            @ApiResponse(responseCode = "200", description = "Successful operation.", content = {
                @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = TriggerDefinitionBasicModel.class)))
            })
        }
    )
    @RequestMapping(
        method = RequestMethod.GET,
        value = "/component-definitions/{componentName}/{componentVersion}/trigger-definitions",
        produces = { "application/json" }
    )
    default Mono<ResponseEntity<Flux<TriggerDefinitionBasicModel>>> getComponentTriggerDefinitions(
        @Parameter(name = "componentName", description = "The name of the component.", required = true, in = ParameterIn.PATH) @PathVariable("componentName") String componentName,
        @Parameter(name = "componentVersion", description = "The version of the component to get.", required = true, in = ParameterIn.PATH) @PathVariable("componentVersion") Integer componentVersion,
        @Parameter(hidden = true) final ServerWebExchange exchange
    ) {
        Mono<Void> result = Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatus.NOT_IMPLEMENTED);
        for (MediaType mediaType : exchange.getRequest().getHeaders().getAccept()) {
            if (mediaType.isCompatibleWith(MediaType.valueOf("application/json"))) {
                String exampleString = "[ { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\" }, { \"display\" : { \"subtitle\" : \"subtitle\", \"icon\" : \"icon\", \"description\" : \"description\", \"category\" : \"category\", \"title\" : \"title\", \"tags\" : [ \"tags\", \"tags\" ] }, \"name\" : \"name\" } ]";
                result = ApiUtil.getExampleResponse(exchange, mediaType, exampleString);
                break;
            }
        }
        return result.then(Mono.empty());

    }

}
