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


/**
 * The endpoint HTTP method.
 * @export
 */
export const HttpMethodModel = {
    Delete: 'DELETE',
    Get: 'GET',
    Patch: 'PATCH',
    Post: 'POST',
    Put: 'PUT'
} as const;
export type HttpMethodModel = typeof HttpMethodModel[keyof typeof HttpMethodModel];


export function instanceOfHttpMethodModel(value: any): boolean {
    return Object.values(HttpMethodModel).includes(value);
}

export function HttpMethodModelFromJSON(json: any): HttpMethodModel {
    return HttpMethodModelFromJSONTyped(json, false);
}

export function HttpMethodModelFromJSONTyped(json: any, ignoreDiscriminator: boolean): HttpMethodModel {
    return json as HttpMethodModel;
}

export function HttpMethodModelToJSON(value?: HttpMethodModel | null): any {
    return value as any;
}
