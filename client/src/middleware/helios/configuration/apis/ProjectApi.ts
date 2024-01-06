/* tslint:disable */
/* eslint-disable */
/**
 * The Automation Configuration API
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
  ProjectModel,
  WorkflowModel,
} from '../models/index';
import {
    ProjectModelFromJSON,
    ProjectModelToJSON,
    WorkflowModelFromJSON,
    WorkflowModelToJSON,
} from '../models/index';

export interface CreateProjectRequest {
    projectModel: ProjectModel;
}

export interface CreateProjectWorkflowRequest {
    id: number;
    workflowModel: WorkflowModel;
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

export interface GetProjectsRequest {
    categoryId?: number;
    projectInstances?: boolean;
    tagId?: number;
    published?: boolean;
}

export interface PublishProjectRequest {
    id: number;
}

export interface UpdateProjectRequest {
    id: number;
    projectModel: ProjectModel;
}

/**
 * 
 */
export class ProjectApi extends runtime.BaseAPI {

    /**
     * Create a new project.
     * Create a new project.
     */
    async createProjectRaw(requestParameters: CreateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ProjectModel>> {
        if (requestParameters.projectModel === null || requestParameters.projectModel === undefined) {
            throw new runtime.RequiredError('projectModel','Required parameter requestParameters.projectModel was null or undefined when calling createProject.');
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/projects`,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: ProjectModelToJSON(requestParameters.projectModel),
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ProjectModelFromJSON(jsonValue));
    }

    /**
     * Create a new project.
     * Create a new project.
     */
    async createProject(requestParameters: CreateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ProjectModel> {
        const response = await this.createProjectRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Create new workflow and adds it to an existing project.
     * Create new workflow and adds it to an existing project.
     */
    async createProjectWorkflowRaw(requestParameters: CreateProjectWorkflowRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<WorkflowModel>> {
        if (requestParameters.id === null || requestParameters.id === undefined) {
            throw new runtime.RequiredError('id','Required parameter requestParameters.id was null or undefined when calling createProjectWorkflow.');
        }

        if (requestParameters.workflowModel === null || requestParameters.workflowModel === undefined) {
            throw new runtime.RequiredError('workflowModel','Required parameter requestParameters.workflowModel was null or undefined when calling createProjectWorkflow.');
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/projects/{id}/workflows`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters.id))),
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: WorkflowModelToJSON(requestParameters.workflowModel),
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => WorkflowModelFromJSON(jsonValue));
    }

    /**
     * Create new workflow and adds it to an existing project.
     * Create new workflow and adds it to an existing project.
     */
    async createProjectWorkflow(requestParameters: CreateProjectWorkflowRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<WorkflowModel> {
        const response = await this.createProjectWorkflowRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Delete a project.
     * Delete a project.
     */
    async deleteProjectRaw(requestParameters: DeleteProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        if (requestParameters.id === null || requestParameters.id === undefined) {
            throw new runtime.RequiredError('id','Required parameter requestParameters.id was null or undefined when calling deleteProject.');
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/projects/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters.id))),
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
    async duplicateProjectRaw(requestParameters: DuplicateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ProjectModel>> {
        if (requestParameters.id === null || requestParameters.id === undefined) {
            throw new runtime.RequiredError('id','Required parameter requestParameters.id was null or undefined when calling duplicateProject.');
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/projects/{id}/duplicate`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters.id))),
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ProjectModelFromJSON(jsonValue));
    }

    /**
     * Duplicates existing project.
     * Duplicates existing project.
     */
    async duplicateProject(requestParameters: DuplicateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ProjectModel> {
        const response = await this.duplicateProjectRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Get a project by id.
     * Get a project by id.
     */
    async getProjectRaw(requestParameters: GetProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ProjectModel>> {
        if (requestParameters.id === null || requestParameters.id === undefined) {
            throw new runtime.RequiredError('id','Required parameter requestParameters.id was null or undefined when calling getProject.');
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/projects/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters.id))),
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ProjectModelFromJSON(jsonValue));
    }

    /**
     * Get a project by id.
     * Get a project by id.
     */
    async getProject(requestParameters: GetProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ProjectModel> {
        const response = await this.getProjectRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Get projects.
     * Get projects.
     */
    async getProjectsRaw(requestParameters: GetProjectsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Array<ProjectModel>>> {
        const queryParameters: any = {};

        if (requestParameters.categoryId !== undefined) {
            queryParameters['categoryId'] = requestParameters.categoryId;
        }

        if (requestParameters.projectInstances !== undefined) {
            queryParameters['projectInstances'] = requestParameters.projectInstances;
        }

        if (requestParameters.tagId !== undefined) {
            queryParameters['tagId'] = requestParameters.tagId;
        }

        if (requestParameters.published !== undefined) {
            queryParameters['published'] = requestParameters.published;
        }

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/projects`,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => jsonValue.map(ProjectModelFromJSON));
    }

    /**
     * Get projects.
     * Get projects.
     */
    async getProjects(requestParameters: GetProjectsRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Array<ProjectModel>> {
        const response = await this.getProjectsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Publishes existing project.
     * Publishes existing project.
     */
    async publishProjectRaw(requestParameters: PublishProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ProjectModel>> {
        if (requestParameters.id === null || requestParameters.id === undefined) {
            throw new runtime.RequiredError('id','Required parameter requestParameters.id was null or undefined when calling publishProject.');
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        const response = await this.request({
            path: `/projects/{id}/publish`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters.id))),
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ProjectModelFromJSON(jsonValue));
    }

    /**
     * Publishes existing project.
     * Publishes existing project.
     */
    async publishProject(requestParameters: PublishProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ProjectModel> {
        const response = await this.publishProjectRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Update an existing project.
     * Update an existing project.
     */
    async updateProjectRaw(requestParameters: UpdateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ProjectModel>> {
        if (requestParameters.id === null || requestParameters.id === undefined) {
            throw new runtime.RequiredError('id','Required parameter requestParameters.id was null or undefined when calling updateProject.');
        }

        if (requestParameters.projectModel === null || requestParameters.projectModel === undefined) {
            throw new runtime.RequiredError('projectModel','Required parameter requestParameters.projectModel was null or undefined when calling updateProject.');
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        const response = await this.request({
            path: `/projects/{id}`.replace(`{${"id"}}`, encodeURIComponent(String(requestParameters.id))),
            method: 'PUT',
            headers: headerParameters,
            query: queryParameters,
            body: ProjectModelToJSON(requestParameters.projectModel),
        }, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ProjectModelFromJSON(jsonValue));
    }

    /**
     * Update an existing project.
     * Update an existing project.
     */
    async updateProject(requestParameters: UpdateProjectRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ProjectModel> {
        const response = await this.updateProjectRaw(requestParameters, initOverrides);
        return await response.value();
    }

}
