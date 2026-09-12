import { shallowMount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { RecordQueryListPanel } from '@muyun/platform-components';
import { configureModuleContext, type HttpClient } from '@/web-core';
import BusinessLogListDrawer from '@/views/BusinessLogListDrawer.vue';

it.each([
  ['login', 'iam.login_audit_log', ['outcome', 'occurredAt', 'loginAccount']],
  ['activity', 'platform.business_activity_log', ['occurredAt', 'username', 'moduleAlias', 'actionCode']],
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

    expect((list.props('columns') as Array<{ key: string }>).map((column) => column.key)).toEqual(keys);
    const rowActionsOf = list.props('rowActionsOf') as
      | ((record: Record<string, unknown>) => unknown[])
      | undefined;
    expect(rowActionsOf?.({})).toEqual([
      expect.objectContaining({ key: 'detail', title: '详情', primary: true }),
    ]);
  },
);
