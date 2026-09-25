# Property input modes: Text and Formula (replacing "Dynamic")

Date: 2026-09-25
Status: Draft, awaiting review

## Problem

A workflow-editor property field has three states today, spread across three flags that drift apart:

| State | Flag | What renders |
|---|---|---|
| constant | `mentionInput = false` | the native control (input, select, date picker, …) |
| "Dynamic" | `mentionInput = true` (uncontrolled) / `controlledDynamicMode = true` (controlled) | `PropertyMentionsInput` |
| formula | `isFormulaMode = true`, only reachable from inside the mentions editor by typing `=` | `PropertyMentionsInput` in formula mode |

The "Dynamic" switch (`PropertyInputTypeSwitch`) means different things on different surfaces:

- **Uncontrolled non-string fields:** the switch swaps the native control for a mentions editor that only accepts a
  single `$` pill, since `handleKeyPress` blocks every other key.
- **STRING fields:** the switch never appears. STRING fields are always the mentions editor.
- **Controlled forms:** the switch appears only on TOOLS fields, and there it means formula mode (`isFormulaMode` is
  fixed on and values are stored as `=…`).

Data pills can't reach native controls. Clicking a pill does nothing after a native input is focused (it sets
`focusedInput = null`). Selects never set `focusedInput`, so a pill click after focusing a select lands in whichever
editor was focused before.

## Goal

Two modes, one switch labelled **Formula**:

- **Text (default)**
  - **STRING fields:** the mentions editor mixing free text and data pills, unchanged.
  - **Every other type:** the value is either a constant or exactly one data pill.
    - A constant renders the native control. Clicking or dragging a pill replaces it.
    - A pill renders the mentions editor limited to one pill. A second pill replaces the first, and the pill must be
      deleted before a constant can be entered.
    - Typing `$` opens the pill popup only on an empty field.
- **Formula:** the `=` expression editor.
  - Reached with the switch, or by typing `=` on an empty input that has no predefined options.

It must behave the same in uncontrolled mode (root properties and cluster elements in the workflow editor, including
TOOLS) and controlled mode (react-hook-form `control`: MCP server tools, workflow-as-tool, agent `ComponentConfigDialog`
and the other dialogs).

## Non-goals

- Changing `fromAi`. Its toggle, look, stored value (`=fromAi('name','TYPE',{…})`) and read-only behaviour stay exactly
  as they are.
- Server-side evaluation. The one gap found, in AI Hub connector tools, is tracked separately (see "Outstanding").
- Making data pills resolvable on controlled surfaces. Those surfaces have no upstream-node context and no pill panel
  (see "Controlled surfaces").

## Decisions

1. **The Formula switch appears on every property with `expressionEnabled !== false`**, STRING included. It is
   hidden:
   - while the field is `fromAi`;
   - for `FORMULA_MODE` (always formula), `NULL`, `CODE_EDITOR`, `DYNAMIC_PROPERTIES` and `FILE_ENTRY`;
   - wherever the surface passes `formulaEnabled={false}`.

   In controlled forms, `formulaEnabled` defaults to `isToolsClusterElement`. The switch therefore still appears only
   on tool forms (MCP tools, workflow-as-tool, agent `ComponentConfigDialog`), as it does today. The other controlled
   dialogs (connection, context-store and knowledge-base sources, data-sync wizard, OAuth2 step,
   `InputConfigurationList`) have never offered expressions, and nobody has checked that their server paths evaluate
   `=` values, so they keep constants only.
2. **Non-string pills reuse `PropertyMentionsInput`**, limited to one pill (`singlePill`), not a new component.
3. **`$` and `=` shortcuts apply only to native controls you can type into** (NUMBER and INTEGER), and only on an empty
   field.
   - They are handled in `onKeyDown`, because INTEGER is `type="number"` and the browser discards both characters in
     `onChange`. Today's `=` shortcut silently fails for INTEGER for this reason.
   - Selects, BOOLEAN, DATE/DATE_TIME/TIME and MULTI_SELECT take pills only by click or drag. Their only way into
     Formula is the switch.
4. **Keystroke and focus survive the handover.** The swap from native input to editor re-inserts the typed `$` or `=`
   into the editor and focuses it once the editor is created, not after a timer.
5. **Toggling Formula keeps the value when nothing is lost, and clears it otherwise.**
   - Text → Formula: a lone pill or a constant is converted (`${a.b}` → `=${a.b}`, `5` → `=5`). Mixed text and pills
     (e.g. `hello ${a}`) is cleared rather than rewritten into a string-concatenation formula.
   - Formula → Text: a lone pill (`=${a.b}`) → `${a.b}`. A literal matching the type (`=5` for a number,
     `=true`/`=false` for BOOLEAN, `='x'` for a STRING) → the literal. Anything else → cleared.
