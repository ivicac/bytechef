/* tslint:disable */
/* eslint-disable */
/**
 * The Automation API Platform Internal API
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
/**
 * Contains generated key required for calling API.
 * @export
 * @interface ApiClient
 */
export interface ApiClient {
    /**
     * The created by.
     * @type {string}
     * @memberof ApiClient
     */
    readonly createdBy?: string;
    /**
     * The created date.
     * @type {Date}
     * @memberof ApiClient
     */
    readonly createdDate?: Date;
    /**
     * The id of an API key.
     * @type {number}
     * @memberof ApiClient
     */
    readonly id?: number;
    /**
     * The last modified by.
     * @type {string}
     * @memberof ApiClient
     */
    readonly lastModifiedBy?: string;
    /**
     * The last modified date.
     * @type {Date}
     * @memberof ApiClient
     */
    readonly lastModifiedDate?: Date;
    /**
     * The last used date.
     * @type {Date}
     * @memberof ApiClient
     */
    readonly lastUsedDate?: Date;
    /**
     * The name of an API key.
     * @type {string}
     * @memberof ApiClient
     */
    name: string;
    /**
     * The preview of secret API key.
     * @type {string}
     * @memberof ApiClient
     */
    readonly secretKey: string;
}

/**
 * Check if a given object implements the ApiClient interface.
 */
export function instanceOfApiClient(value: object): value is ApiClient {
    if (!('name' in value) || value['name'] === undefined) return false;
    if (!('secretKey' in value) || value['secretKey'] === undefined) return false;
    return true;
}

export function ApiClientFromJSON(json: any): ApiClient {
    return ApiClientFromJSONTyped(json, false);
}

export function ApiClientFromJSONTyped(json: any, ignoreDiscriminator: boolean): ApiClient {
    if (json == null) {
        return json;
    }
    return {
        
        'createdBy': json['createdBy'] == null ? undefined : json['createdBy'],
        'createdDate': json['createdDate'] == null ? undefined : (new Date(json['createdDate'])),
        'id': json['id'] == null ? undefined : json['id'],
        'lastModifiedBy': json['lastModifiedBy'] == null ? undefined : json['lastModifiedBy'],
        'lastModifiedDate': json['lastModifiedDate'] == null ? undefined : (new Date(json['lastModifiedDate'])),
        'lastUsedDate': json['lastUsedDate'] == null ? undefined : (new Date(json['lastUsedDate'])),
        'name': json['name'],
        'secretKey': json['secretKey'],
    };
}

export function ApiClientToJSON(json: any): ApiClient {
    return ApiClientToJSONTyped(json, false);
}

export function ApiClientToJSONTyped(value?: Omit<ApiClient, 'createdBy'|'createdDate'|'id'|'lastModifiedBy'|'lastModifiedDate'|'lastUsedDate'|'secretKey'> | null, ignoreDiscriminator: boolean = false): any {
    if (value == null) {
        return value;
    }

    return {
        
        'name': value['name'],
    };
}

