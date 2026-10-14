import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import WorkflowBusinessTaskEditor from '@/platform-components/WorkflowBusinessTaskEditor.vue';

function configuration(kind = 'EXECUTE_ACTION') {
  return JSON.stringify({
    task: {
      definition: { moduleAlias: 'demo.old', manualConfirm: false },
      checks: [],
      guides: [
        {
          guideKey: 'delivery',
          guideKind: kind,
          targetActionCode: 'receive',
          guideConfigText: '{"payload":{}}',
        },
      ],
    },
  });
}

it('preserves invalid action input as an invalid draft instead of silently publishing old parameters', async () => {
  const wrapper = mount(WorkflowBusinessTaskEditor, {
    props: { moduleAlias: 'demo.purchase', value: configuration() },
  });
  const input = wrapper.findComponent({ name: 'UiTextArea' });
  input.vm.$emit('update:value', '{"quantity":');
  await wrapper.vm.$nextTick();
  expect(wrapper.get('[role="alert"]').text()).toContain('有效 JSON 对象');
  const invalid = JSON.parse(String(wrapper.emitted('update:value')!.at(-1)![0]));
  expect(JSON.parse(invalid.task.guides[0].guideConfigText).payload).toBe('{"quantity":');
  input.vm.$emit('update:value', '{"quantity":2}');
  await wrapper.vm.$nextTick();
  expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  const valid = JSON.parse(String(wrapper.emitted('update:value')!.at(-1)![0]));
  expect(valid.task.definition.moduleAlias).toBe('demo.purchase');
  expect(JSON.parse(valid.task.guides[0].guideConfigText).payload).toEqual({ quantity: 2 });
  wrapper.unmount();
});

it('keeps a published task editor read-only even if an input emits an update', async () => {
  const wrapper = mount(WorkflowBusinessTaskEditor, {
    props: { moduleAlias: 'demo.purchase', value: configuration(), disabled: true },
  });
  const input = wrapper.findComponent({ name: 'UiTextArea' });
  expect(input.props('disabled')).toBe(true);
  input.vm.$emit('update:value', '{"quantity":2}');
  await wrapper.vm.$nextTick();
  expect(wrapper.emitted('update:value')).toBeUndefined();
  wrapper.unmount();
});

it('initializes new authored defaults in English once and preserves them on a later language switch', async () => {
  const { defineComponent, h, nextTick } = await import('vue');
  const { createLanguageContext, provideLanguageContext } = await import('@muyun/web-core');
  const language = createLanguageContext({ languageTag: 'en' });
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(WorkflowBusinessTaskEditor, { moduleAlias: 'demo.purchase' });
      },
    }),
  );
  const editor = wrapper.findComponent(WorkflowBusinessTaskEditor);
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Add completion check')!
    .trigger('click');
  const authored = String(editor.emitted('update:value')!.at(-1)![0]);
  expect(JSON.parse(authored).task.checks[0]).toMatchObject({
    title: 'Business completion condition',
    failureMessage: 'Business data does not yet meet the completion condition',
    checkKind: 'FORMULA',
    expression: '',
  });
  const updates = editor.emitted('update:value')!.length;
  language.setLanguage('zh-CN');
  await nextTick();
  expect(editor.emitted('update:value')).toHaveLength(updates);
  expect(wrapper.findAllComponents({ name: 'UiInput' })[0]!.props('value')).toBe(
    'Business completion condition',
  );
  wrapper.unmount();
});
