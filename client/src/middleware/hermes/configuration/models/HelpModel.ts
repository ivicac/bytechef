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
/**
 * The help text that is meant to guide your users as to how to configure this action or trigger.
 * @export
 * @interface HelpModel
 */
export interface HelpModel {
    /**
     * The help text
     * @type {string}
     * @memberof HelpModel
     */
    body: string;
    /**
     * The url to additional documentation
     * @type {string}
     * @memberof HelpModel
     */
    learnMoreUrl?: string;
}

/**
 * Check if a given object implements the HelpModel interface.
 */
export function instanceOfHelpModel(value: object): boolean {
    let isInstance = true;
    isInstance = isInstance && "body" in value;

    return isInstance;
}

export function HelpModelFromJSON(json: any): HelpModel {
    return HelpModelFromJSONTyped(json, false);
}

export function HelpModelFromJSONTyped(json: any, ignoreDiscriminator: boolean): HelpModel {
    if ((json === undefined) || (json === null)) {
        return json;
    }
    return {
        
        'body': json['body'],
        'learnMoreUrl': !exists(json, 'learnMoreUrl') ? undefined : json['learnMoreUrl'],
    };
}

export function HelpModelToJSON(value?: HelpModel | null): any {
    if (value === undefined) {
        return undefined;
    }
    if (value === null) {
        return null;
    }
    return {
        
        'body': value.body,
        'learnMoreUrl': value.learnMoreUrl,
    };
}

