import {FieldType} from '@/shared/constants';
import {describe, expect, it} from 'vitest';

import {toSubmittedValues} from '../useApprovalForm';

describe('toSubmittedValues', () => {
    it('submits a number input as a number', () => {
        const submittedValues = toSubmittedValues(
            [
                {fieldName: 'amount', fieldType: FieldType.NUMBER_INPUT},
                {fieldName: 'price', fieldType: FieldType.NUMBER_INPUT},
            ],
            {amount: '7', price: ' 7.5 '}
        );

        expect(submittedValues).toEqual({amount: 7, price: 7.5});
    });

    it('omits an empty number input and keeps an unparseable one for the server to reject', () => {
        const submittedValues = toSubmittedValues(
            [
                {fieldName: 'amount', fieldType: FieldType.NUMBER_INPUT},
                {fieldName: 'count', fieldType: FieldType.NUMBER_INPUT},
            ],
            {amount: '', count: 'seven'}
        );

        expect(submittedValues).toEqual({count: 'seven'});
    });

    it('leaves the values of other inputs untouched', () => {
        const submittedValues = toSubmittedValues(
            [
                {fieldName: 'reason', fieldType: FieldType.INPUT},
                {fieldName: 'amount', fieldType: FieldType.NUMBER_INPUT},
            ],
            {amount: 3, comment: 'ok', reason: '42'}
        );

        expect(submittedValues).toEqual({amount: 3, comment: 'ok', reason: '42'});
    });
});
