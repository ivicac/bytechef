# Formula and Text Property Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the property "Dynamic" switch with a single **Formula** switch, leaving two modes: Text (a constant or
data pills) and Formula (an `=` expression). The same model applies to the uncontrolled workflow editor and to controlled
react-hook-form surfaces.

**Architecture:**
- A pure module, `propertyInputMode.ts`, derives `{mode, renderer, singlePill, legacyMixed}` from the value. It replaces
  three drifting flags: the reducer's `mentionInput`, `controlledDynamicMode`, and the switch half of `isFormulaMode`.
- Data-pill clicks and drops go through a store-held **pill target** that any focused field registers, not the old
  TipTap-only `focusedInput`.
- Non-string fields render their native control for an empty value or a constant, and a one-pill mentions editor for a
  pill.

**Tech Stack:** React 19, TypeScript 6, Zustand, react-hook-form, TipTap 3.31, Vitest 4 + Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-25-formula-text-property-modes-design.md` (commit `25d81d2fc52`, branch
`formula-text-property-modes-design-spec`). Read it before starting. It has the decisions and the table of controlled
surfaces this plan relies on.

## Global Constraints

- **Target branch: `0_732`.** Work in a worktree cut from `0_732`. The main checkout at `/Volumes/Data/bytechef/bytechef`
  is shared and has another session's uncommitted changes: never `git stash`, and never commit there.
  - Setup: `git -C /Volumes/Data/bytechef/bytechef worktree add -b formula-text-property-modes ../bytechef-formula-modes 0_732`
  - Then `git cherry-pick 25d81d2fc52` plus the commit adding this plan, so the spec and plan travel with the code.
- **Paths:** every path below is relative to the worktree root. `P` =
  `client/src/pages/platform/workflow-editor/components/properties`.
- **Node:** run client tests on Node 24, not the machine default (Node 26 fake-fails ~225 files). Prefix every test
  command with `export NVM_DIR="$HOME/.nvm"; . "$NVM_DIR/nvm.sh" >/dev/null; nvm use default >/dev/null;`. Written
  below as `NODE24;`.
- **Single test file:** `cd client && NODE24; npx vitest run <path relative to client/>`.
- **Full check:** `cd client && NODE24; npm run format && npm run check`. It runs for several minutes, so give the Bash
  tool call `timeout: 600000`. `check` runs `prettier --check` first.
- **Commit messages:** `NNNN client - <description>`. Replace `NNNN` with the ticket number the user supplies; ask if
  none is given.
  - Commit by path: `git commit -m "…" -- <paths>`.
  - No `Co-Authored-By` trailer and no "Generated with" line (user rule).
- **Client lint rules from CLAUDE.md, which ESLint enforces:**
  - object keys in natural ascending order (`sort-keys`, not auto-fixed);
  - interface names end in `I` or `Props`;
  - `useRef` variables end in `Ref`;
  - named imports sorted alphabetically;
  - Lucide icons imported with the `Icon` suffix;
  - `twMerge`, not `cn()`;
  - hook order `useState` → `useRef` → store hooks → custom hooks → derived/`useMemo`/`useCallback` → `useEffect`;
  - no bare Zustand store call without a selector;
  - descriptive variable names, never single letters.
- **Tests:** never flush async work with a bare fixed sleep for store state; use `waitFor`. The existing `settle()`
  helper in the RTL tests is for focus timers and may be reused.
- **Copy:** the switch label is exactly `Formula`. Tooltips are exactly `Switch to formula` / `Switch to text`.
- **Behaviour that must not change:** `fromAi` toggle, look, stored value and read-only behaviour (spec Decision 6).
- **Deviations from the spec, deliberate and minor:**
  1. `formulaEnabled` travels through a React context (`FormulaEnabledContext`), like `CanvasPropertyEditorContext`,
     instead of a prop on five intermediate components. The behaviour is identical.
  2. `getPropertyInputMode` takes `pillEntry` (the `$` handover state) and `hasControl`, and drops the spec's unused
     `hasOptions`/`type` inputs.

## Review Focus

These are the inputs most likely to bite a user that no single task naturally covers. Each has a test in the task named.

1. **`$` typed then abandoned.** Type `$`, then Escape or click away without choosing a pill. The one-pill editor must
   return to the empty native control. It must not save `$` or `$abc` as a constant. (Task 3, Task 5)
2. **Clicking a pill after focusing a select or combobox.** The pill must replace *that* field's value, not land in the
   editor focused before. (Task 6)
3. **A saved `=fromAi(…)` value on a non-string field (uncontrolled and controlled).** It keeps the "Automatically
   defined by the model" rendering, and neither the Formula switch nor the Formula editor appears. (Task 5, Task 8)
4. **A server echo arriving after a pill was inserted into a native field.** The field must stay the one-pill editor
   showing the pill, not flash back to a native input holding the text `${…}`. (Task 4)
5. **Formula → Text on a value that can't convert** (e.g. `=concat(a, b)` on a NUMBER). The value is cleared and saved as
   `null`, and the native control shows empty rather than the stale formula text. (Task 5)

---

### Task 1: Mode model — `propertyInputMode.ts`

**Files:**
- Create: `P/propertyInputMode.ts`
- Test: `P/propertyInputMode.test.ts`

**Interfaces:**
- Consumes: `MENTION_INPUT_PROPERTY_CONTROL_TYPES` from `P/hooks/propertyValueReducer.ts` (existing export).
- Produces (later tasks import exactly these):
  - `interface PropertyInputModeI {legacyMixed: boolean; mode: 'formula' | 'text'; renderer: 'mentions' | 'native'; singlePill: boolean}`
  - `getPropertyInputMode(props: {controlType?: string; formulaMode: boolean; hasControl?: boolean; isFromAi: boolean; pillEntry?: boolean; value: unknown}): PropertyInputModeI`
  - `toFormulaValue(value: unknown, type?: string): string | undefined`. `undefined` means clear.
  - `fromFormulaValue(value: unknown, type?: string): unknown`. `undefined` means clear.
  - `isSingleDataPill(value: unknown): value is string`
  - `shouldIncludeInMetadata(value: unknown, custom?: boolean): boolean`

- [ ] **Step 1: Write the failing test**

Create `P/propertyInputMode.test.ts`:

```ts
import {describe, expect, it} from 'vitest';

import {
    fromFormulaValue,
    getPropertyInputMode,
    isSingleDataPill,
    shouldIncludeInMetadata,
    toFormulaValue,
} from './propertyInputMode';

describe('getPropertyInputMode', () => {
    it.each([
        {
            controlType: 'INTEGER',
            expected: {legacyMixed: false, mode: 'text', renderer: 'native', singlePill: false},
            name: 'empty number renders the native control',
            value: '',
        },
        {
            controlType: 'INTEGER',
            expected: {legacyMixed: false, mode: 'text', renderer: 'native', singlePill: false},
            name: 'constant number renders the native control',
            value: 5,
        },
        {
            controlType: 'SELECT',
            expected: {legacyMixed: false, mode: 'text', renderer: 'mentions', singlePill: true},
            name: 'a lone pill on a select renders the one-pill editor',
            value: '${trigger_1.id}',
        },
        {
            controlType: 'NUMBER',
            expected: {legacyMixed: true, mode: 'text', renderer: 'mentions', singlePill: false},
            name: 'text around a pill on a non-string is legacy mixed',
            value: '${trigger_1.id} ms',
        },
        {
            controlType: 'DATE',
            expected: {legacyMixed: true, mode: 'text', renderer: 'mentions', singlePill: false},
            name: 'two pills on a non-string are legacy mixed',
            value: '${a.b}${c.d}',
        },
        {
            controlType: 'TEXT',
            expected: {legacyMixed: false, mode: 'text', renderer: 'mentions', singlePill: false},
            name: 'a text-like control always mixes text and pills',
            value: 'hello ${a.b}',
        },
        {
            controlType: 'INTEGER',
            expected: {legacyMixed: false, mode: 'formula', renderer: 'mentions', singlePill: false},
            name: 'an = value is formula',
            value: '=1 + 2',
        },
        {
            controlType: 'BOOLEAN',
            expected: {legacyMixed: false, mode: 'formula', renderer: 'mentions', singlePill: false},
            name: 'a customised fromAi value that is not flagged fromAi is formula',
            value: "=fromAi('x', 'BOOLEAN', {'required': true, 'note': 'edited'})",
        },
    ])('$name', ({controlType, expected, value}) => {
        expect(getPropertyInputMode({controlType, formulaMode: false, isFromAi: false, value})).toEqual(expected);
    });

    it('formula state wins over an empty value', () => {
        expect(getPropertyInputMode({controlType: 'INTEGER', formulaMode: true, isFromAi: false, value: ''})).toEqual({
            legacyMixed: false,
            mode: 'formula',
            renderer: 'mentions',
            singlePill: false,
        });
    });

    it('FORMULA_MODE control type is always formula', () => {
        expect(getPropertyInputMode({controlType: 'FORMULA_MODE', formulaMode: false, isFromAi: false, value: ''}).mode).toBe(
            'formula'
        );
    });

    it('fromAi wins over an = value and never reports formula', () => {
        expect(
            getPropertyInputMode({
                controlType: 'INTEGER',
                formulaMode: true,
                isFromAi: true,
                value: "=fromAi('count', 'INTEGER', {'required': false})",
            })
        ).toEqual({legacyMixed: false, mode: 'text', renderer: 'mentions', singlePill: false});
    });

    it('pill entry shows the one-pill editor on an empty value', () => {
        expect(
            getPropertyInputMode({controlType: 'INTEGER', formulaMode: false, isFromAi: false, pillEntry: true, value: ''})
        ).toEqual({legacyMixed: false, mode: 'text', renderer: 'mentions', singlePill: true});
    });

    it('a controlled text-like field is a native input', () => {
        expect(
            getPropertyInputMode({controlType: 'TEXT', formulaMode: false, hasControl: true, isFromAi: false, value: 'x'})
                .renderer
        ).toBe('native');
    });

    it('an uncontrolled FILE_ENTRY is a mentions input, a controlled one is not', () => {
        expect(
            getPropertyInputMode({controlType: 'FILE_ENTRY', formulaMode: false, isFromAi: false, value: ''}).renderer
        ).toBe('mentions');
        expect(
            getPropertyInputMode({controlType: 'FILE_ENTRY', formulaMode: false, hasControl: true, isFromAi: false, value: ''})
                .renderer
        ).toBe('native');
    });
});

describe('toFormulaValue', () => {
    it.each([
        {expected: undefined, type: 'INTEGER', value: ''},
        {expected: undefined, type: 'INTEGER', value: null},
        {expected: '=5', type: 'INTEGER', value: 5},
        {expected: '=5', type: 'INTEGER', value: '5'},
        {expected: '=true', type: 'BOOLEAN', value: true},
        {expected: '=${a.b}', type: 'NUMBER', value: '${a.b}'},
        {expected: "='CURRENT_EXECUTION'", type: 'STRING', value: 'CURRENT_EXECUTION'},
        {expected: "='123'", type: 'STRING', value: '123'},
        {expected: "='it''s'", type: 'STRING', value: "it's"},
        {expected: undefined, type: 'STRING', value: 'hello ${a.b}'},
        {expected: '=1+1', type: 'INTEGER', value: '=1+1'},
        {expected: undefined, type: 'OBJECT', value: {key: 'value'}},
    ])('$value ($type) → $expected', ({expected, type, value}) => {
        expect(toFormulaValue(value, type)).toBe(expected);
    });
});

describe('fromFormulaValue', () => {
    it.each([
        {expected: '${a.b}', type: 'NUMBER', value: '=${a.b}'},
        {expected: 5, type: 'INTEGER', value: '=5'},
        {expected: 2.5, type: 'NUMBER', value: '=2.5'},
        {expected: undefined, type: 'INTEGER', value: '=2.5'},
        {expected: true, type: 'BOOLEAN', value: '=true'},
        {expected: 'x', type: 'STRING', value: "='x'"},
        {expected: "it's", type: 'STRING', value: "='it''s'"},
        {expected: undefined, type: 'NUMBER', value: "='x'"},
        {expected: undefined, type: 'NUMBER', value: '=concat(a, b)'},
        {expected: undefined, type: 'STRING', value: '='},
        {expected: undefined, type: 'STRING', value: 'plain'},
    ])('$value ($type) → $expected', ({expected, type, value}) => {
        expect(fromFormulaValue(value, type)).toBe(expected);
    });
});

