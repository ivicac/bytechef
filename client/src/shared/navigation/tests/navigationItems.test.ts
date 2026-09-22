import {automationNavigation, embeddedNavigation} from '@/shared/navigation/navigationItems';
import {describe, expect, it} from 'vitest';

describe('automationNavigation', () => {
    // Projects is the only authoring surface left in automation, so a labelled group of one would
    // say nothing the row does not already say.
    it('has no Build group', () => {
        expect(automationNavigation.filter((item) => item.group === 'Build')).toEqual([]);
    });

    it('leaves Projects ungrouped between Chats and Connections', () => {
        // The Deploy group has its own row also named 'Projects' (href '/automation/deployments'), so the
        // authoring row must be found by href, not by name — indexOf('Projects') would silently match
        // whichever one comes first instead of the one this test means to check.
        const names = automationNavigation.map((item) => item.name);
        const projectsIndex = automationNavigation.findIndex((item) => item.href === '/automation/projects');

        expect(projectsIndex).toBeGreaterThan(-1);
        expect(automationNavigation[projectsIndex].group).toBeUndefined();
        expect(names[projectsIndex - 1]).toBe('Chats');
        expect(names[projectsIndex + 1]).toBe('Connections');
    });

    it('has no Approval Tasks row', () => {
        expect(automationNavigation.filter((item) => item.href === '/automation/approval-tasks')).toEqual([]);
    });

    it('points at no standalone data sync surface', () => {
        expect(automationNavigation.filter((item) => item.href.startsWith('/automation/data-sync'))).toEqual([]);
    });
});

describe('embeddedNavigation', () => {
    // Only the automation Build group dissolved; embedded still groups Integrations and Automations.
    it('keeps its Build group', () => {
        expect(embeddedNavigation.filter((item) => item.group === 'Build').map((item) => item.name)).toEqual([
            'Integrations',
            'Automations',
        ]);
    });
});
