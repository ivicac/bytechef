/* tslint:disable */
/* eslint-disable */
/**
 * The Platform Configuration API
 * No description provided (generated by Openapi Generator https://github.com/openapitools/openapi-generator)
 *
 * The version of the OpenAPI document: 1
 * 
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */

import { exists, mapValues } from '../runtime';
import type { ComponentOutputSchemaModel } from './ComponentOutputSchemaModel';
import {
    ComponentOutputSchemaModelFromJSON,
    ComponentOutputSchemaModelFromJSONTyped,
    ComponentOutputSchemaModelToJSON,
} from './ComponentOutputSchemaModel';
import type { HelpModel } from './HelpModel';
import {
    HelpModelFromJSON,
    HelpModelFromJSONTyped,
    HelpModelToJSON,
} from './HelpModel';
import type { PropertyModel } from './PropertyModel';
import {
    PropertyModelFromJSON,
    PropertyModelFromJSONTyped,
    PropertyModelToJSON,
} from './PropertyModel';

/**
 * An action is a portion of reusable code that accomplish a specific task. When building a workflow, each action is represented as a task inside the workflow. The task 'type' property is defined as [component name]/v[component version]/[action name]. Action properties are used to set properties of the task inside the workflow.
 * @export
 * @interface ActionDefinitionModel
 */
export interface ActionDefinitionModel {
    /**
     * The component name.
     * @type {string}
     * @memberof ActionDefinitionModel
     */
    componentName?: string;
    /**
     * The component version.
     * @type {number}
     * @memberof ActionDefinitionModel
     */
    componentVersion?: number;
    /**
     * The description.
     * @type {string}
     * @memberof ActionDefinitionModel
     */
    description?: string;
    /**
     * Does action has defined dynamic editor description.
     * @type {boolean}
     * @memberof ActionDefinitionModel
     */
    editorDescriptionDataSource?: boolean;
    /**
     * 
     * @type {HelpModel}
     * @memberof ActionDefinitionModel
     */
    help?: HelpModel;
    /**
     * The action name.
     * @type {string}
     * @memberof ActionDefinitionModel
     */
    name: string;
    /**
     * 
     * @type {ComponentOutputSchemaModel}
     * @memberof ActionDefinitionModel
     */
    outputSchema?: ComponentOutputSchemaModel;
    /**
     * Does action has defined dynamic output schema.
     * @type {boolean}
     * @memberof ActionDefinitionModel
     */
    outputSchemaDataSource?: boolean;
    /**
     * The list of action properties.
     * @type {Array<PropertyModel>}
     * @memberof ActionDefinitionModel
     */
    properties?: Array<PropertyModel>;
    /**
     * The title
     * @type {string}
     * @memberof ActionDefinitionModel
     */
    title?: string;
}

/**
 * Check if a given object implements the ActionDefinitionModel interface.
 */
export function instanceOfActionDefinitionModel(value: object): boolean {
    let isInstance = true;
    isInstance = isInstance && "name" in value;

    return isInstance;
}

export function ActionDefinitionModelFromJSON(json: any): ActionDefinitionModel {
    return ActionDefinitionModelFromJSONTyped(json, false);
}

export function ActionDefinitionModelFromJSONTyped(json: any, ignoreDiscriminator: boolean): ActionDefinitionModel {
    if ((json === undefined) || (json === null)) {
        return json;
    }
    return {
        
        'componentName': !exists(json, 'componentName') ? undefined : json['componentName'],
        'componentVersion': !exists(json, 'componentVersion') ? undefined : json['componentVersion'],
        'description': !exists(json, 'description') ? undefined : json['description'],
        'editorDescriptionDataSource': !exists(json, 'editorDescriptionDataSource') ? undefined : json['editorDescriptionDataSource'],
        'help': !exists(json, 'help') ? undefined : HelpModelFromJSON(json['help']),
        'name': json['name'],
        'outputSchema': !exists(json, 'outputSchema') ? undefined : ComponentOutputSchemaModelFromJSON(json['outputSchema']),
        'outputSchemaDataSource': !exists(json, 'outputSchemaDataSource') ? undefined : json['outputSchemaDataSource'],
        'properties': !exists(json, 'properties') ? undefined : ((json['properties'] as Array<any>).map(PropertyModelFromJSON)),
        'title': !exists(json, 'title') ? undefined : json['title'],
    };
}

export function ActionDefinitionModelToJSON(value?: ActionDefinitionModel | null): any {
    if (value === undefined) {
        return undefined;
    }
    if (value === null) {
        return null;
    }
    return {
        
        'componentName': value.componentName,
        'componentVersion': value.componentVersion,
        'description': value.description,
        'editorDescriptionDataSource': value.editorDescriptionDataSource,
        'help': HelpModelToJSON(value.help),
        'name': value.name,
        'outputSchema': ComponentOutputSchemaModelToJSON(value.outputSchema),
        'outputSchemaDataSource': value.outputSchemaDataSource,
        'properties': value.properties === undefined ? undefined : ((value.properties as Array<any>).map(PropertyModelToJSON)),
        'title': value.title,
    };
}
