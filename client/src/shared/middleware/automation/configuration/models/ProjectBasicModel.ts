/* tslint:disable */
/* eslint-disable */
/**
 * The Automation Configuration Internal API
 * No description provided (generated by Openapi Generator https://github.com/openapitools/openapi-generator)
 *
 * The version of the OpenAPI document: 1
 * 
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */

import { mapValues } from '../runtime';
import type { ProjectStatusModel } from './ProjectStatusModel';
import {
    ProjectStatusModelFromJSON,
    ProjectStatusModelFromJSONTyped,
    ProjectStatusModelToJSON,
} from './ProjectStatusModel';

/**
 * A group of workflows that make one logical project.
 * @export
 * @interface ProjectBasicModel
 */
export interface ProjectBasicModel {
    /**
     * The created by.
     * @type {string}
     * @memberof ProjectBasicModel
     */
    readonly createdBy?: string;
    /**
     * The created date.
     * @type {Date}
     * @memberof ProjectBasicModel
     */
    readonly createdDate?: Date;
    /**
     * The description of a project.
     * @type {string}
     * @memberof ProjectBasicModel
     */
    description?: string;
    /**
     * The id of a project.
     * @type {number}
     * @memberof ProjectBasicModel
     */
    readonly id?: number;
    /**
     * The last modified by.
     * @type {string}
     * @memberof ProjectBasicModel
     */
    readonly lastModifiedBy?: string;
    /**
     * The last modified date.
     * @type {Date}
     * @memberof ProjectBasicModel
     */
    readonly lastModifiedDate?: Date;
    /**
     * The name of a project.
     * @type {string}
     * @memberof ProjectBasicModel
     */
    name: string;
    /**
     * The last published date.
     * @type {Date}
     * @memberof ProjectBasicModel
     */
    readonly lastPublishedDate?: Date;
    /**
     * 
     * @type {ProjectStatusModel}
     * @memberof ProjectBasicModel
     */
    lastStatus?: ProjectStatusModel;
    /**
     * The last version of a project.
     * @type {number}
     * @memberof ProjectBasicModel
     */
    readonly lastProjectVersion?: number;
}

/**
 * Check if a given object implements the ProjectBasicModel interface.
 */
export function instanceOfProjectBasicModel(value: object): boolean {
    if (!('name' in value)) return false;
    return true;
}

export function ProjectBasicModelFromJSON(json: any): ProjectBasicModel {
    return ProjectBasicModelFromJSONTyped(json, false);
}

export function ProjectBasicModelFromJSONTyped(json: any, ignoreDiscriminator: boolean): ProjectBasicModel {
    if (json == null) {
        return json;
    }
    return {
        
        'createdBy': json['createdBy'] == null ? undefined : json['createdBy'],
        'createdDate': json['createdDate'] == null ? undefined : (new Date(json['createdDate'])),
        'description': json['description'] == null ? undefined : json['description'],
        'id': json['id'] == null ? undefined : json['id'],
        'lastModifiedBy': json['lastModifiedBy'] == null ? undefined : json['lastModifiedBy'],
        'lastModifiedDate': json['lastModifiedDate'] == null ? undefined : (new Date(json['lastModifiedDate'])),
        'name': json['name'],
        'lastPublishedDate': json['lastPublishedDate'] == null ? undefined : (new Date(json['lastPublishedDate'])),
        'lastStatus': json['lastStatus'] == null ? undefined : ProjectStatusModelFromJSON(json['lastStatus']),
        'lastProjectVersion': json['lastProjectVersion'] == null ? undefined : json['lastProjectVersion'],
    };
}

export function ProjectBasicModelToJSON(value?: Omit<ProjectBasicModel, 'createdBy'|'createdDate'|'id'|'lastModifiedBy'|'lastModifiedDate'|'lastPublishedDate'|'lastProjectVersion'> | null): any {
    if (value == null) {
        return value;
    }
    return {
        
        'description': value['description'],
        'name': value['name'],
        'lastStatus': ProjectStatusModelToJSON(value['lastStatus']),
    };
}

