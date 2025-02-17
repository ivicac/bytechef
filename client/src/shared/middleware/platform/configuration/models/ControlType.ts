/* tslint:disable */
/* eslint-disable */
/**
 * The Platform Configuration Internal API
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
 * A type of the control to show in UI.
 * @export
 */
export const ControlType = {
    ArrayBuilder: 'ARRAY_BUILDER',
    CodeEditor: 'CODE_EDITOR',
    Date: 'DATE',
    DateTime: 'DATE_TIME',
    Email: 'EMAIL',
    Integer: 'INTEGER',
    JsonSchemaBuilder: 'JSON_SCHEMA_BUILDER',
    FileEntry: 'FILE_ENTRY',
    MultiSelect: 'MULTI_SELECT',
    Number: 'NUMBER',
    Null: 'NULL',
    ObjectBuilder: 'OBJECT_BUILDER',
    Password: 'PASSWORD',
    Phone: 'PHONE',
    RichText: 'RICH_TEXT',
    Select: 'SELECT',
    Text: 'TEXT',
    TextArea: 'TEXT_AREA',
    Time: 'TIME',
    Url: 'URL'
} as const;
export type ControlType = typeof ControlType[keyof typeof ControlType];


export function instanceOfControlType(value: any): boolean {
    for (const key in ControlType) {
        if (Object.prototype.hasOwnProperty.call(ControlType, key)) {
            if (ControlType[key as keyof typeof ControlType] === value) {
                return true;
            }
        }
    }
    return false;
}

export function ControlTypeFromJSON(json: any): ControlType {
    return ControlTypeFromJSONTyped(json, false);
}

export function ControlTypeFromJSONTyped(json: any, ignoreDiscriminator: boolean): ControlType {
    return json as ControlType;
}

export function ControlTypeToJSON(value?: ControlType | null): any {
    return value as any;
}

export function ControlTypeToJSONTyped(value: any, ignoreDiscriminator: boolean): ControlType {
    return value as ControlType;
}

