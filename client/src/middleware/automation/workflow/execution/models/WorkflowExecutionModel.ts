/* tslint:disable */
/* eslint-disable */
/**
 * The Automation Execution API
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
import type { JobModel } from './JobModel';
import {
    JobModelFromJSON,
    JobModelFromJSONTyped,
    JobModelToJSON,
} from './JobModel';
import type { ProjectInstanceModel } from './ProjectInstanceModel';
import {
    ProjectInstanceModelFromJSON,
    ProjectInstanceModelFromJSONTyped,
    ProjectInstanceModelToJSON,
} from './ProjectInstanceModel';
import type { ProjectModel } from './ProjectModel';
import {
    ProjectModelFromJSON,
    ProjectModelFromJSONTyped,
    ProjectModelToJSON,
} from './ProjectModel';
import type { TriggerExecutionModel } from './TriggerExecutionModel';
import {
    TriggerExecutionModelFromJSON,
    TriggerExecutionModelFromJSONTyped,
    TriggerExecutionModelToJSON,
} from './TriggerExecutionModel';
import type { WorkflowBasicModel } from './WorkflowBasicModel';
import {
    WorkflowBasicModelFromJSON,
    WorkflowBasicModelFromJSONTyped,
    WorkflowBasicModelToJSON,
} from './WorkflowBasicModel';

/**
 * Contains information about execution of a project workflow.
 * @export
 * @interface WorkflowExecutionModel
 */
export interface WorkflowExecutionModel {
    /**
     * The id of a workflow execution.
     * @type {number}
     * @memberof WorkflowExecutionModel
     */
    readonly id?: number;
    /**
     * 
     * @type {JobModel}
     * @memberof WorkflowExecutionModel
     */
    job?: JobModel;
    /**
     * 
     * @type {ProjectModel}
     * @memberof WorkflowExecutionModel
     */
    project?: ProjectModel;
    /**
     * 
     * @type {ProjectInstanceModel}
     * @memberof WorkflowExecutionModel
     */
    projectInstance?: ProjectInstanceModel;
    /**
     * 
     * @type {TriggerExecutionModel}
     * @memberof WorkflowExecutionModel
     */
    triggerExecution?: TriggerExecutionModel;
    /**
     * 
     * @type {WorkflowBasicModel}
     * @memberof WorkflowExecutionModel
     */
    workflow?: WorkflowBasicModel;
}

/**
 * Check if a given object implements the WorkflowExecutionModel interface.
 */
export function instanceOfWorkflowExecutionModel(value: object): boolean {
    let isInstance = true;

    return isInstance;
}

export function WorkflowExecutionModelFromJSON(json: any): WorkflowExecutionModel {
    return WorkflowExecutionModelFromJSONTyped(json, false);
}

export function WorkflowExecutionModelFromJSONTyped(json: any, ignoreDiscriminator: boolean): WorkflowExecutionModel {
    if ((json === undefined) || (json === null)) {
        return json;
    }
    return {
        
        'id': !exists(json, 'id') ? undefined : json['id'],
        'job': !exists(json, 'job') ? undefined : JobModelFromJSON(json['job']),
        'project': !exists(json, 'project') ? undefined : ProjectModelFromJSON(json['project']),
        'projectInstance': !exists(json, 'projectInstance') ? undefined : ProjectInstanceModelFromJSON(json['projectInstance']),
        'triggerExecution': !exists(json, 'triggerExecution') ? undefined : TriggerExecutionModelFromJSON(json['triggerExecution']),
        'workflow': !exists(json, 'workflow') ? undefined : WorkflowBasicModelFromJSON(json['workflow']),
    };
}

export function WorkflowExecutionModelToJSON(value?: WorkflowExecutionModel | null): any {
    if (value === undefined) {
        return undefined;
    }
    if (value === null) {
        return null;
    }
    return {
        
        'job': JobModelToJSON(value.job),
        'project': ProjectModelToJSON(value.project),
        'projectInstance': ProjectInstanceModelToJSON(value.projectInstance),
        'triggerExecution': TriggerExecutionModelToJSON(value.triggerExecution),
        'workflow': WorkflowBasicModelToJSON(value.workflow),
    };
}
