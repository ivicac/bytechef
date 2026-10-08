import {getRouteApi} from '@tanstack/react-router';
import {useState} from 'react';

import {Badge} from '@/components/ui/badge';
import {Button} from '@/components/ui/button';
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card';
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table';
import type {AppOverview, BindingOverview} from '@/server/overview';
import type {AppWorkflow} from '@bytechef/app';

function prefill(schema: Record<string, unknown> | null): string {
    const properties =
        schema && typeof schema.properties === 'object' && schema.properties !== null
            ? (schema.properties as Record<string, unknown>)
            : {};

    return JSON.stringify(Object.fromEntries(Object.keys(properties).map((key) => [key, ''])), null, 2);
}

function TryIt({action, bindingKey}: {action: BindingOverview['actions'][number]; bindingKey: string}) {
    const [text, setText] = useState(() => prefill(action.inputSchema));
    const [result, setResult] = useState<string | null>(null);
    const [running, setRunning] = useState(false);

    async function run() {
        if (!import.meta.env.DEV) {
            return;
        }

        setRunning(true);

        try {
            const {tryAction} = await import('@/server/dev');
            const output = await tryAction({
                data: {actionName: action.name, bindingKey, input: JSON.parse(text) as Record<string, unknown>},
            });

            setResult(JSON.stringify(output, null, 2));
        } catch (error) {
            setResult((error as Error).message);
        } finally {
            setRunning(false);
        }
    }

    return (
        <div className="space-y-2">
            <textarea
                className="w-full rounded-md border p-2 font-mono text-xs"
                onChange={(event) => setText(event.target.value)}
                rows={4}
                value={text}
            />
            <Button disabled={running} onClick={run} size="sm" type="button">
                {running ? 'Running…' : 'Run'}
            </Button>
            {result !== null && <pre className="overflow-auto rounded-md bg-muted p-2 text-xs">{result}</pre>}
        </div>
    );
}

function BindingCard({binding}: {binding: BindingOverview}) {
    return (
        <Card id={`binding-${binding.bindingKey}`}>
            <CardHeader>
                <CardTitle>{binding.bindingKey}</CardTitle>
                <CardDescription>
                    {binding.componentName}{' '}
                    {binding.componentVersion === null ? (
                        <Badge variant="destructive">component unavailable</Badge>
                    ) : (
                        <Badge variant="secondary">v{binding.componentVersion}</Badge>
                    )}
                </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
                <Table>
                    <TableHeader>
                        <TableRow>
                            <TableHead>Action</TableHead>
                            <TableHead>Description</TableHead>
                            <TableHead>Try it</TableHead>
                        </TableRow>
                    </TableHeader>
                    <TableBody>
                        {binding.actions.map((action) => (
                            <TableRow key={action.name}>
                                <TableCell className="font-mono">{action.name}</TableCell>
                                <TableCell>{action.description ?? ''}</TableCell>
                                <TableCell>
                                    {binding.componentVersion === null ? (
                                        <span className="text-muted-foreground">unavailable</span>
                                    ) : (
                                        <TryIt action={action} bindingKey={binding.bindingKey} />
                                    )}
                                </TableCell>
                            </TableRow>
                        ))}
                    </TableBody>
                </Table>
                <div className="flex flex-wrap gap-2">
                    {binding.tools.map((tool) => (
                        <Badge key={tool.name} variant={tool.parameters === null ? 'destructive' : 'outline'}>
                            {tool.parameters === null ? `${tool.name} (unavailable)` : tool.name}
                        </Badge>
                    ))}
                </div>
                <p className="text-sm text-muted-foreground">
                    {binding.proxy.enabled ? `Proxy: ${binding.proxy.methods.join(', ')}` : 'Proxy: off'}
                </p>
            </CardContent>
        </Card>
    );
}

function WorkflowsCard({workflows}: {workflows: AppWorkflow[]}) {
    return (
        <Card id="workflows">
            <CardHeader>
                <CardTitle>Workflows</CardTitle>
            </CardHeader>
            <CardContent>
                {workflows.length === 0 ? (
                    <p className="text-sm text-muted-foreground">No workflows are bound to this app.</p>
                ) : (
                    <ul className="space-y-1">
                        {workflows.map((workflow) => (
                            <li className="flex items-center gap-2" key={workflow.workflowKey}>
                                <span className="font-mono">{workflow.workflowKey}</span>
                                <span>{workflow.name}</span>
                                {!workflow.available && <Badge variant="destructive">unavailable</Badge>}
                            </li>
                        ))}
                    </ul>
                )}
            </CardContent>
        </Card>
    );
}

const routeApi = getRouteApi('/');

export function DevHome() {
    const {overview} = routeApi.useLoaderData();

    return overview === null ? null : <Overview overview={overview} />;
}

function Overview({overview}: {overview: AppOverview}) {
    return (
        <main className="mx-auto max-w-4xl space-y-6 p-8" id="overview">
            <h1 className="text-2xl font-semibold">What this app may call</h1>
            {!overview.actionSchemas && (
                <p className="text-sm text-muted-foreground">
                    This ByteChef server does not provide action input schemas.
                </p>
            )}
            {overview.bindings.map((binding) => (
                <BindingCard binding={binding} key={binding.bindingKey} />
            ))}
            <WorkflowsCard workflows={overview.workflows} />
        </main>
    );
}
