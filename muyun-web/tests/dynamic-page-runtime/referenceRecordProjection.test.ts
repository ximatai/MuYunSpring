import { describe, expect, it } from 'vitest';
import { applyReferenceRecordProjection } from '@/dynamic-page-runtime/referenceRecordProjection';
import type { RecordFormFieldDescriptor } from '@/platform-components';

const fields = new Map<string, RecordFormFieldDescriptor>([
  [
    'supplierId',
    {
      fieldRef: { fieldName: 'supplierId' },
      label: '供应商',
      valueType: 'STRING',
      reference: {
        targetModuleAlias: 'purchase.supplier',
        cardinality: 'ONE',
        displayProjections: [{ targetField: 'title', outputField: 'supplierTitle' }],
      },
    },
  ],
  [
    'supplierIds',
    {
      fieldRef: { fieldName: 'supplierIds' },
      label: '供应商集合',
      valueType: 'STRING',
      reference: {
        targetModuleAlias: 'purchase.supplier',
        cardinality: 'MANY',
        displayProjections: [{ targetField: 'title', outputField: 'supplierNames' }],
      },
    },
  ],
]);

describe('reference record projection', () => {
  it('projects only a saved matching ONE reference and retains the reference id', () => {
    const draft = { supplierId: 'supplier-1', supplierTitle: '旧供应商', supplierIds: ['supplier-1'] };

    expect(
      applyReferenceRecordProjection(draft, fields, {
        type: 'saved',
        targetModuleAlias: 'purchase.supplier',
        recordId: 'supplier-1',
        record: { id: 'supplier-1', title: '新供应商' },
      }),
    ).toEqual({ supplierId: 'supplier-1', supplierTitle: '新供应商', supplierIds: ['supplier-1'] });
  });

  it('leaves the draft untouched for non-matching, non-saved, and MANY-reference mutations', () => {
    const draft = { supplierId: 'supplier-1', supplierTitle: '旧供应商', supplierIds: ['supplier-1'] };
    const mutation = {
      targetModuleAlias: 'purchase.supplier',
      recordId: 'supplier-2',
      type: 'saved' as const,
      record: { id: 'supplier-2', title: '其他供应商' },
    };

    expect(applyReferenceRecordProjection(draft, fields, mutation)).toBe(draft);
    expect(applyReferenceRecordProjection(draft, fields, { ...mutation, type: 'deleted' })).toBe(draft);
  });
});
