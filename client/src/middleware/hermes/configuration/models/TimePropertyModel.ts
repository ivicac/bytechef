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
import type { ControlTypeModel } from './ControlTypeModel';
import {
    ControlTypeModelFromJSON,
    ControlTypeModelFromJSONTyped,
    ControlTypeModelToJSON,
} from './ControlTypeModel';
import type { OptionModel } from './OptionModel';
import {
    OptionModelFromJSON,
    OptionModelFromJSONTyped,
    OptionModelToJSON,
} from './OptionModel';
import type { OptionsDataSourceModel } from './OptionsDataSourceModel';
import {
    OptionsDataSourceModelFromJSON,
    OptionsDataSourceModelFromJSONTyped,
    OptionsDataSourceModelToJSON,
} from './OptionsDataSourceModel';
import type { PropertyTypeModel } from './PropertyTypeModel';
import {
    PropertyTypeModelFromJSON,
    PropertyTypeModelFromJSONTyped,
    PropertyTypeModelToJSON,
} from './PropertyTypeModel';
import type { ValuePropertyModel } from './ValuePropertyModel';
import {
    ValuePropertyModelFromJSON,
    ValuePropertyModelFromJSONTyped,
    ValuePropertyModelToJSON,
} from './ValuePropertyModel';

/**
 * A time property.
 * @export
 * @interface TimePropertyModel
 */
export interface TimePropertyModel extends ValuePropertyModel {
    /**
     * The property default value.
     * @type {string}
     * @memberof TimePropertyModel
     */
    defaultValue?: string;
    /**
     * The property sample value.
     * @type {string}
     * @memberof TimePropertyModel
     */
    exampleValue?: string;
    /**
     * The list of valid property options.
     * @type {Array<OptionModel>}
     * @memberof TimePropertyModel
     */
    options?: Array<OptionModel>;
    /**
     * 
     * @type {OptionsDataSourceModel}
     * @memberof TimePropertyModel
     */
    optionsDataSource?: OptionsDataSourceModel;
}

/**
 * Check if a given object implements the TimePropertyModel interface.
 */
export function instanceOfTimePropertyModel(value: object): boolean {
    let isInstance = true;

    return isInstance;
}

export function TimePropertyModelFromJSON(json: any): TimePropertyModel {
    return TimePropertyModelFromJSONTyped(json, false);
}

export function TimePropertyModelFromJSONTyped(json: any, ignoreDiscriminator: boolean): TimePropertyModel {
    if ((json === undefined) || (json === null)) {
        return json;
    }
    return {
        ...ValuePropertyModelFromJSONTyped(json, ignoreDiscriminator),
        'defaultValue': !exists(json, 'defaultValue') ? undefined : json['defaultValue'],
        'exampleValue': !exists(json, 'exampleValue') ? undefined : json['exampleValue'],
        'options': !exists(json, 'options') ? undefined : ((json['options'] as Array<any>).map(OptionModelFromJSON)),
        'optionsDataSource': !exists(json, 'optionsDataSource') ? undefined : OptionsDataSourceModelFromJSON(json['optionsDataSource']),
    };
}

export function TimePropertyModelToJSON(value?: TimePropertyModel | null): any {
    if (value === undefined) {
        return undefined;
    }
    if (value === null) {
        return null;
    }
    return {
        ...ValuePropertyModelToJSON(value),
        'defaultValue': value.defaultValue,
        'exampleValue': value.exampleValue,
        'options': value.options === undefined ? undefined : ((value.options as Array<any>).map(OptionModelToJSON)),
        'optionsDataSource': OptionsDataSourceModelToJSON(value.optionsDataSource),
    };
}

