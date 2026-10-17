import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import WorkflowTimeline from '@/platform-components/WorkflowTimeline.vue';

it('shows business reasons by default and expands the full audit on request', async () => {
  const wrapper = mount(WorkflowTimeline, {
    props: {
      nodes: [
        { nodeKey: 'review', nodeType: 'approval', id: 'node', nodeTitle: '采购审批' },
        { nodeKey: 'large', nodeType: 'approval', id: 'definition-node', title: '大额采购复核' },
      ],
      events: [
        {
          id: 'approved',
          eventType: 'task_completed',
          actionCode: 'approve',
          nodeInstanceId: 'node',
          operatorTitle: '张三',
          payloadText: '{"reason":"同意采购"}',
          message: 'workflow task completed',
        },
        { id: 'activated', eventType: 'node_activated', message: '节点已激活', payloadText: 'invalid json' },
        {
          id: 'large-approved',
          eventType: 'task_completed',
          nodeKey: 'large',
          nodeInstanceId: 'runtime-node',
        },
        { id: 'task-linked', eventType: 'task_completed', taskId: 'arrival' },
        { id: 'finished', eventType: 'instance_completed', actionCode: 'complete' },
      ],
      tasks: [{ id: 'arrival', taskKind: 'task', taskStatus: 'completed', nodeInstanceId: 'node' }],
    },
  });
  expect(wrapper.text()).toContain('采购审批');
  expect(wrapper.text()).toContain('张三');
  expect(wrapper.text()).toContain('同意采购');
  expect(wrapper.text()).toContain('大额采购复核');
  expect(wrapper.findAll('li').filter((item) => item.text().includes('采购审批'))).toHaveLength(2);
  expect(wrapper.text()).toContain('流程完成');
  expect(wrapper.text()).not.toContain('节点已激活');
  await wrapper.findComponent({ name: 'UiCheckbox' }).vm.$emit('update:checked', true);
  expect(wrapper.text()).toContain('节点已激活');
  expect(wrapper.text()).toContain('workflow task completed');
  wrapper.unmount();
});

it('uses explicit opinions without treating translated system diagnostics as user reasons', () => {
  const wrapper = mount(WorkflowTimeline, {
    props: {
      nodes: [],
      events: [
        { id: 'system', eventType: 'task_completed', message: 'Tâche terminée' },
        { id: 'opinion', eventType: 'task_completed', reason: '保留原文意见', message: '任意技术说明' },
      ],
    },
  });
  expect(wrapper.text()).toContain('保留原文意见');
  expect(wrapper.text()).not.toContain('Tâche terminée');
  expect(wrapper.text()).not.toContain('任意技术说明');
  wrapper.unmount();
});

it('keeps the real opinion and operator beside shared reading translations in current and archived history', async () => {
  const { flushPromises } = await import('@vue/test-utils');
  const { defineComponent, h, ref } = await import('vue');
  const { vi } = await import('vitest');
  const { createLanguageContext, provideLanguageContext, businessContentLanguageContext } =
    await import('@muyun/web-core');
  const language = createLanguageContext();
  const history = ref(false);
  const readingLanguage = businessContentLanguageContext(language);
  readingLanguage.setLanguage('de');
  const raw = '密封件复检前继续暂停发货。';
  const events = [
    { id: 'opinion', version: 0, eventType: 'task_completed', reason: raw, operatorTitle: '中国工程师' },
  ];
  const baseline = JSON.stringify(events);
  const source = {
    text: raw,
    sourceLanguage: 'zh-CN',
    revision: 1,
    revisionId: 'original',
    authorId: 'cn',
    authorName: '中国工程师',
  };
  const request = vi.fn(async (entry: { path: string; query?: { language?: string }; method?: string }) => {
    if (entry.path.endsWith('/reading')) {
      const tag = entry.query?.language;
      return {
        recordVersion: 0,
        language: tag,
        fields: {
          reason: {
            originalText: raw,
            source,
            text: tag === 'de' ? 'Versand bis zur Nachprüfung anhalten.' : 'Hold shipment until retest.',
            actualLanguage: tag,
            originalFallback: false,
            translation: {
              revision: 1,
              kind: 'MACHINE',
              text: 'reading',
              authorId: 'cn',
              generationReference: 'job',
            },
          },
        },
      };
    }
    return {
      recordVersion: 0,
      fields: {
        reason: {
          content: { key: 'workflow-opinion', policy: 'AUTO_TRANSLATE' },
          text: raw,
          expectedRevision: 1,
          source,
        },
      },
    };
  });
  const context = {
    moduleAlias: 'quality.exception',
    http: { request },
    can: () => false,
  } as unknown as import('@muyun/web-core').ModuleContext<Record<string, unknown>>;
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () =>
          h(WorkflowTimeline, { events, nodes: [], context, instanceId: 'instance', history: history.value });
      },
    }),
  );
  await flushPromises();
  expect(wrapper.text()).toContain(raw);
  expect(wrapper.text()).toContain('中国工程师');
  expect(wrapper.text()).toContain('Versand bis zur Nachprüfung anhalten.');
  expect(request.mock.calls.some(([entry]) => entry.method === 'POST')).toBe(false);
  readingLanguage.setLanguage('en');
  await flushPromises();
  expect(wrapper.text()).toContain('Hold shipment until retest.');
  history.value = true;
  await flushPromises();
  expect(
    request.mock.calls.some(([entry]) =>
      entry.path.startsWith('/workflow/history/instance/events/opinion/content'),
    ),
  ).toBe(true);
  expect(wrapper.text()).toContain('Hold shipment until retest.');
  expect(JSON.stringify(events)).toBe(baseline);
  wrapper.unmount();
});
