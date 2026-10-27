import { describe, expect, it } from 'vitest';
import { reactive } from 'vue';
import { formCreateDefaults } from '@/dynamic-page-runtime/formCreateDefaults';
import type { ResolvedViewDescriptor } from '@muyun/web-contracts';

describe('model form create defaults', () => {
  it('preserves explicit false, zero and null over model defaults and clones JSON per draft', () => {
    const view = reactive<ResolvedViewDescriptor>({
      viewCode: 'form',
      viewKind: 'FORM',
      fields: [],
      createDefaults: {
        enabled: true,
        count: 12,
        selection: 'default',
        tags: ['initial'],
        large: '9007199254740993',
      },
    });
    const first = formCreateDefaults(view, { enabled: false, count: 0, selection: null });
    expect(first).toEqual({
      enabled: false,
      count: 0,
      selection: null,
      tags: ['initial'],
      large: '9007199254740993',
    });
    (first.tags as string[]).push('changed');
    expect(formCreateDefaults(view).tags).toEqual(['initial']);
    expect(view.createDefaults?.tags).toEqual(['initial']);
  });
  it('accepts older descriptors with no defaults', () => {
    expect(formCreateDefaults(undefined, { tenantId: 'tenant' })).toEqual({ tenantId: 'tenant' });
  });
});
