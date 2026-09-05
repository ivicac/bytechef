'use client';

import {useCallback, useEffect, useRef, useState} from 'react';

import useConnectDialog from '../connect-dialog';
import {MapObjectFieldsType} from '../connect-dialog/types';

const CONNECT_MESSAGE_TYPE = 'EMBED_OPEN_CONNECT_DIALOG';

/**
 * Sent INTO the iframe when the connect dialog closes. Connecting and disconnecting both happen in
 * this page's dialog, which the catalog inside the iframe cannot observe — without this a
 * disconnected integration keeps reading "Connected" until the host page is reloaded.
 */
const INTEGRATIONS_CHANGED_MESSAGE_TYPE = 'EMBED_INTEGRATIONS_CHANGED';

/**
 * Opens the connect dialog for ONE integration and reports when it closes.
 *
 * `useConnectDialog` takes its integration at hook-call time, so switching integrations means a
 * fresh hook instance -- hence a child component the parent mounts under a `key` of the integration
 * id rather than a call it can re-parameterise.
 */
const ConnectDialogHost = ({
    baseUrl,
    environment,
    integrationId,
    jwtToken,
    mapObjectFields,
    mode,
    onClose,
}: {
    baseUrl: string;
    environment: string;
    integrationId: string;
    jwtToken: string;
    mapObjectFields?: MapObjectFieldsType;
    mode?: 'dark' | 'light';
    onClose: () => void;
}) => {
    const {closeDialog, openDialog} = useConnectDialog({
        baseUrl,
        environment,
        integrationId,
        jwtToken,
        mapObjectFields,
        mode,
        onClose,
    });

    useEffect(() => {
        openDialog();

        return () => {
            closeDialog();
        };
        // Mount-only on purpose: the parent remounts this component per integration, so re-running
        // on an identity change of the hook's callbacks would reopen a dialog the user just closed.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return null;
};

/**
 * Props for the IntegrationMarketplace component.
 */
interface IntegrationMarketplaceProps {
    /**
     * The base URL of the ByteChef application.
     * @default 'https://app.bytechef.io'
     */
    baseUrl?: string;

    /**
     * Additional CSS classes applied to the wrapping element. The component renders no layout
     * classes of its own -- the host controls sizing and positioning entirely through this prop.
     */
    className?: string;

    /**
     * The environment whose integrations are listed.
     * @default 'PRODUCTION'
     */
    environment?: 'DEVELOPMENT' | 'STAGING' | 'PRODUCTION';

    /**
     * JWT token identifying the connected user.
     */
    jwtToken: string;

    /**
     * Field-mapping configuration for the connect dialog, identical to `useConnectDialog`'s. It is
     * made of FUNCTIONS, which is why the dialog is rendered here in your page rather than inside
     * the marketplace iframe -- a function cannot cross a postMessage boundary. The marketplace
     * asks this component to open the dialog; this component owns it.
     */
    mapObjectFields?: MapObjectFieldsType;

    /**
     * Called after the connect dialog closes, whether or not a connection was made.
     */
    onConnectDialogClose?: () => void;

    /**
     * Theme applied to the marketplace iframe's content. Shares the Automation Hub's theme shape,
     * so a host embedding both themes them once.
     */
    theme?: Record<string, unknown>;
}

/**
 * A component that embeds the ByteChef Integration Marketplace in an iframe: the catalog of
 * integrations a connected user can connect their own account to, each card showing whether they
 * already have.
 *
 * The catalog is served by ByteChef, so it follows the product without an SDK upgrade. The connect
 * dialog is NOT: it renders into your page, because its field-mapping configuration is functions.
 * The iframe posts the chosen integration's id out and this component opens the dialog for it.
 */
const IntegrationMarketplace = ({
    baseUrl = 'https://app.bytechef.io',
    className,
    environment = 'PRODUCTION',
    jwtToken,
    mapObjectFields,
    onConnectDialogClose,
    theme,
}: IntegrationMarketplaceProps) => {
    const [connectingIntegrationId, setConnectingIntegrationId] = useState<string | null>(null);

    const iframeRef = useRef<HTMLIFrameElement>(null);
    const propsRef = useRef({environment, jwtToken, theme});

    useEffect(() => {
        propsRef.current = {environment, jwtToken, theme};
    }, [environment, jwtToken, theme]);

    const handleConnectDialogClose = useCallback(() => {
        setConnectingIntegrationId(null);

        iframeRef.current?.contentWindow?.postMessage(
            {type: INTEGRATIONS_CHANGED_MESSAGE_TYPE},
            new URL(baseUrl).origin
        );

        onConnectDialogClose?.();
    }, [baseUrl, onConnectDialogClose]);

    useEffect(() => {
        const targetOrigin = new URL(baseUrl).origin;

        const handleMessage = (event: MessageEvent) => {
            if (event.origin !== targetOrigin) {
                return;
            }

            if (event.data?.type === 'EMBED_READY') {
                iframeRef.current?.contentWindow?.postMessage(
                    {params: propsRef.current, type: 'EMBED_INIT'},
                    targetOrigin
                );
            }

            if (event.data?.type === CONNECT_MESSAGE_TYPE && typeof event.data.integrationId === 'string') {
                setConnectingIntegrationId(event.data.integrationId);
            }
        };

        window.addEventListener('message', handleMessage);

        return () => {
            window.removeEventListener('message', handleMessage);
        };
    }, [baseUrl]);

    return (
        <div className={className}>
            <iframe
                height="100%"
                ref={iframeRef}
                src={`${baseUrl}/integration-marketplace.html#/embedded/marketplace`}
                style={{border: 'none'}}
                title="Integration Marketplace"
                width="100%"
            />

            {connectingIntegrationId && (
                <ConnectDialogHost
                    baseUrl={baseUrl}
                    environment={environment}
                    integrationId={connectingIntegrationId}
                    jwtToken={jwtToken}
                    key={connectingIntegrationId}
                    mapObjectFields={mapObjectFields}
                    // The dialog renders into THIS page, so it cannot inherit the mode the way the
                    // iframe does -- the same `theme.mode` has to be handed to it directly.
                    mode={theme?.mode as 'dark' | 'light' | undefined}
                    onClose={handleConnectDialogClose}
                />
            )}
        </div>
    );
};

export default IntegrationMarketplace;
