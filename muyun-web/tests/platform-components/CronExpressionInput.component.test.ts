import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { defineComponent } from 'vue';
import CronExpressionInput from '@/platform-components/CronExpressionInput.vue';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import UiPopover from '@/vue-ui-antdv/components/UiPopover.vue';
import UiButton from '@/vue-ui-antdv/components/UiButton.vue';
import type { CronValidationResult } from '@/platform-components/cronExpression';
import type { RecordFormFieldDescriptor } from '@/platform-components/recordFormFieldModel';
import type { ModuleContext } from '@muyun/web-core';

it('keeps custom syntax intact when the helper is opened and cancelled', async () => {
  const validator = vi.fn().mockResolvedValue({ valid: true });
  const wrapper = mount(CronExpressionInput, { props: { value: '0 0 9 ? * MON#2', validator } });
  try {
    await flushPromises();
    expect(wrapper.emitted('update:value')).toBeUndefined();
    wrapper.findComponent(UiPopover).vm.$emit('update:open', true);
    await flushPromises();
    expect(wrapper.findComponent(UiPopover).props('open')).toBe(true);
    wrapper.findComponent(UiPopover).vm.$emit('update:open', false);
    await flushPromises();
    expect(wrapper.emitted('update:value')).toBeUndefined();
    expect(wrapper.get('input').element.value).toBe('0 0 9 ? * MON#2');
  } finally {
    wrapper.unmount();
  }
});

it('validates on blur and ignores a stale initial response', async () => {
  const pending: Array<(value: CronValidationResult) => void> = [];
  const validator = vi.fn(() => new Promise<CronValidationResult>((resolve) => pending.push(resolve)));
  const wrapper = mount(CronExpressionInput, { props: { value: '0 0 0 * * *', validator } });
  try {
    await wrapper.get('input').setValue('0 30 9 * * *');
    await wrapper.get('input').trigger('blur');
    expect(validator).toHaveBeenLastCalledWith('0 30 9 * * *');
    pending[1]!({ valid: true });
    await flushPromises();
    pending[0]!({ valid: false, reason: 'INVALID_EXPRESSION' });
    await flushPromises();
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toEqual({ valid: true });
    expect(wrapper.get('[role="group"]').attributes('aria-invalid')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});

it('blocks invalid syntax, handles service failure, and clears without validating an optional empty value', async () => {
  const validator = vi.fn().mockResolvedValue({ valid: false, reason: 'MINUTE_PRECISION_REQUIRED' });
  const wrapper = mount(CronExpressionInput, { props: { value: '* * * * * *', validator } });
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('秒位固定为 0');
    validator.mockRejectedValueOnce(new Error('private upstream detail'));
    await wrapper.get('input').trigger('blur');
    await flushPromises();
    expect(wrapper.text()).toContain('校验服务暂不可用');
    expect(wrapper.text()).not.toContain('private upstream detail');
    validator.mockClear();
    await wrapper.get('input').setValue('');
    await wrapper.get('input').trigger('blur');
    await flushPromises();
    expect(validator).not.toHaveBeenCalled();
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toEqual({ valid: true });
  } finally {
    wrapper.unmount();
  }
});

it('debounces validation even while input remains focused', async () => {
  vi.useFakeTimers();
  const validator = vi.fn().mockResolvedValue({ valid: true });
  const wrapper = mount(CronExpressionInput, { props: { value: '', validator } });
  try {
    await wrapper.get('input').setValue('0 0 9 * * *');
    await vi.advanceTimersByTimeAsync(399);
    expect(validator).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(validator).toHaveBeenCalledTimes(1);
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toEqual({ valid: true });
  } finally {
    wrapper.unmount();
    vi.useRealTimers();
  }
});

it('does not validate disabled fields and cancels validation when the owner disables an edited field', async () => {
  const pending: Array<(value: CronValidationResult) => void> = [];
  const validator = vi.fn(() => new Promise<CronValidationResult>((resolve) => pending.push(resolve)));
  const wrapper = mount(CronExpressionInput, { props: { value: '0 0 9 * * *', validator, disabled: true } });
  try {
    expect(validator).not.toHaveBeenCalled();
    await wrapper.setProps({ disabled: false });
    expect(validator).toHaveBeenCalledTimes(1);
    await wrapper.setProps({ disabled: true });
    pending[0]!({ valid: false, reason: 'INVALID_EXPRESSION' });
    await flushPromises();
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toEqual({ valid: true });
  } finally {
    wrapper.unmount();
  }
});

it.each([true, false])(
  'integrates pending and invalid states into the standard form (typed=%s)',
  async (typed) => {
    const resolvers: Array<(value: CronValidationResult) => void> = [];
    const request = vi.fn(() => new Promise<CronValidationResult>((resolve) => resolvers.push(resolve)));
    const field: RecordFormFieldDescriptor = {
      fieldRef: { fieldName: 'expression' },
      label: '计划',
      valueType: typed ? 'STRING' : undefined,
      fieldControl: {
        alias: 'cron',
        rendererType: 'TEXT',
        valueShape: 'SCALAR',
        properties: {},
        bindings: [],
      },
    };
    const wrapper = mount(RecordFormFields, {
      props: {
        record: { expression: '0 0 9 * * *' },
        fields: new Map([['expression', field]]),
        mode: 'edit',
        optionContext: {
          moduleAlias: 'test.schedule',
          http: { request },
        } as unknown as ModuleContext<unknown>,
      },
    });
    try {
      await flushPromises();
      expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({
        valid: false,
        pending: true,
        errors: {},
      });
      resolvers[0]!({ valid: false, reason: 'INVALID_EXPRESSION' });
      await flushPromises();
      expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({
        valid: false,
        errors: { expression: expect.any(String) },
      });
      await wrapper.get('input').setValue('0 0 10 * * *');
      await wrapper.get('input').trigger('blur');
      resolvers[1]!({ valid: true });
      await flushPromises();
      expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: true, errors: {} });
    } finally {
      wrapper.unmount();
    }
  },
);

