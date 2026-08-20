import { computed, ref } from 'vue';
import { describe, expect, it } from 'vitest';
import { useModulePageFormContributionRuntime } from '@/dynamic-page-runtime/composables/useModulePageFormContributionRuntime.ts';
import type { ModulePageFormContribution } from '@/dynamic-page-runtime/modulePageEnhancements.ts';

describe('module page form contribution runtime', () => {
  it('exposes only a frozen form snapshot and blocks save validity until the contribution recovers', () => {
    const contributions = ref<readonly ModulePageFormContribution[]>([
      {
        key: 'tenant-brand-mode',
        component: {},
        location: { surface: 'main', section: 'before-fields' },
      },
    ]);
    const draft = ref<Record<string, unknown>>({ title: '示例租户' });
    const formSessionKey = ref(1);
    const runtime = useModulePageFormContributionRuntime({
      contributions,
      mode: computed(() => 'edit' as const),
      draft,
      fields: ref(new Map()),
      formSessionKey,
      setField(fieldName, value) {
        draft.value = { ...draft.value, [fieldName]: value };
      },
    });

    const context = runtime.contextFor(contributions.value[0]);
    expect(Object.isFrozen(context.draft)).toBe(true);
    expect(context.draft).toEqual({ title: '示例租户' });
    expect(runtime.valid.value).toBe(true);

    context.reportValidity({ valid: false, errors: { brandMode: '请选择品牌模式' } });
    expect(runtime.valid.value).toBe(false);
    context.setField('brandMode', 'BRANDED');
    expect(draft.value.brandMode).toBe('BRANDED');

    context.reportValidity({ valid: true });
    expect(runtime.valid.value).toBe(true);
  });

  it('clears contribution diagnostics when the standard form session changes', () => {
    const contributions = ref<readonly ModulePageFormContribution[]>([
      {
        key: 'tenant-brand-mode',
        component: {},
        location: { surface: 'main', fieldName: 'brandMode', placement: 'after' },
      },
    ]);
    const formSessionKey = ref(1);
    const runtime = useModulePageFormContributionRuntime({
      contributions,
      mode: computed(() => 'create' as const),
      draft: ref({}),
      fields: ref(new Map()),
      formSessionKey,
      setField: () => undefined,
    });

    runtime.contextFor(contributions.value[0]).reportValidity({ valid: false });
    expect(runtime.valid.value).toBe(false);
    formSessionKey.value += 1;
    expect(runtime.valid.value).toBe(true);
  });
});