describe('helpers', () => {
    it('recognises exactly one pill', () => {
        expect(isSingleDataPill('${a.b[0]}')).toBe(true);
        expect(isSingleDataPill('${a}${b}')).toBe(false);
        expect(isSingleDataPill(' ${a}')).toBe(false);
        expect(isSingleDataPill(5)).toBe(false);
    });

    it('records metadata for expressions and pills, and falls back to custom for constants', () => {
        expect(shouldIncludeInMetadata('=1', false)).toBe(true);
        expect(shouldIncludeInMetadata('${a.b}', false)).toBe(true);
        expect(shouldIncludeInMetadata(5, false)).toBe(false);
        expect(shouldIncludeInMetadata(5, true)).toBe(true);
    });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/properties/propertyInputMode.test.ts`
Expected: FAIL with `Failed to resolve import "./propertyInputMode"`.

- [ ] **Step 3: Implement**

Create `P/propertyInputMode.ts`:

```ts
import {MENTION_INPUT_PROPERTY_CONTROL_TYPES} from '@/pages/platform/workflow-editor/components/properties/hooks/propertyValueReducer';

const SINGLE_DATA_PILL_REGEX = /^\$\{[^}]+}$/;
const INTEGER_LITERAL_REGEX = /^-?\d+$/;
const NUMBER_LITERAL_REGEX = /^-?\d+(\.\d+)?$/;
const QUOTED_STRING_LITERAL_REGEX = /^'((?:[^']|'')*)'$/;

export interface PropertyInputModeI {
    legacyMixed: boolean;
    mode: 'formula' | 'text';
    renderer: 'mentions' | 'native';
    singlePill: boolean;
}

interface GetPropertyInputModePropsI {
    controlType?: string;
    formulaMode: boolean;
    hasControl?: boolean;
    isFromAi: boolean;
    pillEntry?: boolean;
    value: unknown;
}

const TEXT_MENTIONS: PropertyInputModeI = {legacyMixed: false, mode: 'text', renderer: 'mentions', singlePill: false};

export function isSingleDataPill(value: unknown): value is string {
    return typeof value === 'string' && SINGLE_DATA_PILL_REGEX.test(value);
}

function isTextLikeControlType(controlType: string | undefined, hasControl: boolean): boolean {
    if (!controlType || hasControl) {
        return false;
    }

    return controlType === 'FILE_ENTRY' || MENTION_INPUT_PROPERTY_CONTROL_TYPES.includes(controlType);
}

/**
 * Text or Formula, and which control renders the value. See
 * docs/superpowers/specs/2026-09-25-formula-text-property-modes-design.md §1. The first matching rule wins.
 */
export function getPropertyInputMode({
    controlType,
    formulaMode,
    hasControl = false,
    isFromAi,
    pillEntry = false,
    value,
}: GetPropertyInputModePropsI): PropertyInputModeI {
    if (isFromAi) {
        return TEXT_MENTIONS;
    }

    const isFormulaValue = typeof value === 'string' && value.startsWith('=');

    if (formulaMode || controlType === 'FORMULA_MODE' || isFormulaValue) {
        return {legacyMixed: false, mode: 'formula', renderer: 'mentions', singlePill: false};
    }

    if (isTextLikeControlType(controlType, hasControl)) {
        return TEXT_MENTIONS;
    }

    if (isSingleDataPill(value)) {
        return {legacyMixed: false, mode: 'text', renderer: 'mentions', singlePill: true};
    }

    if (typeof value === 'string' && value.includes('${')) {
        return {legacyMixed: true, mode: 'text', renderer: 'mentions', singlePill: false};
    }

    if (pillEntry) {
        return {legacyMixed: false, mode: 'text', renderer: 'mentions', singlePill: true};
    }

    return {legacyMixed: false, mode: 'text', renderer: 'native', singlePill: false};
}

export function toFormulaValue(value: unknown, type?: string): string | undefined {
    if (value === undefined || value === null || value === '') {
        return undefined;
    }

    if (typeof value === 'number' || typeof value === 'boolean') {
        return `=${value}`;
    }

    if (typeof value !== 'string') {
        return undefined;
    }

    if (value.startsWith('=')) {
        return value;
    }

    if (isSingleDataPill(value)) {
        return `=${value}`;
    }

    if (value.includes('${')) {
        return undefined;
    }

    const isLiteralOfType =
        ((type === 'INTEGER' || type === 'NUMBER') && NUMBER_LITERAL_REGEX.test(value)) ||
        (type === 'BOOLEAN' && (value === 'true' || value === 'false'));

    if (isLiteralOfType) {
        return `=${value}`;
    }

    return `='${value.replace(/'/g, "''")}'`;
}

export function fromFormulaValue(value: unknown, type?: string): unknown {
    if (typeof value !== 'string' || !value.startsWith('=')) {
        return undefined;
    }

    const body = value.substring(1).trim();

    if (body === '') {
        return undefined;
    }

    if (isSingleDataPill(body)) {
        return body;
    }

    if (type === 'INTEGER') {
        return INTEGER_LITERAL_REGEX.test(body) ? parseInt(body, 10) : undefined;
    }

    if (type === 'NUMBER') {
        return NUMBER_LITERAL_REGEX.test(body) ? parseFloat(body) : undefined;
    }

    if (type === 'BOOLEAN') {
        return body === 'true' || body === 'false' ? body === 'true' : undefined;
    }

    const quotedMatch = QUOTED_STRING_LITERAL_REGEX.exec(body);

    return quotedMatch ? quotedMatch[1].replace(/''/g, "'") : undefined;
}

