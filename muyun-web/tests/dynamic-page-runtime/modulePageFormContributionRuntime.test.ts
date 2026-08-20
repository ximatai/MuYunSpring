import { computed, ref } from 'vue';
import { describe, expect, it } from 'vitest';
import { useModulePageFormContributionRuntime } from '@/dynamic-page-runtime/composables/useModulePageFormContributionRuntime.ts';
import type { ModulePageFormContribution } from '@/dynamic-page-runtime/modulePageEnhancements.ts';
import type { RecordFormFieldDescriptor } from '@/platform-components/recordFormFieldModel.ts';

describe('module page form contribution runtime', () => {
  it('exposes only a frozen form snapshot and blocks save validity until the contribution recovers', () => {
    const contributions = ref<readonly ModulePageFormContribution[]>([
      {
        key: 'tenant-brand-mode',
        component: {},
        location: { surface: 'flat-main', section: 'before-fields' },
      },
    ]);
    const draft = ref<Record<string, unknown>>({ title: '示例租户' });
    const formSessionKey = ref(1);
    const runtime = useModulePageFormContributionRuntime({
      contributions,
      mode: computed(() => 'edit' as const),
      draft,
      fields: ref(formFields()),
      formSessionKey,
      setField(fieldName, value) {
        draft.value = { ...draft.value, [fieldName]: value };
      },
    });

    const context = runtime.contextFor(contributions.value[0]);
    expect(Object.isFrozen(context.draft)).toBe(true);
    expect(context.draft).toEqual({ title: '示例租户' });
    expect(Object.isFrozen(context.fields[0])).toBe(true);
    expect(Object.isFrozen(context.fields[0].fieldControl)).toBe(true);
    expect(Reflect.set(context.fields[0].fieldControl!, 'rendererType', 'TEXTAREA')).toBe(false);
    expect(runtime.valid.value).toBe(true);

    context.reportValidity({ valid: false, errors: { brandMode: '请选择品牌模式' } });
    expect(runtime.valid.value).toBe(false);
    context.setField('brandMode', 'BRANDED');
    expect(draft.value.brandMode).toBe('BRANDED');

    context.reportValidity({ valid: true });
    expect(runtime.valid.value).toBe(true);
  });

  it('rejects writes outside the resolved writable field boundary', () => {
    const contributions = ref<readonly ModulePageFormContribution[]>([
      { key: 'branding', component: {}, location: { surface: 'flat-main', section: 'before-fields' } },
    ]);
    const runtime = useModulePageFormContributionRuntime({
      contributions,
      mode: computed(() => 'edit' as const),
      draft: ref({}),
      fields: ref(formFields()),
      formSessionKey: ref(1),
      setField: () => undefined,
    });
    const context = runtime.contextFor(contributions.value[0]);

    expect(() => context.setField('missing', 'value')).toThrow('未声明字段：missing');
    expect(() => context.setField('alias', 'value')).toThrow('只读字段：alias');
  });

  it('clears contribution diagnostics when the standard form session changes', () => {
    const contributions = ref<readonly ModulePageFormContribution[]>([
      {
        key: 'tenant-brand-mode',
        component: {},
        location: { surface: 'flat-main', fieldName: 'brandMode', placement: 'after' },
      },
    ]);
    const formSessionKey = ref(1);
    const runtime = useModulePageFormContributionRuntime({
      contributions,
      mode: computed(() => 'create' as const),
      draft: ref({}),
      fields: ref(formFields()),
      formSessionKey,
      setField: () => undefined,
    });

    runtime.contextFor(contributions.value[0]).reportValidity({ valid: false });
    expect(runtime.valid.value).toBe(false);
    formSessionKey.value += 1;
    expect(runtime.valid.value).toBe(true);
  });
});

function formFields(): Map<string, RecordFormFieldDescriptor> {
  return new Map<string, RecordFormFieldDescriptor>([
    [
      'brandMode',
      {
        fieldRef: { fieldName: 'brandMode' },
        uiType: 'input',
        fieldControl: { alias: 'brandMode', rendererType: 'TEXT', valueShape: 'SCALAR' },
      },
    ],
    [
      'alias',
      {
        fieldRef: { fieldName: 'alias' },
        uiType: 'input',
        readOnly: { constant: true },
      },
    ],
  ]);
}
