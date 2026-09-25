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
