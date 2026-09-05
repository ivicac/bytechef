/**
 * Shared by `DataSyncWizard` and `DataSyncWizardFooter` from a third file rather than one importing straight
 * from the other. Both modules need `TOTAL_STEPS`; hosting it in the wizard component itself would make the
 * footer import from the wizard while the wizard imports the footer to render it — an import cycle. Benign
 * under ESM live bindings (the constant is only read at render time), but the original Data Stream wizard
 * deliberately avoids the same hazard by hosting its own step count in a third module, so this one does too.
 */
export const TOTAL_STEPS = 5;

export const STEP_LABELS = ['Trigger', 'Source', 'Destination', 'Mapping', 'Test'];
