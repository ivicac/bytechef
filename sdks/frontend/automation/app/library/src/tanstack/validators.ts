import type {ProxyRequest} from '../index';

const PROXY_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'] as const;

type ProxyMethod = (typeof PROXY_METHODS)[number];

export interface ExecuteActionInput {
    actionName: string;
    bindingKey: string;
    componentVersion?: number;
    input: Record<string, unknown>;
}

export interface ExecuteToolInput {
    bindingKey: string;
    input: Record<string, unknown>;
    toolName: string;
}

export interface ExecuteToolByNameInput {
    input: Record<string, unknown>;
    name: string;
}

export interface ProxyInput {
    bindingKey: string;
    request: ProxyRequest;
}

export interface ExecuteWorkflowInput {
    input: Record<string, unknown>;
    waitSeconds?: number;
    workflowKey: string;
}

export interface GetWorkflowRunInput {
    runId: string;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
    if (typeof value !== 'object' || value === null) {
        return false;
    }

    const prototype = Object.getPrototypeOf(value);

    return prototype === Object.prototype || prototype === null;
}

function asRecord(value: unknown, name: string): Record<string, unknown> {
    if (!isPlainObject(value)) {
        throw new TypeError(`${name} must be an object`);
    }

    return value;
}

function requireString(record: Record<string, unknown>, name: string): string {
    const value = record[name];

    if (typeof value !== 'string' || value === '') {
        throw new TypeError(`${name} must be a non-empty string`);
    }

    return value;
}

function optionalString(record: Record<string, unknown>, name: string): string | undefined {
    const value = record[name];

    if (value === undefined || value === null) {
        return undefined;
    }

    if (typeof value !== 'string') {
        throw new TypeError(`${name} must be a string`);
    }

    return value;
}

function optionalNonNegativeInteger(record: Record<string, unknown>, name: string): number | undefined {
    const value = record[name];

    if (value === undefined || value === null) {
        return undefined;
    }

    if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) {
        throw new TypeError(`${name} must be a non-negative integer`);
    }

    return value;
}

function optionalStringListMap(record: Record<string, unknown>, name: string): Record<string, string[]> | undefined {
    const value = record[name];

    if (value === undefined || value === null) {
        return undefined;
    }

    const entries = Object.entries(asRecord(value, name)).map(([key, entry]): [string, string[]] => {
        if (!Array.isArray(entry) || entry.some((item) => typeof item !== 'string')) {
            throw new TypeError(`${name}.${key} must be an array of strings`);
        }

        return [key, entry as string[]];
    });

    return Object.fromEntries(entries);
}

export function executeActionInput(input: unknown): ExecuteActionInput {
    const record = asRecord(input, 'input');
    const componentVersion = optionalNonNegativeInteger(record, 'componentVersion');

    return {
        actionName: requireString(record, 'actionName'),
        bindingKey: requireString(record, 'bindingKey'),
        ...(componentVersion === undefined ? {} : {componentVersion}),
        input: asRecord(record['input'], 'input.input'),
    };
}

export function executeToolInput(input: unknown): ExecuteToolInput {
    const record = asRecord(input, 'input');

    return {
        bindingKey: requireString(record, 'bindingKey'),
        input: asRecord(record['input'], 'input.input'),
        toolName: requireString(record, 'toolName'),
    };
}

export function executeToolByNameInput(input: unknown): ExecuteToolByNameInput {
    const record = asRecord(input, 'input');

    return {
        input: asRecord(record['input'], 'input.input'),
        name: requireString(record, 'name'),
    };
}

export function proxyInput(input: unknown): ProxyInput {
    const record = asRecord(input, 'input');
    const request = asRecord(record['request'], 'request');
    const method = requireString(request, 'method');

    if (!PROXY_METHODS.includes(method as ProxyMethod)) {
        throw new TypeError(`request.method must be one of ${PROXY_METHODS.join(', ')}`);
    }

    const path = requireString(request, 'path');

    if (!path.startsWith('/')) {
        throw new TypeError('request.path must start with /');
    }

    const body = request['body'];

    if (
        body !== undefined &&
        body !== null &&
        typeof body !== 'string' &&
        !Array.isArray(body) &&
        !isPlainObject(body)
    ) {
        throw new TypeError('request.body must be a string, an object or an array');
    }

    const contentType = optionalString(request, 'contentType');
    const headers = optionalStringListMap(request, 'headers');
    const query = optionalStringListMap(request, 'query');

    return {
        bindingKey: requireString(record, 'bindingKey'),
        request: {
            ...(body === undefined || body === null ? {} : {body: body as ProxyRequest['body']}),
            ...(contentType === undefined ? {} : {contentType}),
            ...(headers === undefined ? {} : {headers}),
            method: method as ProxyMethod,
            path,
            ...(query === undefined ? {} : {query}),
        },
    };
}

export function executeWorkflowInput(input: unknown): ExecuteWorkflowInput {
    const record = asRecord(input, 'input');
    const waitSeconds = optionalNonNegativeInteger(record, 'waitSeconds');

    return {
        input: asRecord(record['input'], 'input.input'),
        ...(waitSeconds === undefined ? {} : {waitSeconds}),
        workflowKey: requireString(record, 'workflowKey'),
    };
}

export function getWorkflowRunInput(input: unknown): GetWorkflowRunInput {
    return {runId: requireString(asRecord(input, 'input'), 'runId')};
}
