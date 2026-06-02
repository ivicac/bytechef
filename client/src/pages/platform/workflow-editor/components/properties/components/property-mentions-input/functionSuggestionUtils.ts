import {EvaluatorFunctionDefinition, EvaluatorFunctionType} from '@/shared/middleware/graphql';
import {Editor} from '@tiptap/react';

const MAX_RESULTS = 50;
const MINIMUM_QUERY_LENGTH = 2;
const TRAILING_WORD = /[A-Za-z][A-Za-z0-9]*$/;

// SCREAMING_SNAKE enum value (e.g. "STRING", "INTEGER") -> readable label (e.g. "String", "Integer").
function formatFunctionType(type: EvaluatorFunctionType): string {
    return type.charAt(0) + type.slice(1).toLowerCase();
}

export function formatFunctionSignature(definition: EvaluatorFunctionDefinition): string {
    const parameters = definition.parameters
        .map((parameter) => `${parameter.name}: ${formatFunctionType(parameter.type)}`)
        .join(', ');

    return `(${parameters}): ${formatFunctionType(definition.returnType)}`;
}

export function filterFunctionDefinitions(
    definitions: EvaluatorFunctionDefinition[],
    query: string
): EvaluatorFunctionDefinition[] {
    const lowercaseQuery = query.toLowerCase();

    return definitions
        .filter(
            (definition) =>
                definition.name.toLowerCase().startsWith(lowercaseQuery) ||
                definition.title.toLowerCase().includes(lowercaseQuery)
        )
        .slice(0, MAX_RESULTS);
}

export function buildFunctionInsertion(name: string): {caretOffset: number; content: string} {
    return {caretOffset: name.length + 1, content: `${name}()`};
}

interface SuggestionMatchResultI {
    query: string;
    range: {from: number; to: number};
    text: string;
}

// Custom findSuggestionMatch: trigger on the trailing word before the caret (no explicit trigger char).
// nodeBefore is the contiguous text node ending at the caret, so its trailing-word length maps 1:1 to
// document positions even when a datapill node precedes it on the same line.
export function findFunctionSuggestionMatch({
    $position,
}: {
    $position: {nodeBefore: {isText: boolean; text?: string | null} | null; pos: number};
}): SuggestionMatchResultI | null {
    const nodeBefore = $position.nodeBefore;

    if (!nodeBefore || !nodeBefore.isText || !nodeBefore.text) {
        return null;
    }

    const match = nodeBefore.text.match(TRAILING_WORD);

    if (!match || match[0].length < MINIMUM_QUERY_LENGTH) {
        return null;
    }

    const word = match[0];

    // Don't compete with the `$` datapill suggestion: if the matched word is the tail of a `$`-prefixed
    // token (e.g. "$conc"), let the datapill source own it so both popups don't open at once.
    if (nodeBefore.text[nodeBefore.text.length - word.length - 1] === '$') {
        return null;
    }

    const to = $position.pos;
    const from = to - word.length;

    return {query: word, range: {from, to}, text: word};
}

export function isFormulaModeActive(editor: Pick<Editor, 'storage'>): boolean {
    return editor.storage?.FormulaMode?.isFormulaMode === true;
}

export interface EnclosingFunctionCallI {
    argIndex: number;
    name: string;
}

const IDENTIFIER_CHARACTER = /[A-Za-z0-9_]/;

export function findEnclosingFunctionCall(textBeforeCaret: string): EnclosingFunctionCallI | null {
    const stack: EnclosingFunctionCallI[] = [];

    let word = '';
    let quote: string | null = null;

    for (const character of textBeforeCaret) {
        if (quote) {
            if (character === quote) {
                quote = null;
            }

            continue;
        }

        if (character === '"' || character === "'") {
            quote = character;
            word = '';
        } else if (IDENTIFIER_CHARACTER.test(character)) {
            word += character;
        } else if (character === '(') {
            stack.push({argIndex: 0, name: word});
            word = '';
        } else if (character === ')') {
            stack.pop();
            word = '';
        } else if (character === ',') {
            if (stack.length > 0) {
                stack[stack.length - 1].argIndex += 1;
            }

            word = '';
        } else {
            word = '';
        }
    }

    for (let index = stack.length - 1; index >= 0; index--) {
        if (stack[index].name) {
            return {argIndex: stack[index].argIndex, name: stack[index].name};
        }
    }

    return null;
}

export interface FunctionSignaturePartsI {
    params: string[];
    returnType: string;
}

export function formatFunctionSignatureParts(definition: EvaluatorFunctionDefinition): FunctionSignaturePartsI {
    return {
        params: definition.parameters.map((parameter) => `${parameter.name}: ${formatFunctionType(parameter.type)}`),
        returnType: formatFunctionType(definition.returnType),
    };
}
