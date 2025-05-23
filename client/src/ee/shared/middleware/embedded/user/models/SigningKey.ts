/* tslint:disable */
/* eslint-disable */
/**
 * The Embedded User Internal API
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
 * Contains generated public key used for signing JWT tokens.
 * @export
 * @interface SigningKey
 */
export interface SigningKey {
    /**
     * The created by.
     * @type {string}
     * @memberof SigningKey
     */
    readonly createdBy?: string;
    /**
     * The created date.
     * @type {Date}
     * @memberof SigningKey
     */
    readonly createdDate?: Date;
    /**
     * The id of a public key.
     * @type {number}
     * @memberof SigningKey
     */
    readonly id?: number;
    /**
     * The id of a key used for identifying corresponding private key when validating the JWT token.
     * @type {string}
     * @memberof SigningKey
     */
    readonly keyId: string;
    /**
     * The last modified by.
     * @type {string}
     * @memberof SigningKey
     */
    readonly lastModifiedBy?: string;
    /**
     * The last modified date.
     * @type {Date}
     * @memberof SigningKey
     */
    readonly lastModifiedDate?: Date;
    /**
     * The last used date.
     * @type {Date}
     * @memberof SigningKey
     */
    readonly lastUsedDate?: Date;
    /**
     * The name of a public key.
     * @type {string}
     * @memberof SigningKey
     */
    name: string;
}

/**
 * Check if a given object implements the SigningKey interface.
 */
export function instanceOfSigningKey(value: object): value is SigningKey {
    if (!('keyId' in value) || value['keyId'] === undefined) return false;
    if (!('name' in value) || value['name'] === undefined) return false;
    return true;
}

export function SigningKeyFromJSON(json: any): SigningKey {
    return SigningKeyFromJSONTyped(json, false);
}

export function SigningKeyFromJSONTyped(json: any, ignoreDiscriminator: boolean): SigningKey {
    if (json == null) {
        return json;
    }
    return {
        
        'createdBy': json['createdBy'] == null ? undefined : json['createdBy'],
        'createdDate': json['createdDate'] == null ? undefined : (new Date(json['createdDate'])),
        'id': json['id'] == null ? undefined : json['id'],
        'keyId': json['keyId'],
        'lastModifiedBy': json['lastModifiedBy'] == null ? undefined : json['lastModifiedBy'],
        'lastModifiedDate': json['lastModifiedDate'] == null ? undefined : (new Date(json['lastModifiedDate'])),
        'lastUsedDate': json['lastUsedDate'] == null ? undefined : (new Date(json['lastUsedDate'])),
        'name': json['name'],
    };
}

export function SigningKeyToJSON(json: any): SigningKey {
    return SigningKeyToJSONTyped(json, false);
}

export function SigningKeyToJSONTyped(value?: Omit<SigningKey, 'createdBy'|'createdDate'|'id'|'keyId'|'lastModifiedBy'|'lastModifiedDate'|'lastUsedDate'> | null, ignoreDiscriminator: boolean = false): any {
    if (value == null) {
        return value;
    }

    return {
        
        'name': value['name'],
    };
}

