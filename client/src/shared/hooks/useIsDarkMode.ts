import {useEffect, useState} from 'react';

const isDarkModeActive = () => document.documentElement.classList.contains('dark');

/**
 * Whether the app is currently painting in dark mode, read off the root element's class rather than
 * from `useTheme`.
 *
 * Two reasons for the DOM over the context: `useTheme` throws outside a `ThemeProvider`, and the
 * provider's `system` setting is not a mode at all -- it resolves to `light` or `dark` only once it
 * writes the class. Reading the class gives the resolved answer everywhere, including in the
 * embedded surfaces, where a vendor's `theme` prop drives the same class.
 *
 * For anything painted with Tailwind or design tokens this hook is unnecessary -- CSS already
 * follows the class. It exists for the few widgets that paint themselves from JavaScript and so
 * need the mode as a value: Monaco being the one that matters.
 */
export default function useIsDarkMode(): boolean {
    const [isDarkMode, setIsDarkMode] = useState(isDarkModeActive);

    useEffect(() => {
        const mutationObserver = new MutationObserver(() => setIsDarkMode(isDarkModeActive()));

        mutationObserver.observe(document.documentElement, {attributeFilter: ['class'], attributes: true});

        // The class may have changed between the initial state and this subscription.
        setIsDarkMode(isDarkModeActive());

        return () => mutationObserver.disconnect();
    }, []);

    return isDarkMode;
}