it('does not apply a late helper result after the person cancels the popover', async () => {
  let finish: ((result: CronValidationResult) => void) | undefined;
  const validator = vi
    .fn()
    .mockResolvedValueOnce({ valid: true })
    .mockImplementation(
      () =>
        new Promise<CronValidationResult>((resolve) => {
          finish = resolve;
        }),
    );
  const wrapper = mount(CronExpressionInput, {
    props: { value: '0 0 9 ? * MON#2', validator },
    attachTo: document.body,
    global: {
      stubs: {
        UiPopover: defineComponent({
          name: 'UiPopover',
          props: { open: Boolean },
          template: '<div><slot/><slot v-if="open" name="content"/></div>',
        }),
      },
    },
  });
  try {
    await flushPromises();
    wrapper.findComponent(UiPopover).vm.$emit('update:open', true);
    await flushPromises();
    const applyButton = wrapper
      .findAllComponents(UiButton)
      .find((button) => button.text().includes('应用计划'))!;
    await applyButton.trigger('click');
    expect(validator).toHaveBeenLastCalledWith('0 0 0 * * *');
    wrapper.findComponent(UiPopover).vm.$emit('update:open', false);
    await flushPromises();
    finish!({ valid: true });
    await flushPromises();
    expect(wrapper.get('input').element.value).toBe('0 0 9 ? * MON#2');
    expect(wrapper.emitted('update:value')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});

it('validates an existing expression without editing it, and normalizes only after blur', async () => {
  const validator = vi.fn().mockResolvedValue({ valid: true });
  const wrapper = mount(CronExpressionInput, { props: { value: '0  0 9 * * *', validator } });
  try {
    await flushPromises();
    expect(validator).toHaveBeenLastCalledWith('0  0 9 * * *');
    expect(wrapper.emitted('update:value')).toBeUndefined();
    await wrapper.get('input').trigger('blur');
    await flushPromises();
    expect(validator).toHaveBeenLastCalledWith('0 0 9 * * *');
    expect(wrapper.emitted('update:value')).toEqual([['0 0 9 * * *']]);
  } finally {
    wrapper.unmount();
  }
});

it('validates Cron only when its schedule kind is active and retains the draft across switches', async () => {
  const request = vi.fn().mockResolvedValue({ valid: false, reason: 'INVALID_EXPRESSION' });
  const field: RecordFormFieldDescriptor = {
    fieldRef: { fieldName: 'expression' },
    label: 'Cron',
    valueType: 'STRING',
    visible: {
      formula: {
        expression: "{scheduleKind} == 'CRON'",
        program: {
          schemaVersion: 1,
          profile: 'WEB_UI',
          referencedFields: ['scheduleKind'],
          root: {
            kind: 'BINARY',
            operator: '==',
            arguments: [
              { kind: 'FIELD', field: 'scheduleKind', arguments: [] },
              { kind: 'VALUE', value: 'CRON', arguments: [] },
            ],
          },
        },
      },
    },
    fieldControl: { alias: 'cron', rendererType: 'TEXT', valueShape: 'SCALAR', properties: {}, bindings: [] },
  };
  const record = { scheduleKind: 'CRON', expression: '0 0 99 * * *', intervalMillis: 60000 };
  const wrapper = mount(RecordFormFields, {
    props: {
      fields: new Map([['expression', field]]),
      record,
      mode: 'edit',
      optionContext: {
        moduleAlias: 'platform.scheduled_action_plan',
        http: { request },
      } as unknown as ModuleContext<unknown>,
    },
  });
  try {
    await flushPromises();
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: false });
    await wrapper.setProps({ record: { ...record, scheduleKind: 'FIXED_DELAY' } });
    expect(wrapper.findComponent(CronExpressionInput).exists()).toBe(false);
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: true, errors: {} });
    expect(wrapper.props('record').expression).toBe(record.expression);
    request.mockClear();
    await wrapper.setProps({ record });
    await flushPromises();
    expect(request).toHaveBeenCalledTimes(1);
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: false });
    expect(wrapper.emitted('update:field')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});
