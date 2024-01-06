/* tslint:disable */
/* eslint-disable */
/**
 * The Core Configuration API
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
import type { WorkflowFormatModel } from './WorkflowFormatModel';
import {
    WorkflowFormatModelFromJSON,
    WorkflowFormatModelFromJSONTyped,
    WorkflowFormatModelToJSON,
} from './WorkflowFormatModel';
import type { WorkflowInputModel } from './WorkflowInputModel';
import {
    WorkflowInputModelFromJSON,
    WorkflowInputModelFromJSONTyped,
    WorkflowInputModelToJSON,
} from './WorkflowInputModel';
import type { WorkflowOutputModel } from './WorkflowOutputModel';
import {
    WorkflowOutputModelFromJSON,
    WorkflowOutputModelFromJSONTyped,
    WorkflowOutputModelToJSON,
} from './WorkflowOutputModel';
import type { WorkflowTaskModel } from './WorkflowTaskModel';
import {
    WorkflowTaskModelFromJSON,
    WorkflowTaskModelFromJSONTyped,
    WorkflowTaskModelToJSON,
} from './WorkflowTaskModel';
import type { WorkflowTriggerModel } from './WorkflowTriggerModel';
import {
    WorkflowTriggerModelFromJSON,
    WorkflowTriggerModelFromJSONTyped,
    WorkflowTriggerModelToJSON,
} from './WorkflowTriggerModel';

/**
 * The blueprint that describe the execution of a job.
 * @export
 * @interface WorkflowModel
 */
export interface WorkflowModel {
    /**
     * The created by.
     * @type {string}
     * @memberof WorkflowModel
     */
    readonly createdBy?: string;
    /**
     * The created date.
     * @type {Date}
     * @memberof WorkflowModel
     */
    readonly createdDate?: Date;
    /**
     * The description of a workflow.
     * @type {string}
     * @memberof WorkflowModel
     */
    description?: string;
    /**
     * The id of the workflow.
     * @type {string}
     * @memberof WorkflowModel
     */
    readonly id?: string;
    /**
     * The descriptive name for the workflow
     * @type {string}
     * @memberof WorkflowModel
     */
    readonly label?: string;
    /**
     * The last modified by.
     * @type {string}
     * @memberof WorkflowModel
     */
    readonly lastModifiedBy?: string;
    /**
     * The last modified date.
     * @type {Date}
     * @memberof WorkflowModel
     */
    readonly lastModifiedDate?: Date;
    /**
     * 
     * @type {number}
     * @memberof WorkflowModel
     */
    version?: number;
    /**
     * The definition of a workflow.
     * @type {string}
     * @memberof WorkflowModel
     */
    definition?: string;
    /**
     * 
     * @type {WorkflowFormatModel}
     * @memberof WorkflowModel
     */
    format?: WorkflowFormatModel;
    /**
     * The workflow's expected list of inputs.
     * @type {Array<WorkflowInputModel>}
     * @memberof WorkflowModel
     */
    readonly inputs?: Array<WorkflowInputModel>;
    /**
     * The workflow's list of expected outputs.
     * @type {Array<WorkflowOutputModel>}
     * @memberof WorkflowModel
     */
    readonly outputs?: Array<WorkflowOutputModel>;
    /**
     * The type of the source which stores the workflow definition.
     * @type {string}
     * @memberof WorkflowModel
     */
    sourceType?: WorkflowModelSourceTypeEnum;
    /**
     * The maximum number of times a task may retry.
     * @type {number}
     * @memberof WorkflowModel
     */
    readonly maxRetries?: number;
    /**
     * The steps that make up the workflow.
     * @type {Array<WorkflowTaskModel>}
     * @memberof WorkflowModel
     */
    readonly tasks?: Array<WorkflowTaskModel>;
    /**
     * The steps that make up the workflow.
     * @type {Array<WorkflowTriggerModel>}
     * @memberof WorkflowModel
     */
    readonly triggers?: Array<WorkflowTriggerModel>;
}


/**
 * @export
 */
export const WorkflowModelSourceTypeEnum = {
    Classpath: 'CLASSPATH',
    Filesystem: 'FILESYSTEM',
    Git: 'GIT',
    Jdbc: 'JDBC'
} as const;
export type WorkflowModelSourceTypeEnum = typeof WorkflowModelSourceTypeEnum[keyof typeof WorkflowModelSourceTypeEnum];


/**
 * Check if a given object implements the WorkflowModel interface.
 */
export function instanceOfWorkflowModel(value: object): boolean {
    let isInstance = true;

    return isInstance;
}

export function WorkflowModelFromJSON(json: any): WorkflowModel {
    return WorkflowModelFromJSONTyped(json, false);
}

export function WorkflowModelFromJSONTyped(json: any, ignoreDiscriminator: boolean): WorkflowModel {
    if ((json === undefined) || (json === null)) {
        return json;
    }
    return {
        
        'createdBy': !exists(json, 'createdBy') ? undefined : json['createdBy'],
        'createdDate': !exists(json, 'createdDate') ? undefined : (new Date(json['createdDate'])),
        'description': !exists(json, 'description') ? undefined : json['description'],
        'id': !exists(json, 'id') ? undefined : json['id'],
        'label': !exists(json, 'label') ? undefined : json['label'],
        'lastModifiedBy': !exists(json, 'lastModifiedBy') ? undefined : json['lastModifiedBy'],
        'lastModifiedDate': !exists(json, 'lastModifiedDate') ? undefined : (new Date(json['lastModifiedDate'])),
        'version': !exists(json, '__version') ? undefined : json['__version'],
        'definition': !exists(json, 'definition') ? undefined : json['definition'],
        'format': !exists(json, 'format') ? undefined : WorkflowFormatModelFromJSON(json['format']),
        'inputs': !exists(json, 'inputs') ? undefined : ((json['inputs'] as Array<any>).map(WorkflowInputModelFromJSON)),
        'outputs': !exists(json, 'outputs') ? undefined : ((json['outputs'] as Array<any>).map(WorkflowOutputModelFromJSON)),
        'sourceType': !exists(json, 'sourceType') ? undefined : json['sourceType'],
        'maxRetries': !exists(json, 'maxRetries') ? undefined : json['maxRetries'],
        'tasks': !exists(json, 'tasks') ? undefined : ((json['tasks'] as Array<any>).map(WorkflowTaskModelFromJSON)),
        'triggers': !exists(json, 'triggers') ? undefined : ((json['triggers'] as Array<any>).map(WorkflowTriggerModelFromJSON)),
    };
}

export function WorkflowModelToJSON(value?: WorkflowModel | null): any {
    if (value === undefined) {
        return undefined;
    }
    if (value === null) {
        return null;
    }
    return {
        
        'description': value.description,
        '__version': value.version,
        'definition': value.definition,
        'format': WorkflowFormatModelToJSON(value.format),
        'sourceType': value.sourceType,
    };
}

