import {createServerFn} from '@tanstack/react-start';

import {
    executeActionHandler,
    executeToolByNameHandler,
    executeToolHandler,
    executeWorkflowHandler,
    getWorkflowRunHandler,
    listConnectionsHandler,
    listToolsHandler,
    listWorkflowsHandler,
    proxyHandler,
} from './handlers';
import {bytechefMiddleware} from './middleware';
import {
    executeActionInput,
    executeToolByNameInput,
    executeToolInput,
    executeWorkflowInput,
    getWorkflowRunInput,
    proxyInput,
} from './validators';

const middleware = bytechefMiddleware();

export const executeAction = createServerFn({method: 'POST'})
    .middleware([middleware])
    .validator(executeActionInput)
    .handler(executeActionHandler);
export const executeTool = createServerFn({method: 'POST'})
    .middleware([middleware])
    .validator(executeToolInput)
    .handler(executeToolHandler);
export const executeToolByName = createServerFn({method: 'POST'})
    .middleware([middleware])
    .validator(executeToolByNameInput)
    .handler(executeToolByNameHandler);
/**
 * Starts a run and returns it after the server's `waitSeconds` hold: `RUNNING` or `APPROVAL_REQUIRED` for a long
 * run. Poll with `getWorkflowRun`. For a server-side wait, write your own server function on `bytechefMiddleware()`
 * and pass a signal.
 */
export const executeWorkflow = createServerFn({method: 'POST'})
    .middleware([middleware])
    .validator(executeWorkflowInput)
    .handler(executeWorkflowHandler);
export const getWorkflowRun = createServerFn({method: 'GET'})
    .middleware([middleware])
    .validator(getWorkflowRunInput)
    .handler(getWorkflowRunHandler);
export const listConnections = createServerFn({method: 'GET'}).middleware([middleware]).handler(listConnectionsHandler);
export const listTools = createServerFn({method: 'GET'}).middleware([middleware]).handler(listToolsHandler);
export const listWorkflows = createServerFn({method: 'GET'}).middleware([middleware]).handler(listWorkflowsHandler);
export const proxy = createServerFn({method: 'POST'})
    .middleware([middleware])
    .validator(proxyInput)
    .handler(proxyHandler);
