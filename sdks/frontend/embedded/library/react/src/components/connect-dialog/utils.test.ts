import {describe, expect, it} from 'vitest';

import {decodeJwtSubject} from './utils';

describe('decodeJwtSubject', () => {
    it('extracts the sub claim from a JWT', () => {
        const payload = btoa(JSON.stringify({sub: 'user-123'}))
            .replace(/\+/g, '-')
            .replace(/\//g, '_')
            .replace(/=+$/, '');
        const token = `header.${payload}.signature`;

        expect(decodeJwtSubject(token)).toBe('user-123');
    });

    it('returns undefined for a malformed token', () => {
        expect(decodeJwtSubject('not-a-jwt')).toBeUndefined();
    });
});
