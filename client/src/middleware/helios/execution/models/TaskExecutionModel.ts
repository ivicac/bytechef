/* tslint:disable */
/* eslint-disable */
/**
 * Automation Execution API
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
import type { ComponentDefinitionModel } from './ComponentDefinitionModel';
import {
    ComponentDefinitionModelFromJSON,
    ComponentDefinitionModelFromJSONTyped,
    ComponentDefinitionModelToJSON,
} from './ComponentDefinitionModel';
import type { ExecutionErrorModel } from './ExecutionErrorModel';
import {
    ExecutionErrorModelFromJSON,
    ExecutionErrorModelFromJSONTyped,
    ExecutionErrorModelToJSON,
} from './ExecutionErrorModel';
import type { WorkflowTaskModel } from './WorkflowTaskModel';
import {
    WorkflowTaskModelFromJSON,
    WorkflowTaskModelFromJSONTyped,
    WorkflowTaskModelToJSON,
} from './WorkflowTaskModel';

/**
 * Adds execution semantics to a task.
 * @export
 * @interface TaskExecutionModel
 */
export interface TaskExecutionModel {
    /**
     * 
     * @type {ComponentDefinitionModel}
     * @memberof TaskExecutionModel
     */
    componentDefinition?: ComponentDefinitionModel;
    /**
     * The created by.
     * @type {string}
     * @memberof TaskExecutionModel
     */
    readonly createdBy?: string;
    /**
     * The created date.
     * @type {Date}
     * @memberof TaskExecutionModel
     */
    readonly createdDate?: Date;
    /**
     * The time when a task instance ended (CANCELLED, FAILED, COMPLETED).
     * @type {Date}
     * @memberof TaskExecutionModel
     */
    readonly endDate?: Date;
    /**
     * 
     * @type {ExecutionErrorModel}
     * @memberof TaskExecutionModel
     */
    error?: ExecutionErrorModel;
    /**
     * The total time in ms for a task to execute (excluding wait time of the task in transit). i.e. actual execution time on a worker node.
     * @type {number}
     * @memberof TaskExecutionModel
     */
    readonly executionTime?: number;
    /**
     * The id of a task execution.
     * @type {string}
     * @memberof TaskExecutionModel
     */
    readonly id?: string;
    /**
     * The input parameters for a task.
     * @type {{ [key: string]: any; }}
     * @memberof TaskExecutionModel
     */
    readonly input?: { [key: string]: any; };
    /**
     * The id of a job for which a task belongs to.
     * @type {string}
     * @memberof TaskExecutionModel
     */
    readonly jobId: string;
    /**
     * The last modified by.
     * @type {string}
     * @memberof TaskExecutionModel
     */
    readonly lastModifiedBy?: string;
    /**
     * The last modified date.
     * @type {Date}
     * @memberof TaskExecutionModel
     */
    readonly lastModifiedDate?: Date;
    /**
     * The result output generated by the task handler which executed a task.
     * @type {object}
     * @memberof TaskExecutionModel
     */
    readonly output?: object;
    /**
     * The id of the parent task, if this is a sub-task.
     * @type {string}
     * @memberof TaskExecutionModel
     */
    readonly parentId?: string;
    /**
     * The priority value.
     * @type {number}
     * @memberof TaskExecutionModel
     */
    readonly priority: number;
    /**
     * The current progress value, a number between 0 and 100.
     * @type {number}
     * @memberof TaskExecutionModel
     */
    readonly progress?: number;
    /**
     * The maximum number of times that a task may retry.
     * @type {number}
     * @memberof TaskExecutionModel
     */
    readonly maxRetries?: number;
    /**
     * The number of times that a task has been retried.
     * @type {number}
     * @memberof TaskExecutionModel
     */
    readonly retryAttempts?: number;
    /**
     * The delay to introduce between each retry. Values are to be specified using the ISO-8601 format (excluding the PT prefix). e.g. 10s (ten seconds), 1m (one minute) etc.
     * @type {string}
     * @memberof TaskExecutionModel
     */
    readonly retryDelay?: string;
    /**
     * The factor to use in order to calculate the actual delay time between each successive retry -- multiplying by the value of the retryDelay.
     * @type {number}
     * @memberof TaskExecutionModel
     */
    readonly retryDelayFactor?: number;
    /**
     * The time when a task instance was started.
     * @type {Date}
     * @memberof TaskExecutionModel
     */
    readonly startDate: Date;
    /**
     * The current status of a task.
     * @type {string}
     * @memberof TaskExecutionModel
     */
    readonly status: TaskExecutionModelStatusEnum;
    /**
     * The numeric order of the task in the workflow.
     * @type {number}
     * @memberof TaskExecutionModel
     */
    readonly taskNumber?: number;
    /**
     * The calculated retry delay. i.e. delay * retryAttempts * retryDelayFactor.
     * @type {number}
     * @memberof TaskExecutionModel
     */
    readonly retryDelayMillis?: number;
    /**
     * 
     * @type {WorkflowTaskModel}
     * @memberof TaskExecutionModel
     */
    workflowTask?: WorkflowTaskModel;
    /**
     * The type of the task.
     * @type {string}
     * @memberof TaskExecutionModel
     */
    readonly type?: string;
}


