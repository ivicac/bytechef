import {AutomationHubThemeI} from '@/ee/pages/embedded/automation-hub/stores/useAutomationHubStore';

const HEX_COLOR = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i;

function contrastForeground(hex: string): string {
    const full = hex.length === 4 ? `#${[...hex.slice(1)].map((char) => char + char).join('')}` : hex;
    const [red, green, blue] = [1, 3, 5].map((offset) => parseInt(full.slice(offset, offset + 2), 16) / 255);
    const luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;

    return luminance > 0.5 ? '#111111' : '#ffffff';
}

const CSS_LENGTH = /^\d+(\.\d+)?(px|rem|em|%)$/;

function isSupportedColor(value: string): boolean {
    if (HEX_COLOR.test(value)) {
        return true;
    }

    // jsdom has no CSS.supports; non-hex colors are only accepted where the browser can vouch for them
    return typeof CSS !== 'undefined' && typeof CSS.supports === 'function' && CSS.supports('color', value);
}

/**
 * The hub's own colour roles, in the order a vendor thinks about them: the page, the cards on it,
 * the state a card can be in, and the two actions a card offers. Each maps to one `--hub-*`
 * variable whose default is declared in `styles/index.css`, so an absent value simply leaves the
 * shipped look in place.
 *
 * All of these are fills except the active state, which is the enabled card's border -- hence
 * `activeBorderColor` rather than a "surface" name, so a vendor supplies a colour strong enough to
 * read as an edge instead of a wash that would vanish against the card.
 */
const SURFACE_VARIABLES: Record<string, string> = {
    activeBorderColor: '--hub-active-border',
    cardColor: '--hub-card',
    disableColor: '--hub-disable',
    enableColor: '--hub-enable',
    onAccentColor: '--hub-on-accent',
    segmentColor: '--hub-segment-selected',
    surfaceColor: '--hub-surface',
};

/**
 * The two action colours each carry a hover shade. A vendor supplies one colour, not a ramp, so
 * the hover falls back to the colour itself rather than inventing a darker one — a computed shade
 * would be wrong for the many colours that are not simple hex.
 */
const HOVER_VARIABLES: Record<string, string> = {
    disableColor: '--hub-disable-hover',
    enableColor: '--hub-enable-hover',
};

/**
 * The design tokens the embedded BUILDER paints with are bare HSL triplets, consumed as
 * `hsl(var(--token))`, while a vendor supplies a colour. Writing the colour in directly would
 * produce `hsl(#0b1220)` -- invalid, and silently dropped -- so it is converted here, and a colour
 * that cannot be converted leaves those tokens at their shipped values rather than breaking them.
 */
function toRgb(color: string): [number, number, number] | undefined {
    if (HEX_COLOR.test(color)) {
        const full = color.length === 4 ? `#${[...color.slice(1)].map((char) => char + char).join('')}` : color;

        return [1, 3, 5].map((offset) => parseInt(full.slice(offset, offset + 2), 16)) as [number, number, number];
    }

    if (typeof document === 'undefined') {
        return undefined;
    }

    // Everything else is resolved by the browser itself. jsdom returns the input unchanged, so a
    // named colour simply fails to parse there and the builder tokens are left alone.
    const probe = document.createElement('span');

    probe.style.color = color;

    const match = /^rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(probe.style.color);

    return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : undefined;
}

function toHslTriplet(color: string): string | undefined {
    const rgb = toRgb(color);

    if (!rgb) {
        return undefined;
    }

    const [red, green, blue] = rgb.map((channel) => channel / 255);
    const max = Math.max(red, green, blue);
    const min = Math.min(red, green, blue);
    const lightness = (max + min) / 2;

    let hue = 0;
    let saturation = 0;

    if (max !== min) {
        const delta = max - min;

        saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min);

        if (max === red) {
            hue = (green - blue) / delta + (green < blue ? 6 : 0);
        } else if (max === green) {
            hue = (blue - red) / delta + 2;
        } else {
            hue = (red - green) / delta + 4;
        }

        hue *= 60;
    }

    return `${Math.round(hue)} ${Math.round(saturation * 100)}% ${Math.round(lightness * 100)}%`;
}

/**
 * The same two surface roles, carried into the tokens the builder paints with, so `theme` reaches
 * the whole embedded surface rather than stopping at the hub's own chrome. The builder's canvas and
 * the panels floating over it are the "card"; the ground they sit on is the surface.
 *
 * Only the neutrals are carried: what a vendor means by "my card colour" holds for a panel, but
 * their enable-green is a hub affordance with no counterpart in the builder.
 */
const BUILDER_VARIABLES: Record<string, string[]> = {
    cardColor: ['--background', '--card', '--popover', '--surface-neutral-primary'],
    surfaceColor: ['--muted', '--surface-main'],
};

export function applyHubTheme(
    theme: AutomationHubThemeI,
    root: HTMLElement = document.documentElement
): 'dark' | 'light' {
    if (theme.primaryColor && isSupportedColor(theme.primaryColor)) {
        root.style.setProperty('--primary', theme.primaryColor);
        root.style.setProperty('--ring', theme.primaryColor);

        if (HEX_COLOR.test(theme.primaryColor)) {
            root.style.setProperty('--primary-foreground', contrastForeground(theme.primaryColor));
        }

        // The builder's primary buttons paint with the brand surface, not with `--primary`.
        const brandTriplet = toHslTriplet(theme.primaryColor);

        if (brandTriplet) {
            for (const variable of [
                '--surface-brand-primary',
                '--surface-brand-primary-hover',
                '--surface-brand-primary-active',
            ]) {
                root.style.setProperty(variable, brandTriplet);
            }
        }
    }

    if (theme.fontFamily) {
        root.style.setProperty('--font-sans', theme.fontFamily);
    }

    if (theme.borderRadius && CSS_LENGTH.test(theme.borderRadius)) {
        root.style.setProperty('--radius', theme.borderRadius);
    }

    for (const [themeKey, variable] of Object.entries(SURFACE_VARIABLES)) {
        const value = (theme as Record<string, unknown>)[themeKey];

        if (typeof value !== 'string' || !isSupportedColor(value)) {
            continue;
        }

        root.style.setProperty(variable, value);

        for (const builderVariable of BUILDER_VARIABLES[themeKey] ?? []) {
            const triplet = toHslTriplet(value);

            if (triplet) {
                root.style.setProperty(builderVariable, triplet);
            }
        }

        const hoverVariable = HOVER_VARIABLES[themeKey];

        if (hoverVariable) {
            root.style.setProperty(hoverVariable, value);
        }
    }

    // The escape hatch, deliberately last so it can correct anything above. It writes whatever the
    // vendor names, which is the only way to promise "override everything" — and equally the
    // reason it is documented as unstable: it binds the vendor to OUR variable names, which the
    // named roles above exist to insulate them from. Only `--`-prefixed names are accepted, so a
    // value can never escape into an arbitrary CSS property.
    for (const [variable, value] of Object.entries(theme.cssVariables ?? {})) {
        if (variable.startsWith('--') && typeof value === 'string') {
            root.style.setProperty(variable, value);
        }
    }

    return theme.mode === 'dark' ? 'dark' : 'light';
}
