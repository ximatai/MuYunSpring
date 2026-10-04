import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ModuleDataExchangeSurface from '@/dynamic-page-runtime/ModuleDataExchangeSurface.vue';
import type { ModuleContext } from '@muyun/web-core';
import type { RecordQueryListQueryController } from '@muyun/platform-components';

const stubs = {
  RecordPanelButton: {
    props: ['disabled'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  UiModal: {
    props: ['open', 'confirmDisabled', 'confirmText'],
    template:
      '<section v-if="open"><slot /><button :disabled="confirmDisabled" @click="$emit(\'confirm\')">{{ confirmText }}</button><button @click="$emit(\'cancel\')">取消</button></section>',
  },
};
function context(request: ReturnType<typeof vi.fn>, permitted = true, kind = 'dynamic') {
  return {
    moduleAlias: 'sales.order',
    http: { request },
    can: () => permitted,
    runtime: { snapshot: () => ({ moduleKind: kind, capabilities: ['EXCHANGE'] }) },
  } as unknown as ModuleContext<unknown>;
}
const preview = {
  sheets: [
    {
      entityAlias: 'order',
      sheetName: '订单',
      main: true,
      rowCount: 2,
      fields: [{ fieldName: 'orderNo', title: '订单号', matchKeyCandidate: true }],
    },
    {
      entityAlias: 'line',
      sheetName: '明细',
      main: false,
      rowCount: 3,
      fields: [{ fieldName: 'sku', title: '商品', matchKeyCandidate: true }],
    },
  ],
};
async function upload(wrapper: ReturnType<typeof mount>, name = 'orders.xlsx') {
  const input = wrapper.get('input');
  Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['excel'], name)] });
  await input.trigger('change');
  await flushPromises();
}
afterEach(() => vi.restoreAllMocks());

it('lets a user review both sheets, confirm once, read partial results and upload a corrected file', async () => {
  const request = vi
    .fn()
    .mockResolvedValueOnce(preview)
    .mockResolvedValueOnce({
      created: 1,
      updated: 0,
      skipped: 1,
      errorCount: 1,
      partialSuccess: true,
      message: '导入完成，请修正异常数据后重试',
      errorFileToken: 'failed',
      summaries: { line: { entityAlias: 'line', created: 0, updated: 0, skipped: 1, errors: 1 } },
    })
    .mockResolvedValueOnce(preview);
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(request), scopeKey: 'customer-a' },
    global: { stubs },
  });
  await wrapper.get('button').trigger('click');
  await upload(wrapper);
  expect(request).toHaveBeenCalledTimes(1);
  expect(wrapper.text()).toContain('订单（主表，2 行）');
  expect(wrapper.text()).toContain('明细（子表，3 行）');
  await wrapper.get('select[aria-label="订单重复处理"]').setValue('SKIP');
  await wrapper.get('select[aria-label="明细重复处理"]').setValue('SKIP');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '确认导入')!
    .trigger('click');
  await flushPromises();
  expect(request.mock.calls[1]?.[0].path).toBe('/sales.order/import/execute');
  expect(wrapper.emitted('changed')).toHaveLength(1);
  expect(wrapper.text()).toContain('新增 1，更新 0，跳过 1，异常 1 项');
  expect(wrapper.text()).toContain('下载错误文件');
  expect(wrapper.emitted('busy')).toEqual([[true], [false], [true], [false]]);
  await upload(wrapper, 'corrected.xlsx');
  expect(wrapper.find('[role="status"]').exists()).toBe(false);
  expect(request.mock.calls[2]?.[0].path).toBe('/sales.order/import/parse');
  wrapper.unmount();
});

it('hides unsupported or unauthorized capabilities and discards preview responses after a scope change', async () => {
  const denied = mount(ModuleDataExchangeSurface, {
    props: { context: context(vi.fn(), false), scopeKey: '' },
    global: { stubs },
  });
  expect(denied.find('button').exists()).toBe(false);
  denied.unmount();
  const staticPage = mount(ModuleDataExchangeSurface, {
    props: { context: context(vi.fn(), true, 'static'), scopeKey: '' },
    global: { stubs },
  });
  expect(staticPage.find('button').exists()).toBe(false);
  staticPage.unmount();
  let finish!: (value: unknown) => void;
  const request = vi.fn().mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(request), scopeKey: 'old' },
    global: { stubs },
  });
  await wrapper.get('button').trigger('click');
  await upload(wrapper);
  await wrapper.setProps({ scopeKey: 'new' });
  finish(preview);
  await flushPromises();
  expect(wrapper.find('input').exists()).toBe(false);
  expect(wrapper.emitted('changed')).toBeUndefined();
  wrapper.unmount();
});

it('refuses export before a successful normal query and reuses its exact scope when ready', async () => {
  const request = vi.fn().mockRejectedValue(new Error('测试下载拒绝'));
  let snapshot = { status: 'loading', mode: 'normal' };
  const queryController = { snapshot: () => snapshot } as RecordQueryListQueryController;
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(request), queryController, scopeKey: '' },
    global: { stubs },
  });
  await wrapper.findAll('button')[1]!.trigger('click');
  expect(request).not.toHaveBeenCalled();
  snapshot = {
    status: 'ready',
    mode: 'normal',
    request: { quickSearch: '目标订单', externalQueryValues: { customerId: 'one' } },
  } as typeof snapshot;
  await wrapper.findAll('button')[1]!.trigger('click');
  await flushPromises();
  expect(request.mock.calls[0]?.[0].body).toEqual({
    quickSearch: '目标订单',
    externalQueryValues: { customerId: 'one' },
    unpaged: true,
  });
  expect(wrapper.text()).toContain('测试下载拒绝');
  wrapper.unmount();
});
