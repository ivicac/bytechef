import {applyHubTheme} from '@/ee/pages/embedded/automation-hub/theme/applyHubTheme';
import {beforeEach, describe, expect, it} from 'vitest';

describe('applyHubTheme', () => {
    let root: HTMLElement;

    beforeEach(() => {
        root = document.createElement('div');
    });

    it('sets primary, ring, primary-foreground, radius and font CSS variables and returns light', () => {
        const mode = applyHubTheme({borderRadius: '4px', fontFamily: 'Inter', primaryColor: '#ff0000'}, root);

        expect(root.style.getPropertyValue('--primary')).toBe('#ff0000');
        expect(root.style.getPropertyValue('--ring')).toBe('#ff0000');
        expect(root.style.getPropertyValue('--primary-foreground')).toBe('#ffffff');
        expect(root.style.getPropertyValue('--radius')).toBe('4px');
        expect(root.style.getPropertyValue('--font-sans')).toBe('Inter');
        expect(mode).toBe('light');
    });

    it('returns dark when mode is dark', () => {
        const mode = applyHubTheme({mode: 'dark'}, root);

        expect(mode).toBe('dark');
    });

    it('leaves --primary unset when primaryColor is not a supported color', () => {
        applyHubTheme({primaryColor: 'not-a-color'}, root);

        expect(root.style.getPropertyValue('--primary')).toBe('');
    });

    it('accepts a 3-digit hex primaryColor and computes contrast from its expanded form', () => {
        applyHubTheme({primaryColor: '#f00'}, root);

        expect(root.style.getPropertyValue('--primary')).toBe('#f00');
        expect(root.style.getPropertyValue('--ring')).toBe('#f00');
        expect(root.style.getPropertyValue('--primary-foreground')).toBe('#ffffff');
    });

    it('leaves --radius unset when borderRadius is not a supported CSS length', () => {
        applyHubTheme({borderRadius: 'not-a-length'}, root);

        expect(root.style.getPropertyValue('--radius')).toBe('');
    });

    it('repaints the hub surfaces a vendor names, leaving the rest at their shipped defaults', () => {
        const root = document.createElement('div');

        applyHubTheme({activeBorderColor: '#eeeeff', enableColor: '#123456', surfaceColor: '#fafafa'}, root);

        expect(root.style.getPropertyValue('--hub-surface')).toBe('#fafafa');
        expect(root.style.getPropertyValue('--hub-active-border')).toBe('#eeeeff');
        expect(root.style.getPropertyValue('--hub-enable')).toBe('#123456');

        // One colour, not a ramp: the hover shade follows rather than being computed, because a
        // derived shade would be wrong for every colour that is not simple hex.
        expect(root.style.getPropertyValue('--hub-enable-hover')).toBe('#123456');

        // Untouched roles are left alone so the stylesheet's defaults still apply.
        expect(root.style.getPropertyValue('--hub-card')).toBe('');
        expect(root.style.getPropertyValue('--hub-disable')).toBe('');
    });

    it('ignores a surface colour the browser cannot vouch for', () => {
        const root = document.createElement('div');

        applyHubTheme({surfaceColor: 'not-a-colour'}, root);

        expect(root.style.getPropertyValue('--hub-surface')).toBe('');
    });

    // The hub's own chrome is only half of what a vendor embeds -- the builder opens on the same
    // surface. Its tokens are bare HSL triplets consumed as `hsl(var(--token))`, so a vendor's
    // '#0b1220' has to be converted; writing the hex straight in would yield `hsl(#0b1220)`, which
    // is invalid and silently drops the whole declaration.
    it('carries the surface roles into the builder tokens as HSL triplets', () => {
        const root = document.createElement('div');

        applyHubTheme({cardColor: '#ffffff', surfaceColor: '#000000'}, root);

        expect(root.style.getPropertyValue('--hub-surface')).toBe('#000000');
        expect(root.style.getPropertyValue('--hub-card')).toBe('#ffffff');

        // The canvas and the panels on it are the "card" in the builder; the ground around it is
        // the surface.
        expect(root.style.getPropertyValue('--background')).toBe('0 0% 100%');
        expect(root.style.getPropertyValue('--surface-neutral-primary')).toBe('0 0% 100%');
        expect(root.style.getPropertyValue('--surface-main')).toBe('0 0% 0%');
    });

    it('carries primaryColor to the brand surface the builder buttons paint with', () => {
        const root = document.createElement('div');

        applyHubTheme({primaryColor: '#1071e5'}, root);

        expect(root.style.getPropertyValue('--surface-brand-primary')).toBe('213 87% 48%');
    });

    it('leaves the builder tokens alone for a colour it cannot convert to a triplet', () => {
        const root = document.createElement('div');

        applyHubTheme({surfaceColor: 'rebeccapurple'}, root);

        // jsdom cannot resolve a named colour here, and a token that would end up `hsl(rebeccapurple)`
        // is worse than one left at its shipped value.
        expect(root.style.getPropertyValue('--surface-main')).toBe('');
    });

    it('lets cssVariables reach anything the named roles do not cover, and correct what they do', () => {
        const root = document.createElement('div');

        applyHubTheme({cssVariables: {'--hub-surface': '#000000', '--radius': '2rem'}, surfaceColor: '#ffffff'}, root);

        // Applied last on purpose, so the escape hatch can override a named role rather than
        // silently losing to it.
        expect(root.style.getPropertyValue('--hub-surface')).toBe('#000000');
        expect(root.style.getPropertyValue('--radius')).toBe('2rem');
    });

    it('refuses a cssVariables key that is not a custom property', () => {
        const root = document.createElement('div');

        applyHubTheme({cssVariables: {position: 'absolute'}}, root);

        expect(root.style.getPropertyValue('position')).toBe('');
    });
});
