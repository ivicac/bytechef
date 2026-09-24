import type {TestingLibraryMatchers} from '@testing-library/jest-dom/matchers';

// jest-dom 7 augments vitest's Assertion<T>, but vitest 5 declares Assertion<R, T>, so that augmentation never
// merges. Matchers<R, T> is the interface vitest 5 exposes for custom matchers.
declare module 'vitest' {
    // eslint-disable-next-line @typescript-eslint/naming-convention, @typescript-eslint/no-empty-object-type
    interface Matchers<
        R extends void | Promise<void> = void | Promise<void>,
        T = unknown,
    > extends TestingLibraryMatchers<T, R> {}
}
