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


import * as runtime from '../runtime';
import type {
  Project,
  ProjectStatus,
  ProjectVersion,
  PublishProjectRequest,
} from '../models/index';
import {
    ProjectFromJSON,
    ProjectToJSON,
    ProjectStatusFromJSON,
    ProjectStatusToJSON,
    ProjectVersionFromJSON,
    ProjectVersionToJSON,
    PublishProjectRequestFromJSON,
    PublishProjectRequestToJSON,
} from '../models/index';

export interface CreateProjectRequest {
    project: Project;
}

export interface DeleteProjectRequest {
    id: number;
}

export interface DuplicateProjectRequest {
    id: number;
}

export interface GetProjectRequest {
    id: number;
}

export interface GetProjectVersionsRequest {
    id: number;
}

export interface GetWorkspaceProjectsRequest {
    id: number;
    projectDeployments?: boolean;
    categoryId?: number;
    tagId?: number;
    status?: ProjectStatus;
    includeAllFields?: boolean;
}

export interface PublishProjectOperationRequest {
    id: number;
    publishProjectRequest?: PublishProjectRequest;
}

export interface UpdateProjectRequest {
    id: number;
    project: Project;
}

/**
 * 
 */
export class ProjectApi extends runtime.BaseAPI {

    /**
     * Create a new project.
     * Create a new project.
     */
    async createProjectRaw(requestParameters: CreateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<number>> {
        if (requestParameters['project'] == null) {
            throw new runtime.RequiredError(
                'project',
                'Required parameter "project" was null or undefined when calling createProject().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/projects`,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: ProjectToJSON(requestParameters['project']),
        }, initOverrides);

        if (this.isJsonMime(response.headers.get('content-type'))) {
            return new runtime.JSONApiResponse<number>(response);
        } else {
            return new runtime.TextApiResponse(response) as any;
        }
    }

    /**
     * Create a new project.
     * Create a new project.
     */
    async createProject(requestParameters: CreateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<number> {
        const response = await this.createProjectRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Delete a project.
     * Delete a project.
     */
    async deleteProjectRaw(requestParameters: DeleteProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling deleteProject().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/projects/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Delete a project.
     * Delete a project.
     */
    async deleteProject(requestParameters: DeleteProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.deleteProjectRaw(requestParameters, initOverrides);
    }

    /**
     * Duplicates existing project.
     * Duplicates existing project.
     */
    async duplicateProjectRaw(requestParameters: DuplicateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Project>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling duplicateProject().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/projects/{id}/duplicate`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ProjectFromJSON(jsonValue));
    }

    /**
     * Duplicates existing project.
     * Duplicates existing project.
     */
    async duplicateProject(requestParameters: DuplicateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Project> {
        const response = await this.duplicateProjectRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Get a project by id.
     * Get a project by id.
     */
    async getProjectRaw(requestParameters: GetProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Project>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling getProject().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/projects/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ProjectFromJSON(jsonValue));
    }

    /**
     * Get a project by id.
     * Get a project by id.
     */
    async getProject(requestParameters: GetProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Project> {
        const response = await this.getProjectRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Get a project versions.
     * Get a project versions.
     */
    async getProjectVersionsRaw(requestParameters: GetProjectVersionsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Array<ProjectVersion>>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling getProjectVersions().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/projects/{id}/versions`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => jsonValue.map(ProjectVersionFromJSON));
    }

    /**
     * Get a project versions.
     * Get a project versions.
     */
    async getProjectVersions(requestParameters: GetProjectVersionsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Array<ProjectVersion>> {
        const response = await this.getProjectVersionsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Get projects by workspace id.
     * Get projects by workspace id
     */
    async getWorkspaceProjectsRaw(requestParameters: GetWorkspaceProjectsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Array<Project>>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling getWorkspaceProjects().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['projectDeployments'] != null) {
            queryParameters['projectDeployments'] = requestParameters['projectDeployments'];
        }

        if (requestParameters['categoryId'] != null) {
            queryParameters['categoryId'] = requestParameters['categoryId'];
        }

        if (requestParameters['tagId'] != null) {
            queryParameters['tagId'] = requestParameters['tagId'];
        }

        if (requestParameters['status'] != null) {
            queryParameters['status'] = requestParameters['status'];
        }

        if (requestParameters['includeAllFields'] != null) {
            queryParameters['includeAllFields'] = requestParameters['includeAllFields'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/workspaces/{id}/projects`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => jsonValue.map(ProjectFromJSON));
    }

    /**
     * Get projects by workspace id.
     * Get projects by workspace id
     */
    async getWorkspaceProjects(requestParameters: GetWorkspaceProjectsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Array<Project>> {
        const response = await this.getWorkspaceProjectsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Publishes existing project.
     * Publishes existing project.
     */
    async publishProjectRaw(requestParameters: PublishProjectOperationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling publishProject().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/projects/{id}/publish`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: PublishProjectRequestToJSON(requestParameters['publishProjectRequest']),
        }, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Publishes existing project.
     * Publishes existing project.
     */
    async publishProject(requestParameters: PublishProjectOperationRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.publishProjectRaw(requestParameters, initOverrides);
    }

    /**
     * Update an existing project.
     * Update an existing project.
     */
    async updateProjectRaw(requestParameters: UpdateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        if (requestParameters['id'] == null) {
            throw new runtime.RequiredError(
                'id',
                'Required parameter "id" was null or undefined when calling updateProject().'
            );
        }

        if (requestParameters['project'] == null) {
            throw new runtime.RequiredError(
                'project',
                'Required parameter "project" was null or undefined when calling updateProject().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/projects/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters['id']))),
            method: 'PUT',
            headers: headerParameters,
            query: queryParameters,
            body: ProjectToJSON(requestParameters['project']),
        }, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Update an existing project.
     * Update an existing project.
     */
    async updateProject(requestParameters: UpdateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.updateProjectRaw(requestParameters, initOverrides);
    }

}
