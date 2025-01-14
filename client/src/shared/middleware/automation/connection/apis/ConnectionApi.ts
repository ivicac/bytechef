/* tslint:disable */
/* eslint-disable */
/**
 * The Automation Connection Internal API
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
  ConnectionEnvironmentModel,
  ConnectionModel,
} from '../models/index';
import {
    ConnectionEnvironmentModelFromJSON,
    ConnectionEnvironmentModelToJSON,
    ConnectionModelFromJSON,
    ConnectionModelToJSON,
} from '../models/index';

export interface CreateConnectionRequest {
    connectionModel: ConnectionModel;
}

export interface DeleteConnectionRequest {
    id: number;
}

export interface GetConnectionRequest {
    id: number;
}

export interface GetWorkspaceConnectionsRequest {
    id: number;
    componentName?: string;
    connectionVersion?: number;
    environment?: ConnectionEnvironmentModel;
    tagId?: number;
}

export interface UpdateConnectionRequest {
    id: number;
    connectionModel: ConnectionModel;
}

/**
 * 
 */
export class ConnectionApi extends runtime.BaseAPI {

    /**
     * Create a new connection.
     * Create a new connection
     */
    async createConnectionRaw(requestParameters: CreateConnectionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ConnectionModel>> {
        if (requestParameters['connectionModel'] == null) {
            throw new runtime.RequiredError(
                'connectionModel',
                'Required parameter "connectionModel" was null or undefined when calling createConnection().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/connections`,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: ConnectionModelToJSON(requestParameters['connectionModel']),
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ConnectionModelFromJSON(jsonValue));
    }

    /**
     * Create a new connection.
     * Create a new connection
     */
    async createConnection(requestParameters: CreateConnectionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ConnectionModel> {
        const response = await this.createConnectionRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Delete a connection.
     * Delete a connection
     */
    async deleteConnectionRaw(requestParameters: DeleteConnectionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling deleteConnection().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/connections/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Delete a connection.
     * Delete a connection
     */
    async deleteConnection(requestParameters: DeleteConnectionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.deleteConnectionRaw(requestParameters, initOverrides);
    }

    /**
     * Get a connection by id.
     * Get a connection by id
     */
    async getConnectionRaw(requestParameters: GetConnectionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ConnectionModel>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling getConnection().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/connections/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ConnectionModelFromJSON(jsonValue));
    }

    /**
     * Get a connection by id.
     * Get a connection by id
     */
    async getConnection(requestParameters: GetConnectionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ConnectionModel> {
        const response = await this.getConnectionRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Get all workspace connections.
     * Get all workspace connections
     */
    async getWorkspaceConnectionsRaw(requestParameters: GetWorkspaceConnectionsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Array<ConnectionModel>>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling getWorkspaceConnections().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['componentName'] != null) {
            queryParameters['componentName'] = requestParameters['componentName'];
        }

        if (requestParameters['connectionVersion'] != null) {
            queryParameters['connectionVersion'] = requestParameters['connectionVersion'];
        }

        if (requestParameters['environment'] != null) {
            queryParameters['environment'] = requestParameters['environment'];
        }

        if (requestParameters['tagId'] != null) {
            queryParameters['tagId'] = requestParameters['tagId'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/workspaces/{id}/connections`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => jsonValue.map(ConnectionModelFromJSON));
    }

    /**
     * Get all workspace connections.
     * Get all workspace connections
     */
    async getWorkspaceConnections(requestParameters: GetWorkspaceConnectionsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Array<ConnectionModel>> {
        const response = await this.getWorkspaceConnectionsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Update an existing connection.
     * Update an existing connection
     */
    async updateConnectionRaw(requestParameters: UpdateConnectionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ConnectionModel>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling updateConnection().'
            );
        }

        if (requestParameters['connectionModel'] == null) {
            throw new runtime.RequiredError(
                'connectionModel',
                'Required parameter "connectionModel" was null or undefined when calling updateConnection().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/connections/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'PUT',
            headers: headerParameters,
            query: queryParameters,
            body: ConnectionModelToJSON(requestParameters['connectionModel']),
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ConnectionModelFromJSON(jsonValue));
    }

    /**
     * Update an existing connection.
     * Update an existing connection
     */
    async updateConnection(requestParameters: UpdateConnectionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ConnectionModel> {
        const response = await this.updateConnectionRaw(requestParameters, initOverrides);
        return await response.value();
    }

}