export function shouldIncludeInMetadata(value: unknown, custom?: boolean): boolean {
    if (typeof value === 'string' && (value.startsWith('=') || value.includes('${'))) {
        return true;
    }

    return !!custom;
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: the same command as Step 2. Expected: PASS, all cases.

- [ ] **Step 5: Commit**

```bash
git add client/src/pages/platform/workflow-editor/components/properties/propertyInputMode.ts client/src/pages/platform/workflow-editor/components/properties/propertyInputMode.test.ts
git commit -m "NNNN client - Add the Text/Formula property input mode model" -- client/src/pages/platform/workflow-editor/components/properties/propertyInputMode.ts client/src/pages/platform/workflow-editor/components/properties/propertyInputMode.test.ts
```

---

### Task 2: Pill target in the store, with no behaviour change

Replace `focusedInput: Editor | null` with a pill target that any field can register. This task keeps today's
behaviour: only the mentions editor registers, and a native input still clears the target on focus.

**Files:**
- Create: `client/src/pages/platform/workflow-editor/components/datapills/pillTarget.ts`
- Test: `client/src/pages/platform/workflow-editor/components/datapills/pillTarget.test.ts`
- Modify: `client/src/pages/platform/workflow-editor/stores/useWorkflowNodeDetailsPanelStore.ts:23-24,63-64,91`
- Modify: `client/src/pages/platform/workflow-editor/components/datapills/DataPill.tsx:37-55,102-116,126-172`
- Modify: `P/components/property-mentions-input/PropertyMentionsInput.tsx:119-165,200-209`
- Modify: `P/components/property-mentions-input/PropertyMentionsInputEditor.tsx:1,461-517`
- Modify: `P/components/property-input/PropertyInput.tsx:67,175`
- Modify: `P/hooks/useProperty.ts:223-229,802,950,995,1233,1251`
- Modify tests: `P/components/property-mentions-input/tests/PropertyMentionsInputFormulaEntry.test.tsx:59,138`,
  `P/components/property-mentions-input/tests/formulaModeEntryFocus.test.tsx:59,78`,
  `P/hooks/tests/usePropertyTriggerClusterRoot.test.ts:13`, `P/hooks/tests/usePropertyValueState.test.ts:24`

**Interfaces:**
- Produces:
  - `interface PillTargetI {acceptsPill: () => boolean; insertPill: (mentionId: string) => void; owner: unknown}`
  - `createEditorPillTarget({acceptsPill, editor, singlePill}: {acceptsPill: () => boolean; editor: Editor; singlePill?: boolean}): PillTargetI`
  - Store fields `pillTarget: PillTargetI | null`, `setPillTarget(pillTarget: PillTargetI | null)`,
    `clearPillTarget(owner: unknown)`. The last clears only if `pillTarget.owner === owner`.

- [ ] **Step 1: Write the failing test**

Create `client/src/pages/platform/workflow-editor/components/datapills/pillTarget.test.ts`:

```ts
import useWorkflowNodeDetailsPanelStore from '@/pages/platform/workflow-editor/stores/useWorkflowNodeDetailsPanelStore';
import {Editor} from '@tiptap/core';
import Document from '@tiptap/extension-document';
import {Mention} from '@tiptap/extension-mention';
import Paragraph from '@tiptap/extension-paragraph';
import Text from '@tiptap/extension-text';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {createEditorPillTarget} from './pillTarget';

const createEditor = (content: string) =>
    new Editor({content, extensions: [Document, Paragraph, Text, Mention.configure({})]});

const mentionIds = (editor: Editor) => {
    const ids: string[] = [];

    editor.state.doc.descendants((node) => {
        if (node.type.name === 'mention') {
            ids.push(node.attrs.id);
        }
    });

    return ids;
};

describe('pill target', () => {
    let editor: Editor;

    beforeEach(() => {
        useWorkflowNodeDetailsPanelStore.setState({pillTarget: null});
    });

    afterEach(() => {
        editor?.destroy();
    });

    it('inserts a pill next to existing content', () => {
        editor = createEditor('<p>hello </p>');

        createEditorPillTarget({acceptsPill: () => true, editor}).insertPill('trigger_1.id');

        expect(mentionIds(editor)).toEqual(['trigger_1.id']);
        expect(editor.state.doc.textContent.startsWith('hello')).toBe(true);
    });

    it('replaces the whole content in single-pill mode', () => {
        editor = createEditor('<p><span data-type="mention" data-id="old.pill"></span></p>');

        createEditorPillTarget({acceptsPill: () => true, editor, singlePill: true}).insertPill('new.pill');

        expect(mentionIds(editor)).toEqual(['new.pill']);
    });

    it('clears the target only for its own owner', () => {
        editor = createEditor('<p></p>');

        const pillTarget = createEditorPillTarget({acceptsPill: () => true, editor});

        useWorkflowNodeDetailsPanelStore.getState().setPillTarget(pillTarget);
        useWorkflowNodeDetailsPanelStore.getState().clearPillTarget({});

        expect(useWorkflowNodeDetailsPanelStore.getState().pillTarget).toBe(pillTarget);

        useWorkflowNodeDetailsPanelStore.getState().clearPillTarget(editor);

        expect(useWorkflowNodeDetailsPanelStore.getState().pillTarget).toBeNull();
    });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/datapills/pillTarget.test.ts`
Expected: FAIL with `Failed to resolve import "./pillTarget"`.

- [ ] **Step 3: Implement the module and the store**

Create `client/src/pages/platform/workflow-editor/components/datapills/pillTarget.ts`:

```ts
import {Editor} from '@tiptap/react';

/**
 * Where a data pill lands when one is clicked in the Data Pill Panel: whichever property field registered
 * last. A field registers on focus and stays registered after blur, because clicking the panel itself blurs it.
 * `owner` identifies the registrant so an unmounting field clears only its own registration.
 */
export interface PillTargetI {
    acceptsPill: () => boolean;
    insertPill: (mentionId: string) => void;
    owner: unknown;
}

interface CreateEditorPillTargetPropsI {
    acceptsPill: () => boolean;
    editor: Editor;
    singlePill?: boolean;
}

export function createEditorPillTarget({acceptsPill, editor, singlePill = false}: CreateEditorPillTargetPropsI): PillTargetI {
    return {
        acceptsPill,
        insertPill: (mentionId) => {
            const mention = {attrs: {id: mentionId}, type: 'mention'};

            if (singlePill) {
                // One transaction: selecting everything and inserting over it replaces the pill without an
                // intermediate empty document, which would read as "pill deleted" and swap the native control back.
                editor.chain().focus().selectAll().insertContent(mention).run();

                return;
            }

            editor.chain().focus().insertContent(mention).run();
        },
        owner: editor,
    };
}
```

In `useWorkflowNodeDetailsPanelStore.ts`:
- Replace the import `import {Editor} from '@tiptap/react';` with
  `import {PillTargetI} from '@/pages/platform/workflow-editor/components/datapills/pillTarget';`.
- Replace the two interface lines `focusedInput…`/`setFocusedInput…` with:

```ts
    pillTarget: PillTargetI | null;
    clearPillTarget: (owner: unknown) => void;
    setPillTarget: (pillTarget: PillTargetI | null) => void;
```

- Replace the two implementation lines `focusedInput: null, setFocusedInput: …` with:

```ts
            pillTarget: null,
            clearPillTarget: (owner) =>
                set((state) => (state.pillTarget?.owner === owner ? {...state, pillTarget: null} : state)),
            setPillTarget: (pillTarget) => set((state) => ({...state, pillTarget})),
```

- In `reset`, replace `focusedInput: null,` with `pillTarget: null,`. The file has `/* eslint-disable sort-keys */`, so
  the grouping is fine.

- [ ] **Step 4: Run the test and confirm it passes**

Run: the same command as Step 2. Expected: PASS.

- [ ] **Step 5: Rewire the consumers**

`DataPill.tsx`:
- Delete `canInsertMentionForProperty` (lines 37-55) and `canInsertDataPill` (lines 102-116).
- Remove the now-unused imports: `encodePath`, `safeResolvePath`, `NodeDataType` if nothing else uses it, and `Editor`.
- Replace the store selection and the click handler:

```tsx
    const {pillTarget} = useWorkflowNodeDetailsPanelStore(
        useShallow((state) => ({
            pillTarget: state.pillTarget,
        }))
    );
```

```tsx
    const handleDataPillClick = ({
        parentPropertyName,
        path,
        propertyName,
        workflowNodeName,
    }: HandleDataPillClickProps) => {
        if (!pillTarget || !pillTarget.acceptsPill()) {
            return;
        }

        pillTarget.insertPill(
            buildMentionId({
                parentPropertyName,
                path,
                propertyName,
                workflowNodeName,
            })
        );
    };
```

- If `currentNode` is still read elsewhere in `DataPill`, keep it in the selector. Check with
  `grep -n currentNode client/src/pages/platform/workflow-editor/components/datapills/DataPill.tsx`.

`PropertyMentionsInputEditor.tsx`:
- Delete the `canInsertMentionForProperty` import (line 1).
- In `handleDrop`, replace the guard

```ts
                const attributes = view.props.attributes as Record<string, string>;
                const parameters = currentNode?.parameters || {};

                if (!canInsertMentionForProperty(attributes.type, parameters, attributes.path)) {
                    return true;
                }
```

  with

```ts
                if (expressionEnabled === false) {
                    return true;
                }
```

- Change the `handleDrop` deps to `[expressionEnabled, isFromAi]`.

`PropertyMentionsInput.tsx`:
- Import `createEditorPillTarget` from `@/pages/platform/workflow-editor/components/datapills/pillTarget`.
- Replace the store selector:

```tsx
        const {clearPillTarget, pillTarget, setPillTarget, workflowNodeDetailsPanelOpen} =
            useWorkflowNodeDetailsPanelStore(
                useShallow((state) => ({
                    clearPillTarget: state.clearPillTarget,
                    pillTarget: state.pillTarget,
                    setPillTarget: state.setPillTarget,
                    workflowNodeDetailsPanelOpen: state.workflowNodeDetailsPanelOpen,
                }))
            );
```

- Add, after `openDataPillPanel`/`canvasPropertyEditor`:

```tsx
        const registerPillTarget = useCallback(
            (editor: Editor) =>
                setPillTarget(
                    createEditorPillTarget({
                        acceptsPill: () => expressionEnabled !== false && !isFromAi,
                        editor,
                    })
                ),
            [expressionEnabled, isFromAi, setPillTarget]
        );
```

- Change `onFocus` to call `registerPillTarget(editor)` instead of `setFocusedInput(editor)`.
- In `handleEditorValueChange`, replace `setFocusedInput(localEditorRef.current)` with
  `registerPillTarget(localEditorRef.current)`, and change its deps to
  `[expressionEnabled, registerPillTarget, setIsFormulaMode]`.
- Change the `isFocused` effect to compare owners:

```tsx
        useEffect(() => {
            if (!pillTarget || !localEditorRef.current) {
                setIsFocused(false);

                return;
            }

            setIsFocused(pillTarget.owner === localEditorRef.current);
        }, [pillTarget]);
```

- Add an unmount cleanup effect, placed with the other effects:

```tsx
        useEffect(() => {
            const editorAtMount = localEditorRef;

            return () => {
                if (editorAtMount.current) {
                    clearPillTarget(editorAtMount.current);
                }
            };
        }, [clearPillTarget]);
```

`PropertyInput.tsx`:
- Line 67: `const setPillTarget = useWorkflowNodeDetailsPanelStore((state) => state.setPillTarget);`
- Line 175: `setPillTarget(null);`. This keeps today's "focusing a native input disarms pills" behaviour; Task 6 removes it.

`useProperty.ts`:
- Remove `setFocusedInput` from the store selector (lines 223-229) and from the `handleFromAiClick` deps (line 1251).
- Line 802: delete `setFocusedInput(editorRef.current);`. The preceding `focus()` registers through the editor's `onFocus`.
- Line 950, inside `setTimeout`: replace with `editorRef.current?.commands.focus();`.
- Line 995: delete; the `focus()` two lines below registers.
- Line 1233: delete; `focus()` on line 1231 registers.

Tests:
- In `PropertyMentionsInputFormulaEntry.test.tsx` and `formulaModeEntryFocus.test.tsx`, replace `focusedInput: null` with
  `pillTarget: null`.
- In the same two files, replace `useWorkflowNodeDetailsPanelStore.getState().focusedInput).toBe(editor)` with
  `useWorkflowNodeDetailsPanelStore.getState().pillTarget?.owner).toBe(editor)`.
- In `usePropertyTriggerClusterRoot.test.ts` and `usePropertyValueState.test.ts`, replace `setFocusedInput: vi.fn(),`
  with `clearPillTarget: vi.fn(),` and `setPillTarget: vi.fn(),` on two lines, sorted.

- [ ] **Step 6: Typecheck and run the affected tests**

Run: `cd client && NODE24; npx tsc --project tsconfig.json --noEmit`
Expected: no errors. If `focusedInput`/`setFocusedInput` still appears anywhere, fix it:
`grep -rn "focusedInput\|setFocusedInput" src`.

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/datapills src/pages/platform/workflow-editor/components/properties`
Expected: PASS. Before changing anything, compare any failure against a run of the same command on a clean `0_732`
checkout, to separate pre-existing failures from ones this task introduced.

- [ ] **Step 7: Commit**

```bash
git add -A client/src/pages/platform/workflow-editor
git commit -m "NNNN client - Route data pill clicks through a registered pill target" -- client/src/pages/platform/workflow-editor
```

---

### Task 3: Mentions editor — single-pill mode, requested focus, initial input

**Files:**
- Modify: `P/components/property-mentions-input/PropertyMentionsInputEditor.tsx` (props, `handleKeyPress`, `onUpdate`,
  `onBlur`, `handleDrop`, the autofocus effect)
- Modify: `P/components/property-mentions-input/PropertyMentionsInput.tsx` (props pass-through, pill target `singlePill`)
- Test: `P/components/property-mentions-input/tests/singlePillEditor.test.tsx`

**Interfaces:**
- Consumes: `createEditorPillTarget` (Task 2).
- Produces:
  - New props on both `PropertyMentionsInput` and `PropertyMentionsInputEditor`:
    - `singlePill?: boolean`
    - `focusRequest?: {initialInput?: string; token: number}`. Each new `token` focuses the editor at the end once it
      exists, then types `initialInput` if given.
    - `onSinglePillAbandoned?: () => void`. Called on blur when single-pill content holds no pill.
  - Single-pill contract:
    - empty: only `$` is accepted;
    - pill present: only Backspace and Delete;
    - a new pill replaces the old one;
    - `onValueChange` reports `''` or exactly `${id}`.

- [ ] **Step 1: Write the failing test**

Create `P/components/property-mentions-input/tests/singlePillEditor.test.tsx`. It reuses the harness shape of
`formulaModeEntryFocus.test.tsx` in the same folder, so copy that file's imports, store setup and `settle()` helper, then
add:

```tsx
const renderSinglePill = (props: Partial<Parameters<typeof PropertyMentionsInput>[0]> = {}) => {
    const onValueChange = vi.fn();
    const onSinglePillAbandoned = vi.fn();

    let editor: Editor | null = null;

    const EditorCapture = () => (
        <PropertyMentionsInput
            controlType="INTEGER"
            disableAutoSave
            expressionEnabled
            onSinglePillAbandoned={onSinglePillAbandoned}
            onValueChange={onValueChange}
            path="parameters.count"
            ref={(instance) => {
                editor = instance;
            }}
            singlePill
            type="INTEGER"
            {...props}
        />
    );

    const view = render(<EditorCapture />);

    return {getEditor: () => editor!, onSinglePillAbandoned, onValueChange, view};
};

describe('single-pill editor', () => {
    it('replaces the pill when another is inserted through the pill target', async () => {
        const {getEditor, onValueChange} = renderSinglePill({value: '${old.pill}'});

        await settle();

        act(() => {
            getEditor().commands.focus();
        });

        act(() => {
            useWorkflowNodeDetailsPanelStore.getState().pillTarget!.insertPill('new.pill');
        });

        await waitFor(() => expect(onValueChange).toHaveBeenLastCalledWith('${new.pill}'));
    });

    it('reports an empty value when the pill is deleted', async () => {
        const {getEditor, onValueChange} = renderSinglePill({value: '${old.pill}'});

        await settle();

        act(() => {
            getEditor().commands.clearContent(true);
        });

        await waitFor(() => expect(onValueChange).toHaveBeenLastCalledWith(''));
    });

    it('types the initial input once focus is requested', async () => {
        const {getEditor} = renderSinglePill({focusRequest: {initialInput: '$', token: 1}, value: ''});

        await settle();

        expect(getEditor().state.doc.textContent).toBe('$');
    });

    it('reports an abandoned pill entry on blur when no pill was picked', async () => {
        const {getEditor, onSinglePillAbandoned} = renderSinglePill({focusRequest: {initialInput: '$', token: 1}, value: ''});

        await settle();

        act(() => {
            getEditor().commands.blur();
        });

        await waitFor(() => expect(onSinglePillAbandoned).toHaveBeenCalledTimes(1));
    });

    it('does not report abandonment when a pill is present', async () => {
        const {getEditor, onSinglePillAbandoned} = renderSinglePill({value: '${a.b}'});

        await settle();

        act(() => {
            getEditor().commands.focus();
            getEditor().commands.blur();
        });

        await settle();

        expect(onSinglePillAbandoned).not.toHaveBeenCalled();
    });
});
```

- `commands.blur()` fires TipTap's `onBlur` even under jsdom. If it doesn't, dispatch
  `getEditor().view.dom.dispatchEvent(new FocusEvent('blur'))` instead.
- The `$`-popup-opens check is manual (Task 9), because the suggestion's `allow()` needs real DOM focus.

- [ ] **Step 2: Run the test and confirm it fails**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/properties/components/property-mentions-input/tests/singlePillEditor.test.tsx`
Expected: FAIL. TypeScript reports unknown props `singlePill`/`focusRequest`/`onSinglePillAbandoned`, and the
replace/blur assertions fail.

- [ ] **Step 3: Implement in `PropertyMentionsInputEditor.tsx`**

1. Add to `PropertyMentionsInputEditorProps` (keep keys sorted):
   ```ts
   focusRequest?: {initialInput?: string; token: number};
   onSinglePillAbandoned?: () => void;
   singlePill?: boolean;
   ```
   Destructure them, with `singlePill = false`.
2. Add a helper above the component:
   ```ts
   const countMentionNodes = (editor: {state: {doc: {descendants: (callback: (node: {type: {name: string}}) => void) => void}}}) => {
       let mentionCount = 0;

       editor.state.doc.descendants((node) => {
           if (node.type.name === 'mention') {
               mentionCount++;
           }
       });

       return mentionCount;
   };
   ```
3. In `onUpdate`, delete `const propertyMentions = value.match(/property-mention/g);` and
   `setMentionOccurences(propertyMentions?.length || 0);`, then add `setMentionOccurences(countMentionNodes(editor));`.
   The old count ran on the serialized `${…}` text and was always 0.
4. Replace `handleKeyPress`:
   ```ts
                handleKeyPress: (editorView: EditorView, event: KeyboardEvent) => {
                    const isEditorEmpty = editorView.state.doc.textContent.length === 0 && mentionOccurences === 0;

                    if ((event.key === '=' && isEditorEmpty && !singlePill) || isFormulaMode) {
                        return;
                    }

                    if (DataPillSuggestionPluginKey.getState(editorView.state)?.active) {
                        return;
                    }

                    const restrictsToOnePill = singlePill || type !== 'STRING';

                    if (restrictsToOnePill && (mentionOccurences || event.key !== '$')) {
                        event.preventDefault();
                    }
                },
   ```
   `handleKeyPress` only fires for printable keys, so Backspace and Delete still work.
5. In `handleDrop`, before inserting, branch on single-pill mode:
   ```ts
                if (singlePill) {
                    editorRef.current
                        ?.chain()
                        .focus()
                        .selectAll()
                        .insertContent({attrs: {id: payload.mentionId}, type: 'mention'})
                        .run();

                    return true;
                }
   ```
   Add `singlePill` to the deps.
6. In the `onBlur` option, add before the existing unsaved-suggestion handling:
   ```ts
                if (singlePill && editor && countMentionNodes(editor) === 0) {
                    unsavedSuggestionValueRef.current = undefined;

                    editor.commands.clearContent(false);

                    onSinglePillAbandoned?.();

                    return;
                }
   ```
   `useEditor`'s option closures read `editor` from the enclosing scope, the same way `onFocus` already does.
7. Next to the existing `autoFocus` effect, add a focus-request effect. It waits for `editor`, which is what replaces
   the 50 ms timers in `useProperty`:
   ```ts
        const appliedFocusTokenRef = useRef<number | undefined>(undefined);
   ```
   Declare it with the other `useRef`s, then add:
   ```ts
        useEffect(() => {
            if (!editor || !focusRequest || appliedFocusTokenRef.current === focusRequest.token) {
                return;
            }

            appliedFocusTokenRef.current = focusRequest.token;

            editor.view.dom.focus({preventScroll: true});

            editor.commands.focus('end');

            if (focusRequest.initialInput) {
                editor.commands.insertContent(focusRequest.initialInput);
            }
        }, [editor, focusRequest]);
   ```

- [ ] **Step 4: Implement in `PropertyMentionsInput.tsx`**

1. Add `focusRequest`, `onSinglePillAbandoned` and `singlePill` to `PropertyMentionsInputProps` (sorted). Destructure
   them and forward all three to `<PropertyMentionsInputEditor …>`.
2. Pass `singlePill` into `createEditorPillTarget` inside `registerPillTarget`, and add `singlePill` to its deps.

- [ ] **Step 5: Run the test and confirm it passes**

Run: the same command as Step 2. Expected: PASS.

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/properties/components/property-mentions-input`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git commit -m "NNNN client - Add a single-pill mode and requested focus to the mentions editor" -- client/src/pages/platform/workflow-editor/components/properties/components/property-mentions-input
```

---

### Task 4: Value reducer — mention rendering follows the value

The reducer stops storing `mentionInput`. It works out whether a value renders in the mentions editor from the value
itself, using `getPropertyInputMode`, and it gains two explicit transitions.

**Files:**
- Modify: `P/hooks/propertyValueReducer.ts`
- Test: `P/hooks/tests/propertyValueReducerModes.test.ts`

**Interfaces:**
- Consumes: `getPropertyInputMode` (Task 1).
- Produces:
  - `ParameterValueContextI` gains `formulaMode: boolean` and `mentionInput: boolean`. The latter is the renderer
    before the change, supplied by the hook.
  - `PropertyValueStateI` loses `mentionInput`.
  - New actions:
    - `{type: 'pillValueSet'; value: string}`: sets `propertyParameterValue`, `mentionInputValue` and
      `mentionInputSyncedValue` to the pill.
    - `{type: 'valueCleared'}`: sets every display value empty and `propertyParameterValue` to `''`.
  - Removed actions: `mentionInputModeChanged`, `inputTypeSwitched`.

- [ ] **Step 1: Write the failing test**

Create `P/hooks/tests/propertyValueReducerModes.test.ts`:

```ts
import {describe, expect, it} from 'vitest';

import {ParameterValueContextI, getInitialPropertyValueState, propertyValueReducer} from '../propertyValueReducer';

const integerContext: ParameterValueContextI = {
    controlType: 'INTEGER',
    formulaMode: false,
    isNumericalInput: true,
    mentionInput: false,
    type: 'INTEGER',
};

const initialIntegerState = () =>
    getInitialPropertyValueState({controlType: 'INTEGER', defaultValue: undefined, hasControl: false, parameterValue: 5});

describe('propertyValueReducer modes', () => {
    it('no longer stores mentionInput', () => {
        expect('mentionInput' in initialIntegerState()).toBe(false);
    });

    it('syncs the editor value when a pill arrives for a field that was native', () => {
        const nextState = propertyValueReducer(initialIntegerState(), {
            context: integerContext,
            type: 'parameterValueResolved',
            value: '${trigger_1.count}',
        });

        expect(nextState.mentionInputValue).toBe('${trigger_1.count}');
        expect(nextState.propertyParameterValue).toBe('${trigger_1.count}');
        expect(nextState.inputValue).not.toBe('${trigger_1.count}');
    });

    it('keeps the pill when the server echoes it back after pillValueSet', () => {
        const withPill = propertyValueReducer(initialIntegerState(), {type: 'pillValueSet', value: '${a.b}'});

        const afterEcho = propertyValueReducer(withPill, {
            context: {...integerContext, mentionInput: true},
            type: 'parameterValueResolved',
            value: '${a.b}',
        });

        expect(afterEcho.mentionInputValue).toBe('${a.b}');
        expect(afterEcho.propertyParameterValue).toBe('${a.b}');
    });

    it('clears every display value on valueCleared', () => {
        const withPill = propertyValueReducer(initialIntegerState(), {type: 'pillValueSet', value: '${a.b}'});
        const cleared = propertyValueReducer(withPill, {type: 'valueCleared'});

        expect(cleared).toMatchObject({
            inputValue: '',
            mentionInputSyncedValue: undefined,
            mentionInputValue: '',
            multiSelectValue: [],
            propertyParameterValue: '',
            selectValue: '',
        });
    });

    it('does not turn a pill string into a multi-select array', () => {
        const multiSelectState = getInitialPropertyValueState({
            controlType: 'MULTI_SELECT',
            defaultValue: undefined,
            hasControl: false,
            parameterValue: ['a'],
        });

        const nextState = propertyValueReducer(multiSelectState, {
            context: {controlType: 'MULTI_SELECT', formulaMode: false, isNumericalInput: false, mentionInput: false, type: 'ARRAY'},
            type: 'parameterValueResolved',
            value: '${a.b}',
        });

        expect(Array.isArray(nextState.multiSelectValue)).toBe(true);
    });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/properties/hooks/tests/propertyValueReducerModes.test.ts`
Expected: FAIL. TypeScript reports unknown `pillValueSet`/`valueCleared` and extra context keys; `mentionInput` is still
in state.

- [ ] **Step 3: Implement**

In `propertyValueReducer.ts`:

1. Import `getPropertyInputMode` from `@/pages/platform/workflow-editor/components/properties/propertyInputMode`.
   `propertyInputMode.ts` imports `MENTION_INPUT_PROPERTY_CONTROL_TYPES` from this file. ES modules resolve the cycle
   because both sides are only read at call time, not at module evaluation. If Vitest reports
   `Cannot access … before initialization`, move the two control-type arrays into a new
   `P/hooks/propertyControlTypes.ts` and import them from there in both files and in `useProperty.ts`.
2. Delete `mentionInput` from `PropertyValueStateI` and from `getInitialPropertyValueState`'s return. Compute
   `isMentionCapable` as:
   ```ts
    const initialValue = parameterValue !== undefined ? parameterValue : defaultValue;

    const isMentionCapable =
        getPropertyInputMode({
            controlType,
            formulaMode: controlType === 'FORMULA_MODE',
            hasControl,
            isFromAi: false,
            value: initialValue,
        }).renderer === 'mentions';
   ```
3. Extend `ParameterValueContextI` with `formulaMode: boolean` and `mentionInput: boolean`, keeping keys sorted.
4. In the action union, delete the `mentionInputModeChanged` and `inputTypeSwitched` members and add
   `| {type: 'pillValueSet'; value: string}` and `| {type: 'valueCleared'}`.
5. In `parameterValueResolved`:
   - Destructure `formulaMode` and `mentionInput` from `context`.
   - Replace the empty-value guard `if (state.mentionInput) {` with `if (mentionInput) {`.
   - After the `=` branch, compute:
     ```ts
            const nextUsesMentions =
                getPropertyInputMode({controlType, formulaMode, isFromAi: false, value}).renderer === 'mentions';
     ```
   - Replace `state.mentionInput && state.mentionInputSyncedValue !== value && typeof value === 'string'` with
     `(mentionInput || nextUsesMentions) && state.mentionInputSyncedValue !== value && typeof value === 'string'`.
   - Replace both `!state.mentionInput &&` guards (the INPUT and JSON_SCHEMA_BUILDER branches) with `!nextUsesMentions &&`.
   - Change `if (isNumericalInput && value !== null)` to `if (isNumericalInput && value !== null && !nextUsesMentions)`.
   - Change the MULTI_SELECT line to
     `nextState.multiSelectValue = Array.isArray(value) ? (value as string[]) : EMPTY_MULTI_SELECT_VALUE;`.
6. Delete the `mentionInputModeChanged` and `inputTypeSwitched` cases and add:
   ```ts
        case 'pillValueSet': {
            return {
                ...state,
                mentionInputSyncedValue: action.value,
                mentionInputValue: action.value,
                propertyParameterValue: action.value,
            };
        }

        case 'valueCleared': {
            return {
                ...state,
                inputValue: '',
                mentionInputSyncedValue: undefined,
                mentionInputValue: '',
                multiSelectValue: EMPTY_MULTI_SELECT_VALUE,
                propertyParameterValue: '',
                selectValue: '',
            };
        }
   ```

`useProperty.ts` stops compiling until Task 5. Do **not** commit this task separately: Task 5 completes it and commits
both. Run only the reducer tests here.

- [ ] **Step 4: Run the test and confirm it passes**

Run: the same command as Step 2. Expected: PASS.

---

### Task 5: `useProperty` + `Property` — uncontrolled Text/Formula

This replaces the uncontrolled `mentionInput` state machine with the derived mode, the Formula switch handler, pill
insertion into native fields, and the `$`/`=` keyboard handover. It finishes Task 4.

**Files:**
- Modify: `P/hooks/useProperty.ts`
- Modify: `P/Property.tsx` (uncontrolled branches ≈265-313 and ≈928-1093)
- Modify: `P/components/property-input/PropertyInput.tsx` (add an `onKeyDown` passthrough, which the component already
  forwards via `...props`; check that `onKeyDown` reaches `<Input>`)
- Test: `P/hooks/tests/uncontrolledFormulaMode.test.tsx`
- Update tests: `P/hooks/tests/inputTypeSwitchFormulaMode.test.tsx`, `inputTypeSwitchFromAi.test.tsx`,
  `nonStringExpressionMode.test.ts`, `mentionInputValueSync.test.ts`, `usePropertyValueState.test.ts`,
  `P/tests/Property.test.tsx`

**Interfaces:**
- Consumes:
  - Task 1: `getPropertyInputMode`, `toFormulaValue`, `fromFormulaValue`, `isSingleDataPill`, `shouldIncludeInMetadata`.
  - Task 3: editor props `singlePill`, `focusRequest`, `onSinglePillAbandoned`.
  - Task 4: actions `pillValueSet`, `valueCleared`; context `formulaMode` and `mentionInput`.
- Produces, as new or changed fields on the `useProperty` return:
  - `inputMode: PropertyInputModeI`
  - `mentionInput: boolean`, now derived: `!control && inputMode.renderer === 'mentions'`
  - `isFormulaMode: boolean`, now derived: `inputMode.mode === 'formula'`
  - `setIsFormulaMode`: sets the formula state
  - `handleFormulaSwitch: () => void`, which replaces `handleInputTypeSwitchButtonClick`
  - `handleNativeKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void`
  - `insertPillValue: (mentionId: string) => void`
  - `handleSinglePillAbandoned: () => void`
  - `editorFocusRequest: {initialInput?: string; token: number} | undefined`
  - `showFormulaSwitch: boolean`, which replaces `showInputTypeSwitchButton`
- Removed: `handleInputTypeSwitchButtonClick`, `showInputTypeSwitchButton`, `setMentionInput`.

- [ ] **Step 1: Write the failing test**

Create `P/hooks/tests/uncontrolledFormulaMode.test.tsx`, using the harness in `inputTypeSwitchFormulaMode.test.tsx`
(same imports, `wrapper`, and `beforeEach` store setup):

```tsx
const countProperty = {controlType: 'INTEGER', expressionEnabled: true, name: 'count', type: 'INTEGER'} as PropertyAllType;

const renderCount = (parameterValue?: unknown) =>
    renderHook(() => useProperty({parameterValue, path: 'parameters.count', property: countProperty}), {wrapper});

describe('uncontrolled Text/Formula', () => {
    beforeEach(() => {
        (saveProperty as unknown as Mock).mockReset();
    });

    it('renders a constant natively', () => {
        const {result} = renderCount(5);

        expect(result.current.inputMode).toMatchObject({mode: 'text', renderer: 'native'});
        expect(result.current.mentionInput).toBe(false);
    });

    it('renders a lone pill in the one-pill editor', () => {
        const {result} = renderCount('${trigger_1.count}');

        expect(result.current.inputMode).toMatchObject({renderer: 'mentions', singlePill: true});
    });

    it('converts a constant into a formula and saves it', () => {
        const {result} = renderCount(5);

        act(() => result.current.handleFormulaSwitch());

        expect(result.current.isFormulaMode).toBe(true);
        expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({includeInMetadata: true, value: '=5'}));
    });

    it('converts a formula literal back into a constant', () => {
        const {result} = renderCount('=7');

        act(() => result.current.handleFormulaSwitch());

        expect(result.current.isFormulaMode).toBe(false);
        expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({value: 7}));
    });

    it('clears a formula that cannot convert back', () => {
        const {result} = renderCount('=concat(a, b)');

        act(() => result.current.handleFormulaSwitch());

        expect(result.current.isFormulaMode).toBe(false);
        expect(result.current.propertyParameterValue).toBe('');
        expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({value: null}));
    });

    it('replaces a constant with a pill and saves the pill', () => {
        const {result} = renderCount(5);

        act(() => result.current.insertPillValue('trigger_1.count'));

        expect(result.current.propertyParameterValue).toBe('${trigger_1.count}');
        expect(result.current.inputMode.singlePill).toBe(true);
        expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({value: '${trigger_1.count}'}));
    });

    it('goes back to the native control when the pill is deleted', () => {
        const {result} = renderCount('${trigger_1.count}');

        act(() => result.current.handleMentionInputValueChange(''));

        expect(result.current.inputMode.renderer).toBe('native');
        expect(result.current.propertyParameterValue).toBe('');
    });

    it('$ on an empty number field opens pill entry and requests editor focus with $', () => {
        const {result} = renderCount('');

        const preventDefault = vi.fn();

        act(() => result.current.handleNativeKeyDown({key: '$', preventDefault} as never));

        expect(preventDefault).toHaveBeenCalled();
        expect(result.current.inputMode).toMatchObject({renderer: 'mentions', singlePill: true});
        expect(result.current.editorFocusRequest?.initialInput).toBe('$');
    });

    it('= on an empty number field enters formula mode', () => {
        const {result} = renderCount('');

        act(() => result.current.handleNativeKeyDown({key: '=', preventDefault: vi.fn()} as never));

        expect(result.current.isFormulaMode).toBe(true);
        expect(result.current.editorFocusRequest).toBeDefined();
    });

    it('ignores $ and = when the field already holds a constant', () => {
        const {result} = renderCount(5);

        const preventDefault = vi.fn();

        act(() => result.current.handleNativeKeyDown({key: '$', preventDefault} as never));

        expect(preventDefault).not.toHaveBeenCalled();
        expect(result.current.inputMode.renderer).toBe('native');
    });

    it('abandoning pill entry returns to the native control without saving', () => {
        const {result} = renderCount('');

        act(() => result.current.handleNativeKeyDown({key: '$', preventDefault: vi.fn()} as never));
        act(() => result.current.handleSinglePillAbandoned());

        expect(result.current.inputMode.renderer).toBe('native');
        expect(saveProperty).not.toHaveBeenCalled();
    });

    it('shows the Formula switch on a STRING property', () => {
        const {result} = renderHook(
            () =>
                useProperty({
                    path: 'parameters.uri',
                    property: {controlType: 'TEXT', expressionEnabled: true, name: 'uri', type: 'STRING'} as PropertyAllType,
                }),
            {wrapper}
        );

        expect(result.current.showFormulaSwitch).toBe(true);
    });

    it('never treats a fromAi value as formula', () => {
        const {result} = renderCount("=fromAi('count', 'INTEGER', {'required': false})");

        expect(result.current.isFormulaMode).toBe(false);
        expect(result.current.showFormulaSwitch).toBe(false);
    });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/properties/hooks/tests/uncontrolledFormulaMode.test.tsx`
Expected: FAIL, because `inputMode`, `handleFormulaSwitch`, `insertPillValue`, etc. are undefined.

- [ ] **Step 3: Rework the state in `useProperty.ts`**

1. **Imports.**
   - Add: `import {PropertyInputModeI, fromFormulaValue, getPropertyInputMode, isSingleDataPill, shouldIncludeInMetadata, toFormulaValue} from '@/pages/platform/workflow-editor/components/properties/propertyInputMode';`
   - Add `KeyboardEvent` to the `react` import.
2. **Return type.**
   - Remove `handleInputTypeSwitchButtonClick` and `showInputTypeSwitchButton`.
   - Add, sorted: `editorFocusRequest`, `handleFormulaSwitch`, `handleNativeKeyDown`, `handleSinglePillAbandoned`,
     `inputMode`, `insertPillValue`, `showFormulaSwitch`.
3. **State.**
   - Replace `const [isFormulaMode, setIsFormulaModeInternal] = useState(property.controlType === 'FORMULA_MODE');` with:
     ```ts
    const [editorFocusRequest, setEditorFocusRequest] = useState<{initialInput?: string; token: number} | undefined>();
    const [formulaModeState, setFormulaModeState] = useState(() => {
        if (property.controlType === 'FORMULA_MODE') {
            return true;
        }

        return typeof parameterValue === 'string' && parameterValue.startsWith('=') && !parameterValue.startsWith('=fromAi(');
    });
    const [pillEntry, setPillEntry] = useState(false);
     ```
     Keep all `useState` calls together.
   - Delete the `showInputTypeSwitchButton` `useState` (lines 178-180) and the effect that sets it (≈1614-1641).
   - Delete `const {…, mentionInput, …} = valueState;` → drop `mentionInput` from that destructure.
4. **Derived mode.** Place it right after `isFromAi` (≈line 446), and move the `isNumericalInput`, `parameterValueContext`
   and `parameterValueContextRef` blocks (≈389-400) below it:
   ```ts
    const inputMode: PropertyInputModeI = useMemo(
        () =>
            getPropertyInputMode({
                controlType,
                formulaMode: formulaModeState,
                hasControl: !!control,
                isFromAi,
                pillEntry,
                value: propertyParameterValue,
            }),
        [control, controlType, formulaModeState, isFromAi, pillEntry, propertyParameterValue]
    );

    const mentionInput = !control && inputMode.renderer === 'mentions';
    const isFormulaMode = inputMode.mode === 'formula';

    const showFormulaSwitch =
        expressionEnabled !== false &&
        !isFromAi &&
        controlType !== 'FORMULA_MODE' &&
        controlType !== 'NULL' &&
        controlType !== 'CODE_EDITOR' &&
        controlType !== 'FILE_ENTRY' &&
        type !== 'DYNAMIC_PROPERTIES';
   ```
   `parameterValueContext` becomes
   `useMemo<ParameterValueContextI>(() => ({controlType, formulaMode: formulaModeState, isNumericalInput, mentionInput, type}), [controlType, formulaModeState, isNumericalInput, mentionInput, type])`.
   `setInputValue`, `setMentionInputValue` and `resolveParameterValue` only read `parameterValueContextRef.current` at
   call time, so moving the context below them is safe.
5. **`setIsFormulaMode`.** Keep it as the public setter, now over `formulaModeState`. Replace the wrapper (≈1277-1286):
   ```ts
    const setIsFormulaMode: Dispatch<SetStateAction<boolean>> = useCallback(
        (value) => {
            if (property.controlType === 'FORMULA_MODE') {
                return;
            }

            setFormulaModeState(value);
        },
        [property.controlType]
    );
   ```
   In `handleFromAiClick`, replace `setIsFormulaModeInternal(true);` with `setFormulaModeState(true);`, and in its deps
   replace `setIsFormulaModeInternal` with `setFormulaModeState`.
6. **Remove the `mentionInput` machinery.**
   - Delete `setMentionInput` (≈410-412).
   - Delete the whole "set default mentionInput state" effect (≈1288-1341).
   - In the initial-load effect, delete the `setMentionInput(true); setIsFormulaMode(true);` pair inside
     `valueFromDefinition.startsWith('=')` (≈1385-1389), and in the `else if (isExpressionValue)` branch keep only
     `setMentionInputValue(propertyParameterValue.substring(1));`.
   - In the path-change effect, delete the `setMentionInput(true); setIsFormulaMode(true);` block (≈1469-1473) and drop
     `setIsFormulaMode`/`setMentionInput` from its deps.
   - The value alone now decides formula mode for `=` values (`getPropertyInputMode` rule 2).
7. **Remove `=` detection from `handleInputChange`.** Delete the `if (isNumericalInput && value && value.startsWith('=') && expressionEnabled) { … return; }`
   block (≈789-811). `handleNativeKeyDown` replaces it.
8. **Replace `handleInputTypeSwitchButtonClick`** (≈938-1046, the whole function) with the handlers below. Keep them
   together, after `handleMentionInputValueChange`:
   `propertyParameterValue` lags the user: a typed number sits in `inputValue` until the debounced save echoes back,
   and a mentions field's live text is `mentionInputValue` with the `=` stripped. The handlers therefore read the live
   value:
   ```ts
    const liveValue = useMemo(() => {
        if (mentionInput) {
            return isFormulaMode && mentionInputValue ? `=${mentionInputValue}` : mentionInputValue;
        }

        return isValidControlType && inputValue !== '' ? inputValue : propertyParameterValue;
    }, [inputValue, isFormulaMode, isValidControlType, mentionInput, mentionInputValue, propertyParameterValue]);
   ```
   Place `liveValue` with the other derived values, after `isValidControlType` and `inputMode`. `handleFormulaSwitch` converts `liveValue`, and `handleNativeKeyDown` checks `liveValue` for emptiness.

   ```ts
    const requestEditorFocus = useCallback((initialInput?: string) => {
        setEditorFocusRequest({initialInput, token: Date.now()});
    }, []);

    const saveResolvedValue = useCallback(
        (value: unknown) => {
            if (
                !path ||
                !workflow.id ||
                !(updateWorkflowNodeParameterMutation || updateClusterElementParameterMutation)
            ) {
                return;
            }

            saveProperty({
                includeInMetadata: shouldIncludeInMetadata(value, custom),
                path,
                type,
                updateClusterElementParameterMutation,
                updateWorkflowNodeParameterMutation,
                value,
                workflowId: workflow.id,
            });
        },
        [custom, path, type, updateClusterElementParameterMutation, updateWorkflowNodeParameterMutation, workflow.id]
    );

    const handleFormulaSwitch = useCallback(() => {
        const toFormula = !isFormulaMode;

        const convertedValue = toFormula
            ? toFormulaValue(liveValue, type)
            : fromFormulaValue(liveValue, type);

        setIsFormulaMode(toFormula);
        setPillEntry(false);

        if (convertedValue === undefined) {
            dispatchValueAction({type: 'valueCleared'});
        } else {
            dispatchValueAction({
                context: {...parameterValueContextRef.current, formulaMode: toFormula, mentionInput: toFormula},
                type: 'parameterValueResolved',
                value: convertedValue,
            });
        }

        saveResolvedValue(convertedValue === undefined ? null : convertedValue);

        const nextRenderer = getPropertyInputMode({
            controlType,
            formulaMode: toFormula,
            isFromAi: false,
            value: convertedValue ?? '',
        }).renderer;

        if (nextRenderer === 'mentions') {
            requestEditorFocus();
        } else {
            requestAnimationFrame(() => inputRef.current?.focus());
        }
    }, [
        controlType,
        isFormulaMode,
        liveValue,
        requestEditorFocus,
        saveResolvedValue,
        setIsFormulaMode,
        type,
    ]);

    const insertPillValue = useCallback(
        (mentionId: string) => {
            const pillValue = `\${${mentionId}}`;

            setPillEntry(false);

            dispatchValueAction({type: 'pillValueSet', value: pillValue});

            saveResolvedValue(pillValue);

            requestEditorFocus();
        },
        [requestEditorFocus, saveResolvedValue]
    );

    const handleNativeKeyDown = useCallback(
        (event: KeyboardEvent<HTMLInputElement>) => {
            const isEmpty = liveValue === '' || liveValue == null;

            if (!isEmpty || expressionEnabled === false || (event.key !== '$' && event.key !== '=')) {
                return;
            }

            event.preventDefault();

            if (event.key === '=') {
                setIsFormulaMode(true);

                requestEditorFocus();

                return;
            }

            setPillEntry(true);

            requestEditorFocus('$');
        },
        [expressionEnabled, liveValue, requestEditorFocus, setIsFormulaMode]
    );

    const handleSinglePillAbandoned = useCallback(() => {
        setPillEntry(false);

        dispatchValueAction({type: 'valueCleared'});

        requestAnimationFrame(() => inputRef.current?.focus());
    }, []);
   ```
   `insertPillValue` does not need a separate controlled-mode branch: Task 6 routes controlled fields through
   `field.onChange` instead.
9. **Single-pill transitions in `handleMentionInputValueChange`.** At the top of the callback, after
   `setMentionInputValue(...)`, add:
   ```ts
            if (inputMode.singlePill) {
                if (value === '') {
                    setPillEntry(false);

                    dispatchValueAction({type: 'valueCleared'});

                    return;
                }

                if (isSingleDataPill(value)) {
                    setPillEntry(false);

                    dispatchValueAction({type: 'pillValueSet', value});

                    return;
                }
            }
   ```
   Add `inputMode.singlePill` to its deps. Partial text such as `$ab` while the suggestion is open is neither empty nor a
   pill, so it is ignored and the editor stays mounted.
10. **`handleSelectChange`.** It uses `mentionInputValue.includes('${')` to decide numeric parsing, which is still valid.
    Leave it.
11. **Return object.**
    - Remove `handleInputTypeSwitchButtonClick` and `showInputTypeSwitchButton`.
    - Add, sorted: `editorFocusRequest`, `handleFormulaSwitch`, `handleNativeKeyDown`, `handleSinglePillAbandoned`,
      `inputMode`, `insertPillValue`, `showFormulaSwitch`.
    - `isFormulaMode` and `mentionInput` now return the derived values.

- [ ] **Step 4: Rework the uncontrolled branches in `Property.tsx`**

1. Destructure the new hook fields in place of `handleInputTypeSwitchButtonClick`/`showInputTypeSwitchButton`.
2. Uncontrolled mentions branch (≈265):
   - `handleInputTypeSwitchButtonClick={handleFormulaSwitch}`
   - `showInputTypeSwitchButton={showFormulaSwitch}`
   - add `focusRequest={editorFocusRequest}`, `onSinglePillAbandoned={handleSinglePillAbandoned}`,
     `singlePill={inputMode.singlePill}`
   - `isFormulaMode={isFormulaMode}` stays
   - In the `PropertyCopilotButton` props, `disabled={!!options?.length && !isFormulaMode && !mentionInput}` stays, and
     `dynamic={mentionInput}` becomes `dynamic={mentionInput && !isFormulaMode}` (spec §1, "Other readers").
3. Uncontrolled native branches:
   - `PropertyInput` ≈930, `PropertyJsonSchemaBuilder` ≈991, `PropertyComboBox` ≈1012, BOOLEAN `PropertySelect` ≈1044,
     `PropertyMultiSelect` ≈1078: replace `handleInputTypeSwitchButtonClick={handleInputTypeSwitchButtonClick}` (or the
     arrow) with `handleInputTypeSwitchButtonClick={handleFormulaSwitch}` and
     `showInputTypeSwitchButton={showInputTypeSwitchButton}` with `showInputTypeSwitchButton={showFormulaSwitch}`.
   - On the uncontrolled `PropertyInput` only, add `onKeyDown={handleNativeKeyDown}`.
   - The header switch at ≈359-364 (`showInputTypeSwitchButton && <PropertyInputTypeSwitch handleClick={handleInputTypeSwitchButtonClick} mentionInput={mentionInput} />`)
     becomes `showFormulaSwitch && !control && <PropertyInputTypeSwitch handleClick={handleFormulaSwitch} mentionInput={isFormulaMode} />`.
     The controlled header switch right below it starts with `!showInputTypeSwitchButton && control && …`; replace
     `!showInputTypeSwitchButton &&` with nothing, leaving `control && isToolsClusterElement && expressionEnabled !== false`.
     Task 7 renames the component.
4. `PropertyInput.tsx`: confirm `onKeyDown` reaches the `<Input>` element through `{...props}`. Open the file and check
   `props` is spread onto `Input`. If it isn't, add `onKeyDown` explicitly.

- [ ] **Step 5: Run the new test and confirm it passes**

Run: the same command as Step 2. Expected: PASS.

- [ ] **Step 6: Update the old tests to the new contract**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/properties`

For each failure, update it according to what it asserted:

| File | Old assertion | New assertion |
|---|---|---|
| `hooks/tests/inputTypeSwitchFormulaMode.test.tsx` | `handleInputTypeSwitchButtonClick` toggles `mentionInput` | Rewrite each case against `handleFormulaSwitch` for STRING: the field stays in `mentionInput` in both modes; `isFormulaMode` flips; `=concat('a','b')` → Text clears it (not a literal). Delete "does not carry formula mode back over" and "stays in the editor once clearing the constant value succeeds"; `uncontrolledFormulaMode.test.tsx` now covers the constant→editor path via pills. |
| `hooks/tests/inputTypeSwitchFromAi.test.tsx` | switching Dynamic on a fromAi field reveals the expression | The switch is hidden while fromAi (`showFormulaSwitch === false`). Keep the `handleFromAiClick(false)` case ("Customize"): it must now leave `isFormulaMode === true`. |
| `hooks/tests/nonStringExpressionMode.test.ts` | `=` typed in `handleInputChange` switches to the editor | Move to `handleNativeKeyDown({key: '='})`. |
| `hooks/tests/mentionInputValueSync.test.ts`, `usePropertyValueState.test.ts` | `result.current.mentionInput` from reducer state | Same expectations still hold via the derived value; add `formulaMode: false, mentionInput: <bool>` to any hand-built `ParameterValueContextI`. |
| `tests/Property.test.tsx` | mocked hook returns `mentionInput: true` | Add `inputMode: {legacyMixed: false, mode: 'text', renderer: 'mentions', singlePill: false}`, `isFormulaMode: false`, `showFormulaSwitch: false`, `handleFormulaSwitch: vi.fn()` to the mock (sorted). |

Rerun until the directory passes.

- [ ] **Step 7: Typecheck and lint**

Run: `cd client && NODE24; npx tsc --project tsconfig.json --noEmit && npx eslint src/pages/platform/workflow-editor --max-warnings=0`
Expected: no errors. Remaining `handleInputTypeSwitchButtonClick` props in `PropertyInput`, `PropertySelect` etc. are
fine: they are the child components' own prop names, which Task 7 renames.

- [ ] **Step 8: Commit Tasks 4 and 5 together**

```bash
git commit -m "NNNN client - Derive a property's Text/Formula mode and renderer from its value" -- client/src/pages/platform/workflow-editor/components/properties
```

---

### Task 6: Native fields accept pill clicks and drops

**Files:**
- Create: `P/hooks/usePillTarget.ts`
- Modify: `P/Property.tsx`. Wrap the native branch (`{!mentionInput && (<>…</>)}`) in a `<div>` that carries the pill
  target handlers.
- Modify: `P/components/property-input/PropertyInput.tsx:67,175`. Remove `setPillTarget(null)`.
- Test: `P/tests/PropertyNativePillTarget.test.tsx`

**Interfaces:**
- Consumes: store `setPillTarget`/`clearPillTarget` (Task 2); `insertPillValue`, `isFromAi`, `expressionEnabled`
  (Task 5).
- Produces: `usePillTarget({acceptsPill, insertPill}: {acceptsPill: () => boolean; insertPill: (mentionId: string) => void}): {onDragOver: DragEventHandler; onDrop: DragEventHandler; onFocusCapture: FocusEventHandler; ref: RefObject<HTMLDivElement | null>}`

- [ ] **Step 1: Write the failing test**

Create `P/tests/PropertyNativePillTarget.test.tsx`. Use the `ControlledExpressionMode.test.tsx` imports and
`TooltipProvider` + `WorkflowEditorProvider` wrapping, but render **uncontrolled** with a current node:

```tsx
vi.mock('@/pages/platform/workflow-editor/utils/saveProperty', () => ({default: vi.fn()}));

const countProperty = {controlType: 'INTEGER', expressionEnabled: true, label: 'Count', name: 'count', type: 'INTEGER'} as PropertyAllType;

const booleanProperty = {
    controlType: 'SELECT',
    expressionEnabled: true,
    label: 'Enabled',
    name: 'enabled',
    type: 'BOOLEAN',
} as PropertyAllType;

const renderUncontrolled = (property: PropertyAllType, parameterValue?: unknown) =>
    render(
        <TooltipProvider>
            <WorkflowEditorProvider value={workflowEditorProviderTestValue as never}>
                <Property parameterValue={parameterValue} path={`parameters.${property.name}`} property={property} />
            </WorkflowEditorProvider>
        </TooltipProvider>
    );

describe('native pill target', () => {
    beforeEach(() => {
        (saveProperty as unknown as Mock).mockReset();

        useWorkflowDataStore.setState({
            workflow: {id: 'wf-native-pill', nodeNames: []},
        } as unknown as Partial<ReturnType<typeof useWorkflowDataStore.getState>>);

        useWorkflowNodeDetailsPanelStore.setState({
            currentNode: {name: 'node_1', parameters: {}, workflowNodeName: 'node_1'},
            pillTarget: null,
        } as unknown as Partial<ReturnType<typeof useWorkflowNodeDetailsPanelStore.getState>>);
    });

    it('focusing a number input registers it, and a pill replaces its constant', async () => {
        const {container} = renderUncontrolled(countProperty, 5);

        fireEvent.focus(container.querySelector('input')!);

        const pillTarget = useWorkflowNodeDetailsPanelStore.getState().pillTarget;

        expect(pillTarget).not.toBeNull();

        act(() => pillTarget!.insertPill('trigger_1.count'));

        await waitFor(() => expect(container.querySelector('.ProseMirror')).not.toBeNull());

        expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({value: '${trigger_1.count}'}));
    });

    it('focusing a boolean select registers it instead of the previously focused editor', () => {
        const {container} = renderUncontrolled(booleanProperty, true);

        useWorkflowNodeDetailsPanelStore.setState({
            pillTarget: {acceptsPill: () => true, insertPill: vi.fn(), owner: 'stale-editor'},
        });

        fireEvent.focus(container.querySelector('button')!);

        expect(useWorkflowNodeDetailsPanelStore.getState().pillTarget?.owner).not.toBe('stale-editor');
    });

    it('dropping a pill onto a native field replaces its value', async () => {
        const {container} = renderUncontrolled(countProperty, 5);

        const dataTransfer = {
            getData: () => JSON.stringify({mentionId: 'trigger_1.count'}),
            types: ['application/bytechef-datapill'],
        };

        fireEvent.drop(container.querySelector('input')!, {dataTransfer});

        await waitFor(() =>
            expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({value: '${trigger_1.count}'}))
        );
    });

    it('a fromAi field does not accept pills', () => {
        const {container} = renderUncontrolled(countProperty, "=fromAi('count', 'INTEGER', {'required': false})");

        container.querySelector('[aria-label="count property"]')?.dispatchEvent(new FocusEvent('focusin', {bubbles: true}));

        const pillTarget = useWorkflowNodeDetailsPanelStore.getState().pillTarget;

        expect(pillTarget === null || pillTarget.acceptsPill() === false).toBe(true);
    });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/properties/tests/PropertyNativePillTarget.test.tsx`
Expected: FAIL. The pill target is still null after focusing the input, because `PropertyInput` clears it.

- [ ] **Step 3: Implement**

Create `P/hooks/usePillTarget.ts`:

```ts
import {PillTargetI} from '@/pages/platform/workflow-editor/components/datapills/pillTarget';
import useWorkflowNodeDetailsPanelStore from '@/pages/platform/workflow-editor/stores/useWorkflowNodeDetailsPanelStore';
import {DataPillDragPayloadType} from '@/shared/types';
import {DragEvent, useCallback, useEffect, useRef} from 'react';
import {useShallow} from 'zustand/react/shallow';

interface UsePillTargetPropsI {
    acceptsPill: () => boolean;
    insertPill: (mentionId: string) => void;
}

const DATA_PILL_MIME_TYPE = 'application/bytechef-datapill';

/**
 * Makes a native property control a data pill target: any focus inside the wrapper registers it, so the pill
 * panel inserts here rather than into whichever editor was focused before, and a dropped pill replaces the value.
 */
export default function usePillTarget({acceptsPill, insertPill}: UsePillTargetPropsI) {
    const acceptsPillRef = useRef(acceptsPill);
    const insertPillRef = useRef(insertPill);
    const ownerRef = useRef<HTMLDivElement | null>(null);

    acceptsPillRef.current = acceptsPill;
    insertPillRef.current = insertPill;

    const {clearPillTarget, setPillTarget} = useWorkflowNodeDetailsPanelStore(
        useShallow((state) => ({
            clearPillTarget: state.clearPillTarget,
            setPillTarget: state.setPillTarget,
        }))
    );

    const onFocusCapture = useCallback(() => {
        const pillTarget: PillTargetI = {
            acceptsPill: () => acceptsPillRef.current(),
            insertPill: (mentionId) => insertPillRef.current(mentionId),
            owner: ownerRef.current,
        };

        setPillTarget(pillTarget);
    }, [setPillTarget]);

    const onDragOver = useCallback((event: DragEvent<HTMLDivElement>) => {
        if (event.dataTransfer.types.includes(DATA_PILL_MIME_TYPE) && acceptsPillRef.current()) {
            event.preventDefault();

            event.dataTransfer.dropEffect = 'copy';
        }
    }, []);

    const onDrop = useCallback((event: DragEvent<HTMLDivElement>) => {
        const rawPayload = event.dataTransfer.getData(DATA_PILL_MIME_TYPE);

        if (!rawPayload || !acceptsPillRef.current()) {
            return;
        }

        event.preventDefault();

        try {
            const payload = JSON.parse(rawPayload) as DataPillDragPayloadType;

            if (payload?.mentionId) {
                insertPillRef.current(payload.mentionId);
            }
        } catch {
            // A malformed payload is not a data pill; the drop is simply ignored.
            return;
        }
    }, []);

    useEffect(() => {
        const owner = ownerRef.current;

        return () => clearPillTarget(owner);
    }, [clearPillTarget]);

    return {onDragOver, onDrop, onFocusCapture, ref: ownerRef};
}
```

The `catch` holds a `return`, which satisfies Checkstyle-style "no empty block" lint. ESLint's `no-empty` also passes
because of the comment and statement.

In `Property.tsx`:
- Call the hook after `useProperty` and before the early `return`s:
  ```tsx
    const nativePillTarget = usePillTarget({
        acceptsPill: () => expressionEnabled !== false && !isFromAi,
        insertPill: insertPillValue,
    });
  ```
  It must sit next to the other hooks (`useRef`, `useCallback`), not after `if (hidden && !control) return`.
- Wrap the native branch. `{!mentionInput && (<>…</>)}` becomes:
  ```tsx
            {!mentionInput && (
                <div
                    className="contents"
                    onDragOver={nativePillTarget.onDragOver}
                    onDrop={nativePillTarget.onDrop}
                    onFocusCapture={nativePillTarget.onFocusCapture}
                    ref={nativePillTarget.ref}
                >
                    …existing children unchanged…
                </div>
            )}
  ```
  `contents` keeps the layout identical, because the wrapper generates no box.

In `PropertyInput.tsx`: delete the `setPillTarget` selector (line 67) and the `setPillTarget(null);` call in `onFocus`
(line 175). The wrapper's capture handler now registers the field. Leaving that call in would immediately overwrite the
registration.

Controlled fields: `insertPillValue` writes through the uncontrolled save path. For controlled forms, pass a different
`insertPill`:
```tsx
        insertPill: control ? (mentionId) => controlledPillInsertRef.current?.(mentionId) : insertPillValue,
```
Declare `const controlledPillInsertRef = useRef<((mentionId: string) => void) | null>(null);` with the other refs. In each
controlled native `Controller` render (TEXT/number ≈517, SELECT ≈708, BOOLEAN ≈762, MULTI_SELECT ≈896), set
`controlledPillInsertRef.current = (mentionId) => fieldOnChange(`\${${mentionId}}`);`. Use `onChange` where the render
destructures that name instead.

- [ ] **Step 4: Run the test and confirm it passes**

Run: the same command as Step 2. Expected: PASS.

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "NNNN client - Let native property controls take data pill clicks and drops" -- client/src/pages/platform/workflow-editor/components/properties
```

---

### Task 7: The Formula switch — rename, visibility, surfaces

**Files:**
- Rename: `P/components/PropertyInputTypeSwitch.tsx` → `P/components/PropertyFormulaSwitch.tsx`
- Create: `P/FormulaEnabledContext.tsx`
- Modify consumers:
  - `P/components/property-input/PropertyInput.tsx`
  - `P/components/PropertySelect.tsx`
  - `P/components/PropertyComboBox.tsx`
  - `P/components/PropertyMultiSelect.tsx`
  - `P/components/property-json-schema-builder/PropertyJsonSchemaBuilder.tsx`
  - `P/components/property-mentions-input/PropertyMentionsInput.tsx`
  - `P/Property.tsx`
  - `P/graph/GraphTransitionPopover.tsx`
  - `P/hooks/useProperty.ts` (visibility reads the context)
  - `client/src/ee/pages/automation/ai-hub/context/AiHubConnectorToolPropertiesPopover.tsx`
- Test: `P/components/PropertyFormulaSwitch.test.tsx`; update `P/graph/GraphTransitionPopover.test.tsx` and any test that
  looks up the switch by the name "Dynamic"

**Interfaces:**
- Produces:
  - `PropertyFormulaSwitch({formulaMode, handleClick}: {formulaMode: boolean; handleClick: () => void})`
  - `FormulaEnabledProvider` and `useFormulaEnabledContext(): boolean | undefined`. `undefined` means no override.

- [ ] **Step 1: Write the failing test**

Create `P/components/PropertyFormulaSwitch.test.tsx`:

```tsx
import {TooltipProvider} from '@/components/ui/tooltip';
import {fireEvent, render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import PropertyFormulaSwitch from './PropertyFormulaSwitch';

describe('PropertyFormulaSwitch', () => {
    it('is labelled Formula and reflects the mode', () => {
        render(
            <TooltipProvider>
                <PropertyFormulaSwitch formulaMode handleClick={vi.fn()} />
            </TooltipProvider>
        );

        expect(screen.getByRole('switch', {name: 'Formula'})).toBeChecked();
    });

    it('calls handleClick when toggled', () => {
        const handleClick = vi.fn();

        render(
            <TooltipProvider>
                <PropertyFormulaSwitch formulaMode={false} handleClick={handleClick} />
            </TooltipProvider>
        );

        fireEvent.click(screen.getByRole('switch', {name: 'Formula'}));

        expect(handleClick).toHaveBeenCalledTimes(1);
    });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/properties/components/PropertyFormulaSwitch.test.tsx`
Expected: FAIL with `Failed to resolve import "./PropertyFormulaSwitch"`.

- [ ] **Step 3: Implement**

`git mv` the switch file, then set its content:

```tsx
import Switch from '@/components/Switch/Switch';
import {Tooltip, TooltipContent, TooltipPortal, TooltipTrigger} from '@/components/ui/tooltip';

interface PropertyFormulaSwitchProps {
    formulaMode: boolean;
    handleClick: () => void;
}

const PropertyFormulaSwitch = ({formulaMode, handleClick}: PropertyFormulaSwitchProps) => (
    <Tooltip>
        <TooltipTrigger asChild>
            <span className="inline-flex">
                <Switch
                    checked={formulaMode}
                    label="Formula"
                    onCheckedChange={(checked) => {
                        if (checked !== formulaMode) {
                            handleClick();
                        }
                    }}
                    variant="small"
                />
            </span>
        </TooltipTrigger>

        <TooltipPortal>
            <TooltipContent>{formulaMode ? 'Switch to text' : 'Switch to formula'}</TooltipContent>
        </TooltipPortal>
    </Tooltip>
);

export default PropertyFormulaSwitch;
```

Check that `Switch` with `label` gives the switch the accessible name "Formula". If the test's `getByRole` fails on the
name, look at `client/src/components/Switch/Switch.tsx` for how `label` is wired (e.g. `aria-label` or `<label htmlFor>`)
and query accordingly. Don't change the `Switch` component.

Create `P/FormulaEnabledContext.tsx`:

```tsx
import {createContext, useContext} from 'react';

/**
 * Overrides whether properties under it offer the Formula switch. Undefined (no provider) means the default:
 * every expression-enabled property in the workflow editor, and only tool forms among controlled surfaces.
 * AI Hub connector tools set it to false because their server path never evaluates `=` values.
 *
 * Context rather than prop for the reason CanvasPropertyEditorContext gives: Property nests through several
 * intermediaries, and a prop any one of them forgets to forward silently restores the switch underneath it.
 */
const FormulaEnabledContext = createContext<boolean | undefined>(undefined);

export const FormulaEnabledProvider = FormulaEnabledContext.Provider;

export const useFormulaEnabledContext = () => useContext(FormulaEnabledContext);
```

Consumers. In each, replace the import and the element:

- `PropertyInput`, `PropertySelect`, `PropertyComboBox`, `PropertyMultiSelect`, `PropertyJsonSchemaBuilder`:
  `<PropertyInputTypeSwitch handleClick={handleInputTypeSwitchButtonClick} mentionInput={false} />` becomes
  `<PropertyFormulaSwitch formulaMode={false} handleClick={handleInputTypeSwitchButtonClick} />`. In `PropertyInput`,
  `mentionInput={mentionInput}` becomes `formulaMode={false}`, and the now-unused `mentionInput` prop is deleted from
  `PropertyInputProps` and its destructure. These components render only in Text mode, so the switch is off.
- `PropertyMentionsInput`: `<PropertyInputTypeSwitch handleClick={handleInputTypeSwitchButtonClick} mentionInput />`
  becomes `<PropertyFormulaSwitch formulaMode={!!isFormulaMode} handleClick={handleInputTypeSwitchButtonClick} />`.
- `Property.tsx` header (≈360-376): both switches become
  `<PropertyFormulaSwitch formulaMode={isFormulaMode} handleClick={handleFormulaSwitch} />` for uncontrolled, and
  `<PropertyFormulaSwitch formulaMode={controlledDynamicMode} handleClick={() => handleControlledModeSwitch(!controlledDynamicMode)} />`
  for controlled. Task 8 replaces `controlledDynamicMode` with `isFormulaMode`; until then, controlled mode still runs on
  it.
- `GraphTransitionPopover.tsx` (≈249-258): `<PropertyFormulaSwitch formulaMode={expressionMode} handleClick={() => setExpressionModeOverride(!expressionMode)} />`.
  Update the comment above it to say "The same Formula switch every other property field carries…".
- `ee/pages/automation/ai-hub/context/AiHubConnectorToolPropertiesPopover.tsx`: wrap `<Properties … toolsMode />` in
  `<FormulaEnabledProvider value={false}>…</FormulaEnabledProvider>`.

Visibility in `useProperty.ts`:
- Read `const formulaEnabledOverride = useFormulaEnabledContext();` with the other custom hooks.
- Extend `showFormulaSwitch`:
  ```ts
    const formulaEnabled = formulaEnabledOverride ?? (control ? isToolsClusterElement : true);

    const showFormulaSwitch =
        formulaEnabled &&
        expressionEnabled !== false &&
        !isFromAi &&
        controlType !== 'FORMULA_MODE' &&
        controlType !== 'NULL' &&
        controlType !== 'CODE_EDITOR' &&
        controlType !== 'FILE_ENTRY' &&
        type !== 'DYNAMIC_PROPERTIES';
  ```

Tests to update:
- `grep -rln "PropertyInputTypeSwitch\|'Dynamic'\|name: /dynamic/i\|Switch to dynamic\|Switch to constant" client/src`.
- Change role queries from `'Dynamic'` to `'Formula'` and tooltip text to the new strings. `GraphTransitionPopover.test.tsx`
  is the main one.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor src/ee/pages/automation/ai-hub`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "NNNN client - Replace the Dynamic switch with a Formula switch" -- client/src/pages/platform/workflow-editor client/src/ee/pages/automation/ai-hub
```

---

### Task 8: Controlled forms — one formula state, conversions, STRING switch

**Files:**
- Rename: `P/getInitialControlledDynamicMode.ts` → `P/getInitialFormulaMode.ts`; its test file → `P/getInitialFormulaMode.test.ts`
- Modify: `P/hooks/useProperty.ts`
  - `controlledDynamicMode` state (≈183-191) and `handleControlledModeSwitch` (≈682-718)
  - the reset effect (≈1724-1729)
  - the return
- Modify: `P/Property.tsx`, controlled branches ≈394-450 (dynamic), ≈513-701 (input), ≈703-792 (select/boolean),
  ≈794-894 (textarea), ≈896-926 (multi-select), and the builder header ≈366-376
- Modify: `P/getControlledToolFieldState.ts`. Drop `showControlledSwitch`; `Property` now uses `showFormulaSwitch`.
- Test: `P/tests/ControlledFormulaSwitch.test.tsx`; update `P/tests/ControlledExpressionMode.test.tsx`,
  `P/tests/getControlledToolFieldState.test.ts`, `P/hooks/tests/controlledModeState.test.ts`

**Interfaces:**
- Consumes: `toFormulaValue`, `fromFormulaValue` (Task 1); `showFormulaSwitch`, `setIsFormulaMode`, `isFormulaMode`
  (Tasks 5 and 7).
- Produces:
  - `getInitialFormulaMode({control, controlPath, controlType, parameterValue, propertyName, propertyType}): boolean`
  - `handleControlledFormulaSwitch(fieldValue: unknown, fieldOnChange: (value: unknown) => void): void`
  - `controlledDynamicMode` is removed; controlled branches read `isFormulaMode`.

- [ ] **Step 1: Write the failing test**

Create `P/tests/ControlledFormulaSwitch.test.tsx`. Copy `Wrapper`, `settle`, the imports and the `beforeEach` from
`ControlledExpressionMode.test.tsx`, but parameterise the property:

```tsx
const Wrapper = ({property, value}: {property: PropertyAllType; value: unknown}) => {
    const form = useForm({defaultValues: {[property.name!]: value}});

    formValues = form.watch();

    return (
        <TooltipProvider>
            <WorkflowEditorProvider value={workflowEditorProviderTestValue as never}>
                <Property control={form.control as never} controlPath="" formState={form.formState} property={property} toolsMode />
            </WorkflowEditorProvider>
        </TooltipProvider>
    );
};

const countProperty = {controlType: 'INTEGER', expressionEnabled: true, label: 'Count', name: 'count', type: 'INTEGER'} as PropertyAllType;

const uriProperty = {controlType: 'TEXT', expressionEnabled: true, label: 'URI', name: 'uri', type: 'STRING'} as PropertyAllType;

describe('controlled Formula switch', () => {
    it('converts a constant number into a formula', async () => {
        const {container} = render(<Wrapper property={countProperty} value={5} />);

        await settle();

        fireEvent.click(screen.getByRole('switch', {name: 'Formula'}));

        await waitFor(() => expect(formValues.count).toBe('=5'));

        expect(container.querySelector('.ProseMirror')).not.toBeNull();
    });

    it('converts a literal formula back into a number', async () => {
        render(<Wrapper property={countProperty} value="=7" />);

        await settle();

        fireEvent.click(screen.getByRole('switch', {name: 'Formula'}));

        await waitFor(() => expect(formValues.count).toBe(7));
    });

    it('clears a formula that cannot convert back', async () => {
        render(<Wrapper property={countProperty} value="=concat(a, b)" />);

        await settle();

        fireEvent.click(screen.getByRole('switch', {name: 'Formula'}));

        await waitFor(() => expect(formValues.count).toBe(''));
    });

    it('shows the Formula switch on a STRING tool field and converts the constant', async () => {
        render(<Wrapper property={uriProperty} value="https://example.com" />);

        await settle();

        fireEvent.click(screen.getByRole('switch', {name: 'Formula'}));

        await waitFor(() => expect(formValues.uri).toBe("='https://example.com'"));
    });

    it('typing = into an empty number field enters formula mode', async () => {
        const {container} = render(<Wrapper property={countProperty} value="" />);

        await settle();

        fireEvent.keyDown(container.querySelector('input')!, {key: '='});

        await waitFor(() => expect(container.querySelector('.ProseMirror')).not.toBeNull());
    });

    it('keeps a non-string fromAi value out of formula mode and hides the switch', async () => {
        render(<Wrapper property={countProperty} value="=fromAi('count', 'INTEGER', {'required': false})" />);

        await settle();

        expect(screen.queryByRole('switch', {name: 'Formula'})).toBeNull();
        expect(screen.getByText('Automatically defined by the model')).toBeInTheDocument();
    });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor/components/properties/tests/ControlledFormulaSwitch.test.tsx`
Expected: FAIL. Switching clears to `''` or `'='` instead of converting, the STRING tool field has no switch, and typing
`=` into a `type="number"` input does nothing.

- [ ] **Step 3: Implement `getInitialFormulaMode`**

`git mv P/getInitialControlledDynamicMode.ts P/getInitialFormulaMode.ts` (and its `.test.ts`). Rewrite:

```ts
import {Control, FieldValues} from 'react-hook-form';

interface GetInitialFormulaModePropsI {
    control?: Control<FieldValues, FieldValues>;
    controlPath: string;
    controlType?: string;
    parameterValue?: unknown;
    propertyName?: string;
    propertyType?: string;
}

const readControlledValue = (control: Control<FieldValues, FieldValues>, fieldPath: string) =>
    fieldPath
        .split('.')
        .reduce<unknown>((currentObject, key) => (currentObject as Record<string, unknown>)?.[key], control._formValues);

/**
 * Whether a property opens in Formula mode. A `=fromAi(` value on a STRING field is never formula: STRING renders
 * fromAi through its own toggle. On other types the fromAi value is kept in the mentions editor branch so its
 * read-only "Automatically defined by the model" rendering is unchanged (spec Decision 6).
 */
export default function getInitialFormulaMode({
    control,
    controlPath,
    controlType,
    parameterValue,
    propertyName,
    propertyType,
}: GetInitialFormulaModePropsI): boolean {
    if (controlType === 'FORMULA_MODE') {
        return true;
    }

    let value = parameterValue;

    if (control?._formValues && propertyName) {
        value = readControlledValue(control, controlPath ? `${controlPath}.${propertyName}` : propertyName);
    }

    if (typeof value !== 'string' || !value.startsWith('=')) {
        return false;
    }

    return !(propertyType === 'STRING' && value.startsWith('=fromAi('));
}
```

Rewrite `getInitialFormulaMode.test.ts` to cover:
- no control, `=1` parameter value → true;
- control with `=1` → true;
- control with a constant → false;
- STRING `=fromAi(` → false;
- INTEGER `=fromAi(` → true;
- `FORMULA_MODE` → true.

Use the same `control` stub shape the old test used (`{_formValues: {...}} as never`).

- [ ] **Step 4: Implement controlled switching in `useProperty.ts`**

1. **Initial formula state.**
   - Replace the `formulaModeState` initializer from Task 5 with
     `useState(() => getInitialFormulaMode({control, controlPath, controlType: property.controlType, parameterValue, propertyName: property.name?.replace(/\s/g, '_'), propertyType: property.type}))`.
   - Delete the `controlledDynamicMode` `useState` and the `getInitialControlledDynamicMode` import.
   - For uncontrolled mode, `getInitialFormulaMode` reads `parameterValue`, and STRING `=fromAi(` stays out, as Task 5
     required. The uncontrolled INTEGER `=fromAi(` case is covered by `isFromAi` winning in `getPropertyInputMode`.
2. **Replace `handleControlledModeSwitch`** with `handleControlledFormulaSwitch`:
   ```ts
    const handleControlledFormulaSwitch = useCallback(
        (fieldValue: unknown, fieldOnChange: (value: unknown) => void) => {
            const toFormula = !isFormulaMode;
            const wasFromAi = controlledFromAi === true;

            const convertedValue = toFormula ? toFormulaValue(fieldValue, type) : fromFormulaValue(fieldValue, type);

            setIsFormulaMode(toFormula);
            setControlledFromAi(undefined);

            fieldOnChange(convertedValue ?? (toFormula ? '=' : ''));

            if (
                wasFromAi &&
                path &&
                workflow.id &&
                (updateWorkflowNodeParameterMutation || updateClusterElementParameterMutation)
            ) {
                saveProperty({
                    fromAi: false,
                    includeInMetadata: custom,
                    path,
                    type,
                    updateClusterElementParameterMutation,
                    updateWorkflowNodeParameterMutation,
                    value: convertedValue ?? null,
                    workflowId: workflow.id,
                });
            }
        },
        [
            controlledFromAi,
            custom,
            isFormulaMode,
            path,
            setIsFormulaMode,
            type,
            updateClusterElementParameterMutation,
            updateWorkflowNodeParameterMutation,
            workflow.id,
        ]
    );
   ```
   Empty formula is stored as `'='` in controlled mode, as before; `reconstructControlledExpressionValue` and
   `resolveExpressionValue` depend on it.
3. **Builder headers** (OBJECT_BUILDER/ARRAY_BUILDER) have no `field` in scope.
   - Keep `controlledDynamicOnChangeRef` and `resetOnModeChangeRef`, but rename the ref to `controlledFormulaOnChangeRef`.
   - Replace the reset effect (≈1724) with:
     ```ts
    useEffect(() => {
        if (isFormulaMode && resetOnModeChangeRef.current && controlledFormulaOnChangeRef.current) {
            resetOnModeChangeRef.current = false;

            controlledFormulaOnChangeRef.current('=');
        }
    }, [isFormulaMode, resetOnModeChangeRef]);
     ```
   - Add `handleControlledBuilderFormulaSwitch = () => { resetOnModeChangeRef.current = true; setIsFormulaMode(!isFormulaMode); }`
     for the header. Objects and arrays never convert (`toFormulaValue` returns `undefined`), so `'='`/`''` is the correct
     behaviour.
4. **Return.**
   - Remove `controlledDynamicMode`, `controlledDynamicOnChangeRef` and `handleControlledModeSwitch`.
   - Add `controlledFormulaOnChangeRef`, `handleControlledBuilderFormulaSwitch` and `handleControlledFormulaSwitch`
     (sorted). Update the return type.

- [ ] **Step 5: Implement the controlled branches in `Property.tsx`**

Do a global replace in the file: `controlledDynamicMode` → `isFormulaMode`. That covers the ≈394 branch condition and
every `!controlledDynamicMode &&` guard. Then:

- **≈394 formula branch.**
  - `controlledDynamicOnChangeRef.current = field.onChange;` → `controlledFormulaOnChangeRef.current = field.onChange;`
  - Its `handleInputTypeSwitchButtonClick` becomes
    `() => handleControlledFormulaSwitch(field.value, fieldOnChange)`.
  - `showInputTypeSwitchButton` becomes `showInputTypeSwitchButton={showFormulaSwitch && !isFieldFromAi}`.
  - `setIsFormulaMode={() => {}}` becomes
    `setIsFormulaMode={(formulaMode) => { if (!formulaMode) { setIsFormulaMode(false); fieldOnChange(''); } }}`, so
    Backspace on an empty formula exits, as in uncontrolled mode.
- **≈513 TEXT/number controller.**
  - From `getControlledToolFieldState`, destructure without `showControlledSwitch`.
  - `handleInputTypeSwitchButtonClick` becomes `showFormulaSwitch ? () => handleControlledFormulaSwitch(field.value, fieldOnChange) : undefined`.
  - `showInputTypeSwitchButton={showFormulaSwitch}`.
  - Add
    `onKeyDown={(event) => { if (event.key === '=' && showFormulaSwitch && (field.value === '' || field.value == null)) { event.preventDefault(); setIsFormulaMode(true); fieldOnChange('='); } }}`.
    This covers INTEGER's `type="number"` swallowing `=`.
  - The existing `showFromAi && (isExpressionMode || isFieldFromAi)` STRING path is unchanged: typing `=` in a STRING tool
    field still goes through `resolveExpressionValue`. Its `PropertyMentionsInput` additionally gets
    `showInputTypeSwitchButton={showFormulaSwitch && !isFieldFromAi}` and
    `handleInputTypeSwitchButtonClick={() => handleControlledFormulaSwitch(field.value, fieldOnChange)}`.
- **≈708 SELECT, ≈762 BOOLEAN, ≈896 MULTI_SELECT.**
  - `handleInputTypeSwitchButtonClick` becomes `() => handleControlledFormulaSwitch(fieldValue, onChange)`. For
    MULTI_SELECT, which destructures `{onChange, value}`, use `value`.
  - `showInputTypeSwitchButton` becomes `showFormulaSwitch`.
- **≈794 TEXT_AREA.**
  - Add `handleInputTypeSwitchButtonClick={() => handleControlledFormulaSwitch(field.value, fieldOnChange)}` and
    `showInputTypeSwitchButton={showFormulaSwitch && !isFieldFromAi}` to its `PropertyMentionsInput`.
  - Add the same two props to `PropertyTextArea` only if it accepts them. Check its props: if it has no switch slot,
    leave the text area without one. It is STRING-only, and typing `=` already enters formula mode there.
- **≈366 builder header.** `handleClick={handleControlledBuilderFormulaSwitch}`, `formulaMode={isFormulaMode}`, and the
  condition becomes `!showFormulaSwitch ? null : …` in place of `control && isToolsClusterElement && expressionEnabled !== false`.

In `getControlledToolFieldState.ts`, delete `showControlledSwitch` from the interface, the computation and the return.
Update `getControlledToolFieldState.test.ts` accordingly.

- [ ] **Step 6: Run the tests and confirm they pass**

Run: the same command as Step 2. Expected: PASS.

Run: `cd client && NODE24; npx vitest run src/pages/platform/workflow-editor src/shared/components/component-config src/pages/platform/mcp-servers src/ee`
Expected: PASS. Update `ControlledExpressionMode.test.tsx` and `controlledModeState.test.ts` wherever they assert that
switching clears the value; the new contract is conversion. Every other case there (caret handover, reopen not stealing
focus) must still pass unchanged.

- [ ] **Step 7: Commit**

```bash
git commit -m "NNNN client - Use the Text/Formula switch and value conversion in controlled property forms" -- client/src/pages/platform/workflow-editor/components/properties
```

---

### Task 9: Cleanup, full check, and verification in the running editor

**Files:**
- Delete leftovers found by grep. Update `.agents/` docs if any mention the Dynamic switch.

- [ ] **Step 1: Sweep for leftovers**

Run:
```bash
cd client && grep -rn "PropertyInputTypeSwitch\|controlledDynamicMode\|getInitialControlledDynamicMode\|setMentionInput\|mentionInputModeChanged\|inputTypeSwitched\|focusedInput\|canInsertMentionForProperty\|showInputTypeSwitchButton={showInputTypeSwitchButton}" src
cd .. && grep -rln "Dynamic switch\|\"Dynamic\" switch" .agents CLAUDE.md docs/content 2>/dev/null
```
Expected: no hits in `src`. Rename any doc hits to "Formula switch", or reword them.

- [ ] **Step 2: Full client check**

Run, with Bash tool `timeout: 600000`: `cd client && NODE24; npm run format && npm run check`
Expected: prettier, eslint (0 warnings), tsc and vitest all pass. Fix `sort-keys` and import-order findings by hand.

- [ ] **Step 3: Manual verification in the running app**

- Start the stack per CLAUDE.md: infra via `docker compose -f server/docker-compose.dev.infra.yml up -d`, the server via
  `./gradlew -p server/apps/server-app bootRun`, and the client via `cd client && npm run dev`.
- Log in as `admin@localhost.com` / `admin`.
- Open a workflow with an HTTP Client task and a trigger that produces output.
- Check, and note each result for the final report:
  1. A number property, empty: type `$`. The one-pill editor appears with the cursor in it and **the pill popup open**.
     Press Escape and click away: the field returns to an empty number input and nothing is saved.
  2. The same field holding `5`: click a pill in the Data Pill Panel. It replaces `5`. Click another pill: it replaces
     the first. Delete the pill: the number input returns, empty and focused.
  3. A select property: focus it, then click a pill. It lands in the select, not in a previously focused editor. Drag a
     pill onto another select: it replaces its value.
  4. A boolean property: the Formula switch converts `true` → `=true` → `true`.
  5. A STRING property: the Formula switch is visible. Text `abc` → Formula `='abc'` → Text `abc`.
  6. An AI agent's TOOLS element, uncontrolled in the editor: `fromAi` still shows "Automatically defined by the model",
     with no Formula switch while it's on. "Customize" opens Formula mode with the expression.
  7. An MCP server tool form (Automation → MCP Servers → a component tool's properties): a number field converts through
     the switch; typing `=` into an empty number enters formula.
  8. An AI Hub connector tool popover: no Formula switch at all.
  9. A graph transition popover: the switch reads "Formula" and still toggles expression mode.

- [ ] **Step 4: Commit any cleanup**

```bash
git commit -m "NNNN client - Remove leftovers of the Dynamic switch" -- client .agents
```
Skip this if Step 1 found nothing.

- [ ] **Step 5: Hand back to the user**

Report:
- which verification items passed;
- anything that didn't, with details;
- the worktree path and branch.

Do not push, open a PR, or merge into `0_732` without the user's explicit go-ahead.
