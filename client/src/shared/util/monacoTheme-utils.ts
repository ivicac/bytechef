import type {Monaco} from '@monaco-editor/react';

export const MONACO_DARK_THEME = 'bytechef-dark';
export const MONACO_LIGHT_THEME = 'light';

/**
 * Resolves a CSS colour expression to the `#rrggbb` Monaco needs, by letting the browser do the
 * parsing: an offscreen element takes the expression, and its computed `color` comes back as `rgb()`.
 *
 * This is what lets the theme be written in terms of design tokens rather than literals. The tokens
 * are bare HSL triplets consumed as `hsl(var(--token))`, and the element resolves them against
 * whatever is on the root right now -- so calling this while dark mode is active yields the dark
 * palette, including a vendor's own overrides in the embedded surfaces.
 */
const resolveCssColor = (cssColor: string): string | undefined => {
    const probeElement = document.createElement('span');

    probeElement.style.color = cssColor;
    probeElement.style.display = 'none';

    document.body.appendChild(probeElement);

    const computedColor = window.getComputedStyle(probeElement).color;

    probeElement.remove();

    const rgbMatch = /^rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(computedColor);

    if (!rgbMatch) {
        return undefined;
    }

    return `#${[1, 2, 3].map((group) => Number(rgbMatch[group]).toString(16).padStart(2, '0')).join('')}`;
};

const resolveToken = (token: string): string | undefined => {
    const rawValue = window.getComputedStyle(document.documentElement).getPropertyValue(token);

    // An undefined token makes `hsl(var(--token))` invalid, which computes to the inherited colour
    // rather than failing -- so a missing token would silently paint Monaco some unrelated colour.
    if (!rawValue.trim()) {
        return undefined;
    }

    return resolveCssColor(`hsl(var(${token}))`);
};

/**
 * Registers the dark theme under a stable name so `<Editor theme>` can always reference it.
 *
 * Called once at configuration time with no colours -- Monaco rejects a theme name it has never
 * seen, and the mode may still be light then -- and again from the editor whenever dark mode turns
 * on, which is the only moment the dark token values are actually readable off the root element.
 * `defineTheme` replaces a theme in place and repaints any editor currently using it, so redefining
 * is the update mechanism, not a leak.
 */
export const defineMonacoDarkTheme = (monaco: Monaco, withColors = true): void => {
    const colors: Record<string, string> = {};

    if (withColors) {
        const tokenColors: Record<string, string | undefined> = {
            'editor.background': resolveToken('--background'),
            'editor.foreground': resolveToken('--foreground'),
            'editor.lineHighlightBackground': resolveToken('--muted'),
            'editorGutter.background': resolveToken('--background'),
            'editorIndentGuide.background': resolveToken('--border'),
            'editorLineNumber.foreground': resolveToken('--muted-foreground'),
            'editorSuggestWidget.background': resolveToken('--popover'),
            'editorWidget.background': resolveToken('--popover'),
        };

        for (const [colorId, color] of Object.entries(tokenColors)) {
            if (color) {
                colors[colorId] = color;
            }
        }
    }

    monaco.editor.defineTheme(MONACO_DARK_THEME, {
        base: 'vs-dark',
        colors,
        inherit: true,
        rules: [],
    });
};
