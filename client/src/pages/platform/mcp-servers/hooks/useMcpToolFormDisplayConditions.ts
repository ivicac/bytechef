import {McpTool} from '@/shared/middleware/graphql';
import useFormDisplayConditions from '@/shared/queries/platform/useFormDisplayConditions';

// A tool's properties are gated on each other (HTTP Client's body properties all hang off bodyContentType),
// and this form has no workflow node to read the evaluated conditions off — so they are evaluated against
// the form's own values instead.
const useMcpToolFormDisplayConditions = (
    componentName: string,
    componentVersion: number,
    mcpTool: McpTool,
    formValues: Record<string, unknown>
) =>
    useFormDisplayConditions({
        componentName,
        componentVersion,
        operationName: mcpTool.name,
        operationType: 'CLUSTER_ELEMENT',
        parameters: formValues,
    });

export default useMcpToolFormDisplayConditions;
