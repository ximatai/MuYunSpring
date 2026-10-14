import { describe, expect, it } from 'vitest';
import { createLanguageContext } from '@/web-core';
import { readonlyReferenceDisplayItems } from '@/platform-components/readonlyReferenceDisplay';
import { resolveRecordDetailDisplayValue } from '@/platform-components/recordDetailFieldModel';
import { resolveRecordQueryListDisplayValue } from '@/platform-components/recordQueryListColumnModel';
import { recycleBinRestoreUnavailableReason } from '@/platform-components/recycleBinState';
import type { RecordFormFieldState } from '@/platform-components/recordFormFieldModel';
import type { RecycleBinItem } from '@/web-contracts';

describe('standard English presentation facts', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  it('localizes standard boolean/status values and keeps explicit business labels', () => {
    expect(
      resolveRecordQueryListDisplayValue(
        { enabled: false },
        { key: 'enabled', title: 'Status', type: 'enabledStatus' },
        {},
        { language },
      ),
    ).toBe('Disabled');
    expect(
      resolveRecordQueryListDisplayValue(
        { approved: true },
        { key: 'approved', title: 'Approved' },
        {},
        { language },
      ),
    ).toBe('Yes');
    expect(
      resolveRecordQueryListDisplayValue(
        { enabled: true },
        {
          key: 'enabled',
          title: 'Status',
          type: 'booleanStatus',
          booleanStatus: { trueLabel: '业务通过', falseLabel: '业务拒绝' },
        },
        {},
        { language },
      ),
    ).toBe('业务通过');
  });
  it('localizes unavailable-reference suffixes without changing IDs, titles or browse authorization', () => {
    const summary = [{ id: 'real-1', title: '原始业务名称', alias: 'real-code', unavailable: true }];
    const items = readonlyReferenceDisplayItems(
      { cardinality: 'MANY' },
      ['real-1', 'hidden-2'],
      summary,
      language,
    );
    expect(items).toEqual([
      { id: 'real-1', label: '原始业务名称 (real-code) (unavailable)', browseable: false },
      { id: 'hidden-2', label: 'hidden-2', browseable: false },
    ]);
    expect(summary[0]).toEqual({
      id: 'real-1',
      title: '原始业务名称',
      alias: 'real-code',
      unavailable: true,
    });
  });
  it('distinguishes post-save calculations from absent persisted values', () => {
    const field = { fieldName: 'amount', calculationPending: true } as RecordFormFieldState;
    expect(resolveRecordDetailDisplayValue(field, {}, { language })).toBe('Calculated after saving');
    expect(
      resolveRecordDetailDisplayValue(
        { ...field, calculationPending: false, controlType: 'enabledStatus' },
        { amount: false },
        { language, draft: true },
      ),
    ).toBe('Disabled');
  });
  it('uses proven lifecycle reasons and preserves an unrecognized producer reason', () => {
    expect(
      recycleBinRestoreUnavailableReason(
        {
          restorable: false,
          unavailableReason: 'resource lifecycle changed after deletion',
        } as RecycleBinItem<unknown>,
        language,
      ),
    ).toBe('Cannot restore: resource lifecycle changed after deletion');
    expect(
      recycleBinRestoreUnavailableReason(
        { restorable: false, unavailableReason: 'custom-source-reason' } as RecycleBinItem<unknown>,
        language,
      ),
    ).toBe('Cannot restore: custom-source-reason');
    expect(
      recycleBinRestoreUnavailableReason({ restorable: true } as RecycleBinItem<unknown>, language),
    ).toBeUndefined();
  });
});
