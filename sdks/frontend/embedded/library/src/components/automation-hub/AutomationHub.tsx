'use client';

import {useEffect, useRef} from 'react';

/**
 * Which tabs of the Automation Hub are shown to the end user. All keys default to `true`; set a
 * key to `false` to hide that tab. If only one tab remains enabled the tab strip itself is hidden.
 */
export interface AutomationHubTabsConfig {
    /**
     * The Automations tab -- the published template catalog, each card carrying the user's activation state.
     * @default true
     */
    automations?: boolean;

    /**
     * The Connections tab -- the connections the user owns, with reconnect/delete, plus any
     * connection a tenant admin marked as shared inside ByteChef's '/embedded/connections' page.
     * Shared connections are listed but read-only: the user may select them when activating an
     * automation, but may not reconnect or delete them.
     * @default true
     */
    connections?: boolean;

    /**
     * The "New automation" button on the Automations tab.
     * @default true
     */
    newWorkflow?: boolean;
}

/**
 * Theme applied to the Automation Hub iframe's content. `fontFamily` must be a font the iframe
 * can load on its own (system/web-safe, or otherwise loadable by the ByteChef application) --
 * host-page `@font-face` declarations do not cross the iframe boundary.
 */
export interface AutomationHubTheme {
    /**
     * The border of a card whose automation is running. Any CSS color.
     *
     * Marks the state as an edge rather than a fill, so it needs a border-strength colour: a pale
     * tint reads as no border at all against the card it outlines.
     */
    activeBorderColor?: string;

    /**
     * CSS length applied to the hub's border radius token, e.g. '0.5rem'.
     */
    borderRadius?: string;

    /**
     * The surface of a card sitting on the hub's page. Any CSS color.
     *
     * Also carried into the workflow builder, where the canvas and the panels floating over it are
     * the "card": a hex colour is converted to the builder's token format, and any other colour is
     * applied where it can be and left alone where it cannot.
     */
    cardColor?: string;

    /**
     * Raw CSS custom properties written onto the hub's root element, applied AFTER every option
     * above so they can correct one. Keys must start with '--'; anything else is ignored.
     *
     * This is the escape hatch for anything the named options do not reach. It binds you to
     * ByteChef's internal variable names, which the named options exist to insulate you from, so
     * treat it as unstable across versions and prefer a named option wherever one exists.
     */
    cssVariables?: Record<string, string>;

    /**
     * The "Disable" button on a running automation's card. Any CSS color; its hover shade follows
     * it.
     */
    disableColor?: string;

    /**
     * The "Enable" button on a stopped automation's card. Any CSS color; its hover shade follows
     * it.
     */
    enableColor?: string;

    /**
     * CSS font-family value applied to the hub's font token. Must be loadable inside the iframe --
     * host-page `@font-face` does not cross the iframe boundary.
     */
    fontFamily?: string;

    /**
     * Light or dark mode.
     * @default 'light'
     */
    mode?: 'dark' | 'light';

    /**
     * The label colour on the Enable and Disable buttons. Any CSS color.
     */
    onAccentColor?: string;

    /**
     * Any CSS color, applied to the hub's primary color token. The contrasting foreground color is
     * computed automatically.
     */
    primaryColor?: string;

    /**
     * The selected segment of the hub's filter and layout switchers. Any CSS color.
     */
    segmentColor?: string;

    /**
     * The hub's page background behind the cards, and the ground the builder's canvas sits on. Any
     * CSS color; see `cardColor` for how it reaches the builder.
     */
    surfaceColor?: string;
}

/**
 * Props for the AutomationHub component.
 * This interface defines all the configuration options needed to initialize and render
 * the embedded Automation Hub iframe.
 */
interface AutomationHubProps {
    /**
     * The base URL of the ByteChef application.
     * This URL is used to construct the iframe src attribute.
     * @default 'https://app.bytechef.io'
     */
    baseUrl?: string;

    /**
     * Additional CSS classes applied to the wrapping element. The component renders no layout
     * classes of its own -- the host controls sizing and positioning entirely through this prop.
     */
    className?: string;

    /**
     * The catalog layout an end user starts on. Their own choice, made with the layout switcher,
     * takes over from there and is remembered in their browser — unless `layoutSwitcherAllowed` is
     * false, in which case this layout is the only one they ever see.
     * @default 'grid'
     */
    defaultLayout?: 'grid' | 'list';

