/* tslint:disable */
/* eslint-disable */
/**
 * The Embedded Configuration Internal API
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
  IntegrationModel,
  IntegrationStatusModel,
  IntegrationVersionModel,
  PublishIntegrationRequestModel,
  WorkflowModel,
} from '../models/index';
import {
    IntegrationModelFromJSON,
    IntegrationModelToJSON,
    IntegrationStatusModelFromJSON,
    IntegrationStatusModelToJSON,
    IntegrationVersionModelFromJSON,
    IntegrationVersionModelToJSON,
    PublishIntegrationRequestModelFromJSON,
    PublishIntegrationRequestModelToJSON,
    WorkflowModelFromJSON,
    WorkflowModelToJSON,
} from '../models/index';

export interface CreateIntegrationRequest {
    integrationModel: IntegrationModel;
}

export interface CreateIntegrationWorkflowRequest {
    id: number;
    workflowModel: WorkflowModel;
}

export interface DeleteIntegrationRequest {
    id: number;
}

export interface GetIntegrationRequest {
    id: number;
}

export interface GetIntegrationVersionsRequest {
    id: number;
}

export interface GetIntegrationsRequest {
    categoryId?: number;
    integrationInstanceConfigurations?: boolean;
    tagId?: number;
    status?: IntegrationStatusModel;
}

export interface PublishIntegrationRequest {
    id: number;
    publishIntegrationRequestModel?: PublishIntegrationRequestModel;
}

export interface UpdateIntegrationRequest {
    id: number;
    integrationModel: IntegrationModel;
}

/**
 * 
 */
export class IntegrationApi extends runtime.BaseAPI {

    /**
     * Create a new integration.
     * Create a new integration
     */
    async createIntegrationRaw(requestParameters: CreateIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<IntegrationModel>> {
        if (requestParameters['integrationModel'] == null) {
            throw new runtime.RequiredError(
                'integrationModel',
                'Required parameter "integrationModel" was null or undefined when calling createIntegration().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/integrations`,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: IntegrationModelToJSON(requestParameters['integrationModel']),
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => IntegrationModelFromJSON(jsonValue));
    }

    /**
     * Create a new integration.
     * Create a new integration
     */
    async createIntegration(requestParameters: CreateIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<IntegrationModel> {
        const response = await this.createIntegrationRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Create new workflow and adds it to an existing integration.
     * Create new workflow and adds it to an existing integration
     */
    async createIntegrationWorkflowRaw(requestParameters: CreateIntegrationWorkflowRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<WorkflowModel>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling createIntegrationWorkflow().'
            );
        }

        if (requestParameters['workflowModel'] == null) {
            throw new runtime.RequiredError(
                'workflowModel',
                'Required parameter "workflowModel" was null or undefined when calling createIntegrationWorkflow().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/integrations/{id}/workflows`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: WorkflowModelToJSON(requestParameters['workflowModel']),
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => WorkflowModelFromJSON(jsonValue));
    }

    /**
     * Create new workflow and adds it to an existing integration.
     * Create new workflow and adds it to an existing integration
     */
    async createIntegrationWorkflow(requestParameters: CreateIntegrationWorkflowRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<WorkflowModel> {
        const response = await this.createIntegrationWorkflowRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Delete an integration.
     * Delete an integration
     */
    async deleteIntegrationRaw(requestParameters: DeleteIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling deleteIntegration().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/integrations/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Delete an integration.
     * Delete an integration
     */
    async deleteIntegration(requestParameters: DeleteIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.deleteIntegrationRaw(requestParameters, initOverrides);
    }

    /**
     * Get an integration by id.
     * Get an integration by id
     */
    async getIntegrationRaw(requestParameters: GetIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<IntegrationModel>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling getIntegration().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/integrations/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => IntegrationModelFromJSON(jsonValue));
    }

    /**
     * Get an integration by id.
     * Get an integration by id
     */
    async getIntegration(requestParameters: GetIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<IntegrationModel> {
        const response = await this.getIntegrationRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Get a integration versions.
     * Get a integration versions.
     */
    async getIntegrationVersionsRaw(requestParameters: GetIntegrationVersionsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Array<IntegrationVersionModel>>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling getIntegrationVersions().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/integrations/{id}/versions`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => jsonValue.map(IntegrationVersionModelFromJSON));
    }

    /**
     * Get a integration versions.
     * Get a integration versions.
     */
    async getIntegrationVersions(requestParameters: GetIntegrationVersionsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Array<IntegrationVersionModel>> {
        const response = await this.getIntegrationVersionsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Get integrations.
     * Get integrations
     */
    async getIntegrationsRaw(requestParameters: GetIntegrationsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Array<IntegrationModel>>> {
        const queryParameters: any = {};

        if (requestParameters['categoryId'] != null) {
            queryParameters['categoryId'] = requestParameters['categoryId'];
        }

        if (requestParameters['integrationInstanceConfigurations'] != null) {
            queryParameters['integrationInstanceConfigurations'] = requestParameters['integrationInstanceConfigurations'];
        }

        if (requestParameters['tagId'] != null) {
            queryParameters['tagId'] = requestParameters['tagId'];
        }

        if (requestParameters['status'] != null) {
            queryParameters['status'] = requestParameters['status'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/integrations`,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => jsonValue.map(IntegrationModelFromJSON));
    }

    /**
     * Get integrations.
     * Get integrations
     */
    async getIntegrations(requestParameters: GetIntegrationsRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Array<IntegrationModel>> {
        const response = await this.getIntegrationsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Publishes existing integration.
     * Publishes existing integration.
     */
    async publishIntegrationRaw(requestParameters: PublishIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling publishIntegration().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/integrations/{id}/publish`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: PublishIntegrationRequestModelToJSON(requestParameters['publishIntegrationRequestModel']),
        }, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Publishes existing integration.
     * Publishes existing integration.
     */
    async publishIntegration(requestParameters: PublishIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.publishIntegrationRaw(requestParameters, initOverrides);
    }

    /**
     * Update an existing integration.
     * Update an existing integration
     */
    async updateIntegrationRaw(requestParameters: UpdateIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<IntegrationModel>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling updateIntegration().'
            );
        }

        if (requestParameters['integrationModel'] == null) {
            throw new runtime.RequiredError(
                'integrationModel',
                'Required parameter "integrationModel" was null or undefined when calling updateIntegration().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/integrations/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'PUT',
            headers: headerParameters,
            query: queryParameters,
            body: IntegrationModelToJSON(requestParameters['integrationModel']),
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => IntegrationModelFromJSON(jsonValue));
    }

    /**
     * Update an existing integration.
     * Update an existing integration
     */
    async updateIntegration(requestParameters: UpdateIntegrationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<IntegrationModel> {
        const response = await this.updateIntegrationRaw(requestParameters, initOverrides);
        return await response.value();
    }

}
