/* tslint:disable */
/* eslint-disable */
/**
 * The Core Execution API
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
import type { WorkflowConnectionModel } from './WorkflowConnectionModel';
import {
    WorkflowConnectionModelFromJSON,
    WorkflowConnectionModelFromJSONTyped,
    WorkflowConnectionModelToJSON,
} from './WorkflowConnectionModel';

/**
 * Represents a definition of a workflow trigger.
 * @export
 * @interface WorkflowTriggerModel
 */
export interface WorkflowTriggerModel {
    /**
     * 
     * @type {Array<WorkflowConnectionModel>}
     * @memberof WorkflowTriggerModel
     */
    connections?: Array<WorkflowConnectionModel>;
    /**
     * The human-readable description of the task.
     * @type {string}
     * @memberof WorkflowTriggerModel
     */
    label?: string;
    /**
     * The identifier name of the task. Task names are used for assigning the output of one task so it can be later used by subsequent tasks.
     * @type {string}
     * @memberof WorkflowTriggerModel
     */
    name: string;
    /**
     * Key-value map of task parameters.
     * @type {{ [key: string]: object; }}
     * @memberof WorkflowTriggerModel
     */
    parameters?: { [key: string]: object; };
    /**
     * The timeout expression which describes when a trigger should be deemed as timed-out.
     * @type {string}
     * @memberof WorkflowTriggerModel
     */
    timeout?: string;
    /**
     * The type of a trigger.
     * @type {string}
     * @memberof WorkflowTriggerModel
     */
    type: string;
}

/**
 * Check if a given object implements the WorkflowTriggerModel interface.
 */
export function instanceOfWorkflowTriggerModel(value: object): boolean {
    let isInstance = true;
    isInstance = isInstance && "name" in value;
    isInstance = isInstance && "type" in value;

    return isInstance;
}

export function WorkflowTriggerModelFromJSON(json: any): WorkflowTriggerModel {
    return WorkflowTriggerModelFromJSONTyped(json, false);
}

export function WorkflowTriggerModelFromJSONTyped(json: any, ignoreDiscriminator: boolean): WorkflowTriggerModel {
    if ((json === undefined) || (json === null)) {
        return json;
    }
    return {
        
        'connections': !exists(json, 'connections') ? undefined : ((json['connections'] as Array<any>).map(WorkflowConnectionModelFromJSON)),
        'label': !exists(json, 'label') ? undefined : json['label'],
        'name': json['name'],
        'parameters': !exists(json, 'parameters') ? undefined : json['parameters'],
        'timeout': !exists(json, 'timeout') ? undefined : json['timeout'],
        'type': json['type'],
    };
}

export function WorkflowTriggerModelToJSON(value?: WorkflowTriggerModel | null): any {
    if (value === undefined) {
        return undefined;
    }
    if (value === null) {
        return null;
    }
    return {
        
        'connections': value.connections === undefined ? undefined : ((value.connections as Array<any>).map(WorkflowConnectionModelToJSON)),
        'label': value.label,
        'name': value.name,
        'parameters': value.parameters,
        'timeout': value.timeout,
        'type': value.type,
    };
}

