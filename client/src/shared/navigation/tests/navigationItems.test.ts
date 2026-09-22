import {automationNavigation} from '@/shared/navigation/navigationItems';
import {describe, expect, it} from 'vitest';

describe('automationNavigation', () => {
    it('lists AI Hub and Chats first, leaving Approval Tasks to the user menu', () => {
        expect(automationNavigation.slice(0, 2).map((item) => item.name)).toEqual(['AI Hub', 'Chats']);
        expect(automationNavigation.map((item) => item.name)).not.toContain('Approval Tasks');
    });

    it('leaves Projects ungrouped right after Chats', () => {
        const names = automationNavigation.map((item) => item.name);
        const projectsIndex = automationNavigation.findIndex((item) => item.href === '/automation/projects');

        expect(projectsIndex).toBeGreaterThan(-1);
        expect(automationNavigation[projectsIndex].group).toBeUndefined();
        expect(names[projectsIndex - 1]).toBe('Chats');
    });

    it('points at no standalone data sync surface', () => {
        expect(automationNavigation.filter((item) => item.href.startsWith('/automation/data-sync'))).toEqual([]);
    });
});
