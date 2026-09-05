import {ReactNode, createContext, useContext, useEffect, useState} from 'react';

type ThemeType = 'dark' | 'light' | 'system';

interface ThemeProviderProps {
    children: ReactNode;
    defaultTheme?: ThemeType;
    /**
     * Whether the choice is read from and written to localStorage. Embedded surfaces pass `false`:
     * they are served from the ByteChef origin, so persisting would have a vendor's iframe write
     * ByteChef's OWN `bytechef.ui-theme` key — the mode a vendor picked for their embed would
     * follow the ByteChef user into the ByteChef app, and two embedded surfaces on one host page
     * would fight over the single key. Reading is disabled for the same reason in reverse: a
     * vendor's embed must not inherit whatever mode the ByteChef user last chose. Their mode
     * arrives on the `theme` prop at every mount instead.
     */
    persist?: boolean;
    storageKey?: string;
}

interface ThemeProviderStateI {
    theme: ThemeType;
    setTheme: (theme: ThemeType) => void;
}

const ThemeProviderContext = createContext<ThemeProviderStateI | undefined>(undefined);

export function ThemeProvider({
    children,
    defaultTheme = 'light',
    persist = true,
    storageKey = 'bytechef.ui-theme',
    ...props
}: ThemeProviderProps) {
    const [theme, setTheme] = useState<ThemeType>(() =>
        persist ? (localStorage.getItem(storageKey) as ThemeType) || defaultTheme : defaultTheme
    );

    useEffect(() => {
        const root = window.document.documentElement;
        const darkModeQuery = window.matchMedia('(prefers-color-scheme: dark)');

        const applyTheme = () => {
            root.classList.remove('light', 'dark');

            if (theme === 'system') {
                root.classList.add(darkModeQuery.matches ? 'dark' : 'light');
            } else {
                root.classList.add(theme);
            }
        };

        applyTheme();

        if (theme !== 'system') {
            return;
        }

        darkModeQuery.addEventListener('change', applyTheme);

        return () => darkModeQuery.removeEventListener('change', applyTheme);
    }, [theme]);

    const value = {
        setTheme: (theme: ThemeType) => {
            if (persist) {
                localStorage.setItem(storageKey, theme);
            }

            setTheme(theme);
        },
        theme,
    };

    return (
        <ThemeProviderContext.Provider {...props} value={value}>
            {children}
        </ThemeProviderContext.Provider>
    );
}

export const useTheme = () => {
    const context = useContext(ThemeProviderContext);

    if (context === undefined) throw new Error('useTheme must be used within a ThemeProvider');

    return context;
};
