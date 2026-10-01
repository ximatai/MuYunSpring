import { describe, expect, it } from 'vitest';
import {
  portableFieldPattern,
  recordFormInputConstraintError,
  recordFormRequiredError,
  type RecordFormFieldState,
} from '../../src/platform-components/recordFormFieldModel';

describe('portable field patterns', () => {
  it('uses normalized Java whitespace semantics for required and pattern validation together', () => {
    const field = {
      label: '编码',
      required: true,
      inputRequirements: {
        requiredOnInsert: true,
        requiredOnUpdate: true,
        textNormalization: 'TRIM_TO_NULL',
        maxLength: 3,
        validationRegex: '[A-Z]+',
      },
    } as RecordFormFieldState;
    for (const value of ['\u0000', '\u001c', '\u3000', '   ', null]) {
      expect(recordFormRequiredError(field, value)).toBe('请填写编码');
      expect(recordFormInputConstraintError(field, value)).toBeUndefined();
    }
    expect(recordFormRequiredError(field, '\u00a0')).toBeUndefined();
    expect(recordFormInputConstraintError(field, '\u00a0')).toContain('格式不符合要求');
    expect(recordFormRequiredError(field, ' ABC ')).toBeUndefined();
    expect(recordFormInputConstraintError(field, ' ABC ')).toBeUndefined();
    field.inputRequirements!.textNormalization = 'NONE';
    expect(recordFormRequiredError(field, '\u0000')).toBeUndefined();
    expect(recordFormInputConstraintError(field, '\u0000')).toContain('格式不符合要求');
    expect(recordFormRequiredError(field, '\u001c')).toBe('请填写编码');
  });

  it('validates normalized values without changing the draft or treating non-breaking spaces as Java whitespace', () => {
    const field = {
      label: '编码',
      inputRequirements: { maxLength: 3, validationRegex: '[A-Z]+', textNormalization: 'TRIM' },
    } as RecordFormFieldState;
    expect(recordFormInputConstraintError(field, ' ABC ')).toBeUndefined();
    expect(recordFormInputConstraintError(field, '\u00a0A')).toContain('格式不符合要求');
    field.inputRequirements!.textNormalization = 'TRIM_TO_NULL';
    expect(recordFormInputConstraintError(field, '\u3000')).toBeUndefined();
    expect(recordFormInputConstraintError(field, '\u00a0')).toContain('格式不符合要求');
    field.inputRequirements!.textNormalization = 'NONE';
    expect(recordFormInputConstraintError(field, 'A\n')).toContain('格式不符合要求');
  });
  it('supports flat ASCII identifier and fixed-width patterns', () => {
    expect(portableFieldPattern('[a-z][a-z0-9_]{0,62}')?.test('sales_order')).toBe(true);
    expect(portableFieldPattern('^[0-9]{11}$')?.test('13800138000')).toBe(true);
    expect(portableFieldPattern('[a-z]+')?.test('中文')).toBe(false);
  });
  it('leaves Java-specific, Unicode and ambiguous repetition to the server', () => {
    for (const pattern of ['(?i)abc', '[a-z&&[^b]]', '(a+)+', '[a-z]+[a-z]+', '[', '.', '\\w+', '[^a]']) {
      expect(portableFieldPattern(pattern)).toBeUndefined();
    }
  });
});