    /**
     * Whether the activation wizard offers "Edit workflow", which opens the automation in the
     * embedded workflow builder. Set false to keep end users to the wizard's own steps.
     * @default true
     */
    editWorkflowAllowed?: boolean;

    /**
     * Whether to allow the connection dialog to be shown in the workflow builder view of the hub.
     * When true, users can create and manage connections directly. When false, users can only use
     * existing connections -- either connections a tenant admin marked as shared inside
     * ByteChef's '/embedded/connections' page or integration connections created via
     * `ConnectDialog`.
     * @default true
     */
    connectionDialogAllowed?: boolean;

    /**
     * The environment to use for the Automation Hub.
     * This affects which environment's connections and configurations are used.
     * @default 'PRODUCTION'
     */
    environment?: 'DEVELOPMENT' | 'STAGING' | 'PRODUCTION';

    /**
     * Array of component identifiers to include in the hub's workflow builder view.
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
     * Whether the catalog offers the grid/list layout switcher. With it withdrawn, end users stay
     * on `defaultLayout`.
     * @default true
     */
    layoutSwitcherAllowed?: boolean;

    /**
     * @deprecated No longer has any effect. The server derives shared connections from the
     * `shared` flag a tenant admin sets on the connection itself at '/embedded/connections';
     * ids sent here are ignored. Will be removed in a future release.
     * @default []
     */
    sharedConnectionIds?: number[];

    /**
     * Which tabs of the Automation Hub are shown to the end user.
     * @default {automations: true, connections: true, newWorkflow: true}
     */
    tabs?: AutomationHubTabsConfig;

    /**
     * Theme applied to the Automation Hub iframe's content -- the catalog, the connections view AND
     * the workflow builder they open.
     *
     * Scoped to this iframe: it never touches the ByteChef application's own appearance, and two
     * embedded surfaces on one page can carry different themes.
     */
    theme?: AutomationHubTheme;
}

/**
 * A component that embeds the ByteChef Automation Hub in an iframe.
 *
 * The Automation Hub gives end users an Automations view -- a self-serve catalog of published
 * templates, each showing whether the user has activated it -- plus a Connections view and the
 * workflow builder for automations they own, all behind one iframe. When the iframe signals it is ready via
 * a postMessage, the parent sends the initialization parameters back.
 *
 * @param props - The configuration options for the embedded Automation Hub
 * @returns A React component that renders the embedded Automation Hub
 */
const AutomationHub = ({
    baseUrl = 'https://app.bytechef.io',
    className,
    connectionDialogAllowed = true,
    defaultLayout = 'grid',
    editWorkflowAllowed = true,
    environment = 'PRODUCTION',
    includeComponents,
    jwtToken,
    layoutSwitcherAllowed = true,
    sharedConnectionIds = [],
    tabs,
    theme,
}: AutomationHubProps) => {
    const iframeRef = useRef<HTMLIFrameElement>(null);
    const propsRef = useRef({
        connectionDialogAllowed,
        defaultLayout,
        editWorkflowAllowed,
        environment,
        includeComponents,
        jwtToken,
        layoutSwitcherAllowed,
        sharedConnectionIds,
        tabs,
        theme,
    });

    // Kept up to date via an effect (rather than assigned during render) so that a late prop
    // change is still visible to the next EMBED_READY handshake, without mutating the ref while
    // rendering -- postMessage delivery is always async relative to React's render/effect cycle,
    // so this remains observably identical to an in-render assignment.
    useEffect(() => {
        propsRef.current = {
            connectionDialogAllowed,
            defaultLayout,
            editWorkflowAllowed,
            environment,
            includeComponents,
            jwtToken,
            layoutSwitcherAllowed,
            sharedConnectionIds,
            tabs,
            theme,
        };
    }, [
        connectionDialogAllowed,
        defaultLayout,
        editWorkflowAllowed,
        environment,
        includeComponents,
        jwtToken,
        layoutSwitcherAllowed,
        sharedConnectionIds,
        tabs,
        theme,
    ]);

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
        <div className={className}>
            <iframe
                ref={iframeRef}
                src={`${baseUrl}/automation-hub.html#/embedded/hub`}
                width="100%"
                height="100%"
                style={{border: 'none'}}
                title="Automation Hub"
            />
        </div>
    );
};

export default AutomationHub;
