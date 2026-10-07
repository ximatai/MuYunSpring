import { expect, it } from 'vitest';
import { createRecordReferenceDisplay } from '@/dynamic-page-runtime/recordReferenceDisplay';
import type { RecordFormDraftAccess } from '@/dynamic-page-runtime/recordFormDraftAccess';

function fixture() {
  const record: Record<string, unknown> = { customer: 'private-id', quantity: 1 };
  const identity = {
    targetModuleAlias: 'customers',
    source: { kind: 'sourceField' as const, id: 'order.customer' },
    authorizationScope: 'scope-one',
  };
  let session = 1;
  const form = {
    editingRecord: record,
    referencePickerConfigs: { customer: { provider: { identity }, reloadKey: 'one' } },
  } as unknown as Pick<RecordFormDraftAccess, 'editingRecord' | 'referencePickerConfigs'>;
  return {
    record,
    identity,
    form,
    newSession: () => session++,
    display: createRecordReferenceDisplay(form, () => session),
  };
}

it('retains source-resolved names across unrelated edits, without copying candidates or patches', () => {
  const { record, display } = fixture();
  const candidate = {
    id: 'private-id',
    title: '客户甲',
    affectPatch: { price: 99 },
    projections: { private: 'secret' },
  };
  display.observe('customer', [candidate]);
  record.quantity = 4;
  candidate.title = 'later mutated';
  expect(display.current('customer')).toBe('客户甲');
  expect(record).toEqual({ customer: 'private-id', quantity: 4 });
  display.clear(['customer']);
  expect(display.current('customer')).toBeUndefined();
});

it.each(['selection', 'session', 'reload', 'authorization'] as const)(
  'invalidates observations after %s changes',
  (change) => {
    const { record, display, newSession, form, identity } = fixture();
    display.observe('customer', [{ id: 'private-id', title: '客户甲' }]);
    if (change === 'selection') record.customer = 'other';
    if (change === 'session') newSession();
    if (change === 'reload') form.referencePickerConfigs.customer!.reloadKey = 'two';
    if (change === 'authorization') identity.authorizationScope = 'scope-two';
    expect(display.current('customer')).toBeUndefined();
    record.customer = 'private-id';
    expect(display.current('customer')).toBeUndefined();
  },
);

it.each(
  [
    [],
    [{ id: 'other', title: '无关客户' }],
    [{ id: 'private-id', title: 'private-id' }],
    [{ id: 'private-id', title: ' ' }],
    [{ id: 'private-id', title: '失效客户', unavailable: true }],
    [{ id: 'private-id', title: '标识符', identifierFallback: true }],
    [{ id: 'private-id', title: '长'.repeat(2001) }],
  ].map((candidates) => ({ candidates })),
)('clears unresolved, stale or unbounded observations (%j)', ({ candidates }) => {
  const { display } = fixture();
  display.observe('customer', [{ id: 'private-id', title: '客户甲' }]);
  display.observe('customer', candidates);
  expect(display.current('customer')).toBeUndefined();
});

it('requires every selected reference to resolve and follows the current selection order', () => {
  const { record, display } = fixture();
  record.customer = ['private-id', 'second'];
  display.observe('customer', [{ id: 'private-id', title: '客户甲' }]);
  expect(display.current('customer')).toBeUndefined();
  display.observe('customer', [
    { id: 'second', title: '客户乙' },
    { id: 'private-id', title: '客户甲' },
  ]);
  expect(display.current('customer')).toBe('客户甲、客户乙');
});
