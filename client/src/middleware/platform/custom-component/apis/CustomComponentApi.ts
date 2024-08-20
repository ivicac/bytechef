/* tslint:disable */
/* eslint-disable */
/**
 * The Platform Custom Component Internal API
 * No description provided (generated by Openapi Generator https://github.com/openapitools/openapi-generator)
 *
 * The version of the OpenAPI document: 1
 * 
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */


import * as runtime from '../runtime';
import type {
  CustomComponentModel,
} from '../models/index';
import {
    CustomComponentModelFromJSON,
    CustomComponentModelToJSON,
} from '../models/index';

export interface DeleteCustomComponentRequest {
    id: number;
}

export interface EnableCustomComponentRequest {
    id: number;
    enable: boolean;
}

export interface GetCustomComponentRequest {
    id: number;
}

/**
 * 
 */
export class CustomComponentApi extends runtime.BaseAPI {

    /**
     * Delete an custom component.
     * Delete an custom component
     */
    async deleteCustomComponentRaw(requestParameters: DeleteCustomComponentRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling deleteCustomComponent().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/custom-components/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Delete an custom component.
     * Delete an custom component
     */
    async deleteCustomComponent(requestParameters: DeleteCustomComponentRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.deleteCustomComponentRaw(requestParameters, initOverrides);
    }

    /**
     * Enable/disable a custom component.
     * Enable/disable a custom component.
     */
    async enableCustomComponentRaw(requestParameters: EnableCustomComponentRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling enableCustomComponent().'
            );
        }

        if (requestParameters['enable'] == null) {
            throw new runtime.RequiredError(
                'enable',
                'Required parameter "enable" was null or undefined when calling enableCustomComponent().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/custom-components/{id}/enable/{enable}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))).replace(`{${"enable"}}`, encodeURIComponent(String(requestParameters['enable']))),
            method: 'PATCH',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Enable/disable a custom component.
     * Enable/disable a custom component.
     */
    async enableCustomComponent(requestParameters: EnableCustomComponentRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.enableCustomComponentRaw(requestParameters, initOverrides);
    }

    /**
     * Get an custom component by id.
     * Get an custom component by id
     */
    async getCustomComponentRaw(requestParameters: GetCustomComponentRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<CustomComponentModel>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling getCustomComponent().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/custom-components/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CustomComponentModelFromJSON(jsonValue));
    }

    /**
     * Get an custom component by id.
     * Get an custom component by id
     */
    async getCustomComponent(requestParameters: GetCustomComponentRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<CustomComponentModel> {
        const response = await this.getCustomComponentRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Get Custom Components.
     * Get Custom Components
     */
    async getCustomComponentsRaw(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Array<CustomComponentModel>>> {
        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/custom-components`,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => jsonValue.map(CustomComponentModelFromJSON));
    }

    /**
     * Get Custom Components.
     * Get Custom Components
     */
    async getCustomComponents(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Array<CustomComponentModel>> {
        const response = await this.getCustomComponentsRaw(initOverrides);
        return await response.value();
    }

}