/**
 * @export
 */
export const TaskExecutionModelStatusEnum = {
    Created: 'CREATED',
    Started: 'STARTED',
    Failed: 'FAILED',
    Cancelled: 'CANCELLED',
    Completed: 'COMPLETED'
} as const;
export type TaskExecutionModelStatusEnum = typeof TaskExecutionModelStatusEnum[keyof typeof TaskExecutionModelStatusEnum];


/**
 * Check if a given object implements the TaskExecutionModel interface.
 */
export function instanceOfTaskExecutionModel(value: object): boolean {
    let isInstance = true;
    isInstance = isInstance && "jobId" in value;
    isInstance = isInstance && "priority" in value;
    isInstance = isInstance && "startDate" in value;
    isInstance = isInstance && "status" in value;

    return isInstance;
}

export function TaskExecutionModelFromJSON(json: any): TaskExecutionModel {
    return TaskExecutionModelFromJSONTyped(json, false);
}

export function TaskExecutionModelFromJSONTyped(json: any, ignoreDiscriminator: boolean): TaskExecutionModel {
    if ((json === undefined) || (json === null)) {
        return json;
    }
    return {
        
        'componentDefinition': !exists(json, 'componentDefinition') ? undefined : ComponentDefinitionModelFromJSON(json['componentDefinition']),
        'createdBy': !exists(json, 'createdBy') ? undefined : json['createdBy'],
        'createdDate': !exists(json, 'createdDate') ? undefined : (new Date(json['createdDate'])),
        'endDate': !exists(json, 'endDate') ? undefined : (new Date(json['endDate'])),
        'error': !exists(json, 'error') ? undefined : ExecutionErrorModelFromJSON(json['error']),
        'executionTime': !exists(json, 'executionTime') ? undefined : json['executionTime'],
        'id': !exists(json, 'id') ? undefined : json['id'],
        'input': !exists(json, 'input') ? undefined : json['input'],
        'jobId': json['jobId'],
        'lastModifiedBy': !exists(json, 'lastModifiedBy') ? undefined : json['lastModifiedBy'],
        'lastModifiedDate': !exists(json, 'lastModifiedDate') ? undefined : (new Date(json['lastModifiedDate'])),
        'output': !exists(json, 'output') ? undefined : json['output'],
        'parentId': !exists(json, 'parentId') ? undefined : json['parentId'],
        'priority': json['priority'],
        'progress': !exists(json, 'progress') ? undefined : json['progress'],
        'maxRetries': !exists(json, 'maxRetries') ? undefined : json['maxRetries'],
        'retryAttempts': !exists(json, 'retryAttempts') ? undefined : json['retryAttempts'],
        'retryDelay': !exists(json, 'retryDelay') ? undefined : json['retryDelay'],
        'retryDelayFactor': !exists(json, 'retryDelayFactor') ? undefined : json['retryDelayFactor'],
        'startDate': (new Date(json['startDate'])),
        'status': json['status'],
        'taskNumber': !exists(json, 'taskNumber') ? undefined : json['taskNumber'],
        'retryDelayMillis': !exists(json, 'retryDelayMillis') ? undefined : json['retryDelayMillis'],
        'workflowTask': !exists(json, 'workflowTask') ? undefined : WorkflowTaskModelFromJSON(json['workflowTask']),
        'type': !exists(json, 'type') ? undefined : json['type'],
    };
}

export function TaskExecutionModelToJSON(value?: TaskExecutionModel | null): any {
    if (value === undefined) {
        return undefined;
    }
    if (value === null) {
        return null;
    }
    return {
        
        'componentDefinition': ComponentDefinitionModelToJSON(value.componentDefinition),
        'error': ExecutionErrorModelToJSON(value.error),
        'workflowTask': WorkflowTaskModelToJSON(value.workflowTask),
    };
}

