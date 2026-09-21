import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { shallowMount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { RecordQueryListPanel } from '@muyun/platform-components';
import { configureModuleContext, type HttpClient } from '@/web-core';
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
