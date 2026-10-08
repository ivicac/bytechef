import type {AppAction, AppBinding, AppTool, AppWorkflow} from '@bytechef/app';

export interface ToolOverview {
    description: string | null;
    name: string;
    parameters: Record<string, unknown> | null;
    toolName: string;
}

export interface BindingOverview {
    actions: Array<{description: string | null; inputSchema: Record<string, unknown> | null; name: string}>;
    bindingKey: string;
    componentName: string;
    componentVersion: number | null;
    proxy: {enabled: boolean; methods: string[]};
    tools: ToolOverview[];
}

export interface AppOverview {
    actionSchemas: boolean;
    bindings: BindingOverview[];
    workflows: AppWorkflow[];
}

/**
 * The name ByteChef gives a bound tool for LLM function calling: the binding key with every run of non-alphanumeric
 * characters collapsed to `_`, then `__`, then the tool name in upper snake case (`crm` + `searchContacts` →
 * `CRM__SEARCH_CONTACTS`).
 */
export function flatToolName(bindingKey: string, toolName: string): string {
    const key = bindingKey.replace(/[^A-Za-z0-9]+/g, '_').toUpperCase();
    let tool = '';

    for (let index = 0; index < toolName.length; index++) {
        const character = toolName.charAt(index);
        const upperCase = character.toUpperCase();

        if (index > 0 && /\p{Uppercase}/u.test(character)) {
            tool += '_';
        }

        tool += upperCase.length === 1 ? upperCase : character;
    }

    return `${key}__${tool}`;
}

export function buildOverview(
    bindings: readonly AppBinding[],
    actions: readonly AppAction[],
    actionSchemas: boolean,
    tools: readonly AppTool[],
    workflows: readonly AppWorkflow[]
): AppOverview {
    return {
        actionSchemas,
        bindings: bindings.map((binding) => ({
            actions: binding.actionNames.map((name) => {
                const action = actions.find(
                    (candidate) => candidate.bindingKey === binding.bindingKey && candidate.actionName === name
                );

                return {description: action?.description ?? null, inputSchema: action?.inputSchema ?? null, name};
            }),
            bindingKey: binding.bindingKey,
            componentName: binding.componentName,
            componentVersion: binding.componentVersion ?? null,
            proxy: {enabled: binding.proxyEnabled, methods: [...binding.proxyAllowedMethods]},
            tools: binding.toolNames.map((toolName) => {
                const tool = tools.find(
                    (candidate) => candidate.bindingKey === binding.bindingKey && candidate.toolName === toolName
                );

                return tool
                    ? {
                          description: tool.description ?? null,
                          name: tool.name,
                          parameters: tool.parameters,
                          toolName,
                      }
                    : {description: null, name: flatToolName(binding.bindingKey, toolName), parameters: null, toolName};
            }),
        })),
        workflows: [...workflows],
    };
}
