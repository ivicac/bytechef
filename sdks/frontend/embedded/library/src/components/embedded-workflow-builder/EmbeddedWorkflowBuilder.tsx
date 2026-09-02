'use client';

import {useEffect, useRef} from 'react';

/**
 * Props for the EmbeddedWorkflowBuilder component.
 * This interface defines all the configuration options needed to initialize and render
 * the embedded workflow builder iframe.
 */
interface EmbeddedWorkflowBuilderProps {
    /**
     * The base URL of the ByteChef application.
     * This URL is used to construct the iframe src attribute.
     * @default 'https://app.bytechef.io'
     */
    baseUrl?: string;

    /**
     * Whether to allow the connection dialog to be shown in the workflow builder.
     * When true, users can create and manage connections directly in the workflow builder.
     * When false, users can only use existing connections. Those existing connections can be
     * either connections a tenant admin marked as shared inside ByteChef's '/embedded/connections'
     * page or integration connections created via `ConnectDialog`.
     */
    connectionDialogAllowed: boolean;

    /**
     * The environment to use for the workflow builder.
     * This affects which environment's connections and configurations are used.
     * @default 'PRODUCTION'
     */
    environment?: 'DEVELOPMENT' | 'STAGING' | 'PRODUCTION';

    /**
     * Array of component identifiers to include in the workflow builder.
     * This limits which integration components are available to the user.
     * Example: ['slack', 'googleMail', 'productboard']
     */
    includeComponents?: string[];

    /**
     * JWT token for authentication with the ByteChef API.
     * This token is passed to the iframe via postMessage for API authorization.
     */
    jwtToken: string;

    /**
     * The uuid for the workflow being edited.
     * This is used to load the correct workflow in the builder.
     */
    workflowUuid: string;
}

/**
 * A component that embeds the ByteChef Workflow Builder in an iframe.
 *
 * This component creates an iframe that loads the ByteChef Workflow Builder UI and
 * initializes it with the provided configuration. When the iframe signals it is ready
 * via a postMessage, the parent sends the initialization parameters back.
 *
 * @param props - The configuration options for the embedded workflow builder
 * @returns A React component that renders the embedded workflow builder
 */
const EmbeddedWorkflowBuilder = ({
    baseUrl = 'https://app.bytechef.io',
    connectionDialogAllowed,
    environment = 'PRODUCTION',
    includeComponents,
    jwtToken,
    workflowUuid,
}: EmbeddedWorkflowBuilderProps) => {
    const iframeRef = useRef<HTMLIFrameElement>(null);
    const propsRef = useRef({connectionDialogAllowed, environment, includeComponents, jwtToken});

    // Kept up to date via an effect (rather than assigned during render) so that a late prop
    // change is still visible to the next EMBED_READY handshake, without mutating the ref while
    // rendering -- postMessage delivery is always async relative to React's render/effect cycle,
    // so this remains observably identical to an in-render assignment.
    useEffect(() => {
        propsRef.current = {connectionDialogAllowed, environment, includeComponents, jwtToken};
    }, [connectionDialogAllowed, environment, includeComponents, jwtToken]);

    useEffect(() => {
        const targetOrigin = new URL(baseUrl).origin;

        const sendInitMessage = () => {
            if (iframeRef.current && iframeRef.current.contentWindow) {
                iframeRef.current.contentWindow.postMessage(
                    {
                        type: 'EMBED_INIT',
                        params: propsRef.current,
                    },
                    targetOrigin
                );
            }
        };

        const handleMessage = (event: MessageEvent) => {
            if (event.origin === targetOrigin && event.data.type === 'EMBED_READY') {
                sendInitMessage();
            }
        };

        window.addEventListener('message', handleMessage);

        return () => {
            window.removeEventListener('message', handleMessage);
        };
    }, [baseUrl]);

    return (
        <div className="absolute inset-0 lg:pl-72">
            <iframe
                ref={iframeRef}
                src={`${baseUrl}/embedded/builder/${workflowUuid}`}
                width="100%"
                height="100%"
                style={{border: 'none'}}
                title="Workflow Builder"
            />
        </div>
    );
};

export default EmbeddedWorkflowBuilder;