6. **`fromAi` is never Formula mode.** A value starting with `=fromAi(` keeps today's rendering. Turning `fromAi` off
   returns the field to an empty Text mode. "Customize" (today's reveal of the expression for editing) now opens
   Formula mode with the expression.
7. **Legacy values are kept.** A non-string value that mixes text and pills, or holds two or more pills (e.g.
   `"${a} ms"`), opens in the mentions editor without the one-pill limit. Once edited into a conforming shape, the
   normal rules apply.
8. **AI Hub connector tools hide the Formula switch** (`formulaEnabled={false}`), because their server path doesn't
   evaluate `=` values.

## Design

### 1. Mode model: `properties/propertyInputMode.ts`

A pure module with no React or store dependencies:

```ts
interface PropertyInputModeI {
    legacyMixed: boolean;
    mode: 'formula' | 'text';
    renderer: 'mentions' | 'native';
}

getPropertyInputMode({controlType, formulaMode, hasOptions, isFromAi, type, value}): PropertyInputModeI
toFormulaValue(value): string | undefined          // undefined → clear
fromFormulaValue(value, type): unknown | undefined // undefined → clear
```

Rules, first match wins:

1. `isFromAi` → `text`, with today's `fromAi` rendering.
2. `formulaMode`, or a string value starting with `=` → `formula` + `mentions`.
3. STRING or a text-like control type (`MENTION_INPUT_PROPERTY_CONTROL_TYPES`) → `text` + `mentions`.
4. Non-string value that is exactly one `${…}` → `text` + `mentions` (one pill).
5. Non-string value with text around a pill, or two or more pills → `text` + `mentions`, `legacyMixed: true`.
6. Anything else (empty or constant) → `text` + `native`.

State:

- `formulaMode` is the only real state. It is set by the switch or by typing `=`, and initialized from the loaded
  value: `control._formValues` in controlled mode, the node parameters in uncontrolled mode.
- It replaces `mentionInput`, `controlledDynamicMode`, the switch-driven part of `isFormulaMode`, and
  `getInitialControlledDynamicMode.ts`.
- Rendering follows the value. Adding a pill switches the field to `mentions` and deleting it switches back to
  `native`, with no toggling code.

Other readers of the old flags:

- `PropertyCopilotButton`'s `dynamic` (`Property.tsx` ≈276) becomes `renderer === 'mentions' && mode === 'text'`.
- The copilot's formula mode becomes `mode === 'formula'`.
- `propertyValueReducer` drops `mentionInput` and its `inputTypeSwitched` / `mentionInputModeChanged` actions.

### 2. Rendering and switching (`Property.tsx`, `useProperty.ts`)

**Switch.**
- `PropertyInputTypeSwitch` becomes `PropertyFormulaSwitch`: label "Formula", checked when `mode === 'formula'`,
  tooltip "Switch to formula" / "Switch to text".
- It stays in the label-row slot the native controls and `PropertyMentionsInput` already expose, via
  `showInputTypeSwitchButton` / `handleInputTypeSwitchButtonClick`.
- The graph transition popover (`graph/GraphTransitionPopover.tsx`) uses the same switch and is renamed with it.

**Toggling.**
- On: value becomes `toFormulaValue(current) ?? ''`, `formulaMode` becomes true, and the editor is focused at the end.
- Off: value becomes `fromFormulaValue(current, type) ?? ''`, `formulaMode` becomes false, and whatever the new
  renderer shows is focused.
- Saving:
  - Uncontrolled: the existing `saveProperty`, with empty saved as `null`.
  - Controlled: `field.onChange`, with formula text passed through `reconstructControlledExpressionValue`.
- Backspace on an empty formula still exits Formula mode, via `FormulaMode.extension.ts`.

**Branches.**
- One mentions branch serves both controlled and uncontrolled mode through a small value adapter. It replaces today's
  three `PropertyMentionsInput` blocks (≈265 uncontrolled, ≈394 controlled dynamic, ≈556 controlled STRING tools).
- The native branches stay as they are, minus their `controlledDynamicMode` guards.

**One-pill editor.**
- New `singlePill` prop on `PropertyMentionsInput`, set for non-string `text` + `mentions` unless `legacyMixed`.
  - Empty field: only `$` is accepted.
  - With a pill: only Backspace and Delete are accepted.
  - Inserting a pill replaces the existing one (see §3).
- The mention count is taken from mention nodes in the document. Today's `mentionOccurences` counts
  `/property-mention/` in the value after serialization, when it has already become `${…}`, so it stays at 0.
- Deleting the pill empties the value, so the native control renders again and takes focus.

**Keyboard handover (NUMBER, INTEGER).**
- `PropertyInput` gets `onKeyDown`. On an empty field, `$` or `=` calls `preventDefault` and records
  `pendingEditorInput`.
- `$` makes the field render the one-pill editor. Its `onCreate` focuses the editor, inserts `$` so the suggestion
  popup opens, and registers the pill target.
- `=` sets `formulaMode` and the Formula editor focuses itself on create.
- This removes the 50 ms `setTimeout` handovers (`useProperty.ts` ≈797, ≈949, ≈987, ≈994).

**AI Hub.**
- `Properties` and `Property` accept `formulaEnabled` and pass it down to nested properties. It defaults to true in
  uncontrolled mode and to `isToolsClusterElement` in controlled mode (see Decision 1).
- `AiHubConnectorToolPropertiesPopover` passes `false`.

### 3. Pill target (pill clicks and drops into any field)

**Store** (`useWorkflowNodeDetailsPanelStore`): `focusedInput: Editor | null` and `setFocusedInput` become:

```ts
interface PillTargetI {
    acceptsPill: () => boolean;
    insertPill: (mentionId: string) => void;
}

pillTarget: PillTargetI | null;
setPillTarget: (pillTarget: PillTargetI | null) => void;
```

Today only `PropertyMentionsInput`, `useProperty`, `DataPill` and two tests
(`PropertyMentionsInputFormulaEntry.test.tsx`, `formulaModeEntryFocus.test.tsx`) use these.

**Who registers a target.**
- **Mentions editor:** registers on focus.
  - `insertPill` runs today's `insertContent` of a mention.
  - With `singlePill`, it replaces the document content with the new mention.
- **Native controls:** a `usePillTarget` hook in `Property` puts an `onFocusCapture` on a wrapper `div` around the
  native branch.
  - Any focus inside it registers the target: input, Radix select trigger, combobox, multi-select, date picker.
  - This covers every control without editing each component, and fixes the stale-target bug for selects.
  - `insertPill` writes `${mentionId}` through the same save path as a typed value. §1's rules then render the one-pill
    editor.
- **`acceptsPill()`** returns `expressionEnabled !== false && !isFromAi`.
  - It replaces `canInsertMentionForProperty` in `DataPill.tsx`, which looked up the value in
    `currentNode.parameters` by the editor's `path`. That lookup is wrong for controlled form paths.
  - In Formula mode, pills are inserted into the formula text, as today.

**Drag and drop.**
- The native wrapper handles `dragover`/`drop` for `application/bytechef-datapill` and calls `insertPill`.
- The mentions editor keeps its own `handleDrop`, which replaces the pill in `singlePill` mode.

**Lifetime.**
- A target stays registered after blur, because clicking the pill panel itself blurs the field.
- It is cleared on unmount, or replaced when another field registers.

**`DataPill`.** Click calls `pillTarget.insertPill(buildMentionId(...))` when `pillTarget?.acceptsPill()` is true. Drag
payloads are unchanged.

### Controlled surfaces

Checked server-side while designing this:

| Surface | What happens to stored params | Can a `${…}` pill resolve? | Is a formula evaluated? |
|---|---|---|---|
| MCP component tools, workflow-as-tool (automation + EE) | `AbstractToolFacade.resolveParameterValue` processes only strings containing `fromAi(`, against an empty `Map.of()` context | no | `fromAi` and constant formulas only |
| AI Hub connector tools (EE) | passed to `executeTool` unchanged; no Evaluator (`ClusterElementToolCallback.java`) | no | **no** |
| Agents (`ComponentConfigDialog`) | copied into the generated workflow and evaluated against the job context | yes, but the dialog has no pill panel | yes |

So on controlled surfaces, Text mode means constants only, and Formula works as in the editor. The mode model is the
same everywhere; the pill paths simply never fire where there is no pill panel.

## Testing

- **`propertyInputMode.test.ts`** (table-driven): every rule in §1 by type and control type; `fromAi` precedence;
  `legacyMixed` detection; `toFormulaValue`/`fromFormulaValue` in both directions, including the clear cases.
- **Property RTL tests, each in a controlled and an uncontrolled variant:**
  - switch round-trip with value conversion;
  - `$` from an empty NUMBER and INTEGER field: the editor is focused and the popup is open;
  - `=` from an empty NUMBER and INTEGER field: Formula mode is on and focused;
  - deleting a pill brings back the native control;
  - a `fromAi` value never shows Formula;
  - the switch appears on STRING;
  - the switch is hidden when `formulaEnabled={false}`.
- **Pill target:**
  - store unit test;
  - `DataPill` click and drag routed to a mock target;
  - focusing a number input then clicking a pill → the value becomes `${x}` and the one-pill editor renders;
  - dropping onto a select replaces its constant;
  - a second pill replaces the first;
  - `fromAi` rejects pills.
- **Existing tests to update:** `getInitialControlledDynamicMode.test.ts` (removed with the module), the two
  `focusedInput` tests, and `PropertyMentionsInput*` tests that assert the Dynamic label.
- **Manual check in the running editor:** focus handover and pill replacement on a component with number, select,
  boolean and date properties, and on an MCP server tool form.

## Outstanding

- **AI Hub connector tools evaluate neither `=` expressions nor `fromAi`** on the server
  (`server/ee/libs/ai/ai-hub/ai-hub-service/.../toolsearch/ClusterElementToolCallback.java`). The switch is hidden
  there by this change; the server fix is a separate issue.
