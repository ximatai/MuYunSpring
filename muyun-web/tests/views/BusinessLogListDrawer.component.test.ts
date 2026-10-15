import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { RecordQueryListPanel } from '@muyun/platform-components';
import {
  configureModuleContext,
  createLanguageContext,
  provideLanguageContext,
  AppError,
  type HttpClient,
} from '@/web-core';
import BusinessLogListDrawer from '@/views/BusinessLogListDrawer.vue';

const source = readFileSync(
  resolve(import.meta.dirname, '../../src/views/BusinessLogListDrawer.vue'),
  'utf8',
);

it.each([
  ['login', 'iam.login_audit_log', ['outcome', 'occurredAt', 'loginAccount']],
  [
    'activity',
    'platform.business_activity_log',
    ['outcome', 'occurredAt', 'username', 'moduleAlias', 'actionCode', 'recordId', 'mutationSource'],
  ],
  [
    'request-error',
    'platform.request_error_log',
    ['occurredAt', 'moduleAlias', 'errorCode', 'httpStatus', 'summary'],
  ],
] as const)(
  'presents the %s log with its operational columns and a discoverable detail entry',
  (surface, moduleAlias, keys) => {
    configureModuleContext({ http: { request: async () => ({}) } as HttpClient });
    const wrapper = shallowMount(BusinessLogListDrawer, {
      props: { surface, moduleAlias, title: '日志' },
    });
    const list = wrapper.findComponent(RecordQueryListPanel);
    expect(source).toContain('@record-activate="openDetail"');

    expect((list.props('columns') as Array<{ key: string }>).map((column) => column.key)).toEqual(keys);
    const rowActionsOf = list.props('rowActionsOf') as
      | ((record: Record<string, unknown>) => unknown[])
      | undefined;
    expect(rowActionsOf?.({})).toEqual([
      expect.objectContaining({ key: 'detail', title: '详情', primary: true }),
    ]);
    const referencePickerOf = list.props('referencePickerOf') as
      | ((field: { name: string }) => { placeholder?: string } | undefined)
      | undefined;
    expect(referencePickerOf?.({ name: 'operatorId' })).toEqual(
      expect.objectContaining({ placeholder: '按账号或用户 ID 搜索' }),
    );
    expect(referencePickerOf?.({ name: 'moduleAlias' })).toBeUndefined();
  },
);

it('maps each log surface to its contextual retention policies', () => {
  expect(source).toContain("login: ['LOGIN']");
  expect(source).toContain("activity: ['ACTION', 'PAGE_ACCESS']");
  expect(source).toContain("'request-error': ['REQUEST_ERROR']");
  expect(source).toContain('<BusinessLogRetentionControl :event-types="retentionEventTypes" />');
});

it('keeps the list title and separates business operations from query controls', () => {
  expect(source).not.toContain(':show-title="false"');
  expect(source).toContain('<template #operations>');
  expect(source).not.toContain('<template #toolbarActions>');
});

it('updates cached columns, picker labels and safe failure feedback without reloading log facts', async () => {
  const request = vi.fn(async () => {
    throw new AppError('内部数据库诊断', { status: 500, code: 'INTERNAL_ERROR' });
  });
  configureModuleContext({ http: { request } as HttpClient });
  const language = createLanguageContext({ languageTag: 'en' });
  const Harness = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () =>
        h(BusinessLogListDrawer, {
          surface: 'activity',
          moduleAlias: 'platform.business_activity_log',
          title: '业务原名',
        });
    },
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        RecordQueryListPanel: {
          name: 'RecordQueryListPanel',
          props: ['columns', 'referencePickerOf'],
          emits: ['record-activate'],
          template: '<section />',
        },
        RecordDetailDrawer: { props: ['open'], template: '<section v-if="open"><slot /></section>' },
        UiError: { props: ['message'], template: '<p>{{ message }}</p>' },
      },
    },
  });
  try {
    await flushPromises();
    const list = wrapper.findComponent({ name: 'RecordQueryListPanel' });
    const picker = list.props('referencePickerOf')({ name: 'operatorId' });
    const provider = picker.provider;
    expect(list.props('columns')[0].title).toBe('Outcome');
    expect(picker.title).toBe('Select an operator');
    list.vm.$emit('record-activate', { eventId: 'event-1', outcome: 'SUCCESS', summary: '业务日志原文' });
    await flushPromises();
    expect(wrapper.text()).toContain('A system error prevented the operation from completing');
    expect(wrapper.text()).not.toContain('内部数据库诊断');
    const calls = request.mock.calls.length;
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(list.props('columns')[0].title).toBe('结果');
    expect(picker.title).toBe('选择操作用户');
    expect(wrapper.text()).toContain('系统异常，操作未完成');
    language.setLanguage('en');
    await flushPromises();
    expect(list.props('columns')[0].title).toBe('Outcome');
    expect(picker.provider).toBe(provider);
    expect(request).toHaveBeenCalledTimes(calls);
  } finally {
    wrapper.unmount();
  }
});
