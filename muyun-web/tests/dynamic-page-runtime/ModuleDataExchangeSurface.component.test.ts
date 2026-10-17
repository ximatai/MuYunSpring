import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ModuleDataExchangeSurface from '@/dynamic-page-runtime/ModuleDataExchangeSurface.vue';
import {
  createLanguageContext,
  provideLanguageContext,
  type ExchangePreview,
  type ModuleContext,
} from '@muyun/web-core';
import { defineComponent, h } from 'vue';
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
function preview(): ExchangePreview {
  return {
    documentId: 'file-identity',
    previewDigest: 'reviewed-digest',
    purpose: 'DATA',
    language: 'en',
    sheets: [
      {
        entityAlias: 'order',
        sheetName: '订单',
        main: true,
        rowCount: 2,
        matchFields: ['orderNo'],
        fieldTitles: { orderNo: '订单号', description: '说明', category: '分类' },
      },
      {
        entityAlias: 'line',
        sheetName: '明细',
        main: false,
        rowCount: 3,
        matchFields: ['sku'],
        fieldTitles: { sku: '商品' },
      },
    ],
    rows: [
      {
        row: 'row-1',
        entity: 'order',
        relation: 'R-1',
        operation: 'UPDATE',
        recordId: 'business-id',
        currentValues: { description: '中文原文' },
        changes: { description: 'New English business description' },
        languages: { description: { languageTag: 'en', expectedRevision: 1 } },
        choices: [],
      },
    ],
  };
}
function result(errors = 0) {
  return {
    created: 1,
    updated: 0,
    skipped: 1,
    errorCount: errors,
    partialSuccess: errors > 0,
    ...(errors ? { errorWorkbookBase64: btoa('failed workbook bytes') } : {}),
    rows: [],
  };
}
async function click(wrapper: ReturnType<typeof mount>, label: string) {
  const button = wrapper.findAll('button').find((button) => button.text() === label);
  expect(button, label).toBeDefined();
  await button!.trigger('click');
  await flushPromises();
}
async function upload(wrapper: ReturnType<typeof mount>, name = 'orders.xlsx') {
  const input = wrapper.get('input');
  Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['excel'], name)] });
  await input.trigger('change');
  await flushPromises();
}
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('requires review before import and retains the file, explicit source language and receipt across interface language changes', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi
    .fn()
    .mockResolvedValueOnce(preview())
    .mockResolvedValueOnce(preview())
    .mockResolvedValueOnce(result());
  const current = context(request);
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(ModuleDataExchangeSurface, { context: current, scopeKey: 'tenant-a' });
      },
    }),
    { global: { stubs } },
  );
  expect(wrapper.findAll('button')).toHaveLength(1);
  await click(wrapper, 'Data files');
  await click(wrapper, 'Import data');
  await upload(wrapper);
  expect(
    wrapper
      .findAll('button')
      .find((button) => button.text() === 'Confirm import')!
      .attributes('disabled'),
  ).toBeDefined();
  await wrapper.get('select[aria-label="Language of this business change"]').setValue('en');
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(wrapper.text()).toContain('orders.xlsx');
  expect(
    (wrapper.get('select[aria-label="本次业务修改的填写语言"]').element as HTMLSelectElement).value,
  ).toBe('en');
  expect(wrapper.text()).toContain('中文原文');
  expect(wrapper.text()).toContain('New English business description');
  await click(wrapper, '核对文件与本次修改');
  await click(wrapper, '确认导入');
  expect(request).toHaveBeenCalledTimes(3);
  const body = request.mock.calls[2]?.[0].body as FormData;
  expect(JSON.parse(await (body.get('command') as Blob).text())).toMatchObject({
    documentId: 'file-identity',
    previewDigest: 'reviewed-digest',
    sourceLanguage: 'en',
  });
  expect(request.mock.calls[2]?.[0].path).toBe('/sales.order/exchange/files/import');
  expect(wrapper.text()).toContain('新增 1，更新 0，跳过 1，异常 0 项');
  language.setLanguage('en');
  await flushPromises();
  expect(wrapper.text()).toContain('Import completed');
  expect(wrapper.text()).toContain('Created 1, updated 0, skipped 1, errors 0.');
  expect(request).toHaveBeenCalledTimes(3);
  expect(wrapper.findComponent(ModuleDataExchangeSurface).emitted('changed')).toHaveLength(1);
  wrapper.unmount();
});

it('requires explicit object selection for machine labels and retains that binding in the reviewed import command', async () => {
  const initial = preview();
  initial.rows[0]!.choices = [
    {
      field: 'category',
      input: 'Machine CNC',
      candidates: [{ id: 'category-a', title: '数控设备', matchedText: 'Machine CNC', kind: 'MACHINE' }],
    },
  ];
  const resolved = preview();
  resolved.rows[0]!.changes.category = 'category-a';
  const request = vi
    .fn()
    .mockResolvedValueOnce(initial)
    .mockResolvedValueOnce(resolved)
    .mockResolvedValueOnce(result());
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(request), scopeKey: '' },
    global: { stubs },
  });
  await click(wrapper, '业务文件');
  await click(wrapper, '导入数据');
  await upload(wrapper);
  expect(wrapper.text()).toContain('此确认不表示译文质量已确认');
  await wrapper.get('select[aria-label="R-1 category Machine CNC"]').setValue('category-a');
  await click(wrapper, '核对文件与本次修改');
  await click(wrapper, '确认导入');
  const body = request.mock.calls[2]?.[0].body as FormData;
  expect(JSON.parse(await (body.get('command') as Blob).text()).confirmations).toEqual([
    { row: 'row-1', field: 'category', input: 'Machine CNC', candidateId: 'category-a' },
  ]);
  wrapper.unmount();
});

it.each([
  ['independent records', 'R-2', true, false],
  ['one aggregate', 'R-1', false, false],
  ['independent records with a confirmed object', 'R-2', true, true],
  ['one aggregate with a confirmed object', 'R-1', false, true],
])(
  'keeps unresolved name choices from blocking only their own business unit: %s',
  async (_label, relation, allowed, confirmedFirst) => {
    const pending = preview();
    pending.rows.push({
      ...pending.rows[0]!,
      row: 'pending-row',
      relation: String(relation),
      choices: [
        {
          field: 'category',
          input: 'Machine CNC',
          candidates: [{ id: 'category-a', title: '数控设备', matchedText: 'Machine CNC', kind: 'MACHINE' }],
        },
      ],
    });
    if (confirmedFirst)
      pending.rows[0]!.choices = [
        {
          field: 'category',
          input: 'Original CNC',
          candidates: [
            { id: 'category-a', title: '数控设备', matchedText: 'Original CNC', kind: 'ORIGINAL' },
          ],
        },
      ];
    const reviewed = structuredClone(pending);
    reviewed.rows[0]!.choices = [];
    const request = vi
      .fn()
      .mockResolvedValueOnce(pending)
      .mockResolvedValueOnce(reviewed)
      .mockResolvedValueOnce(result(1));
    const wrapper = mount(ModuleDataExchangeSurface, {
      props: { context: context(request), scopeKey: '' },
      global: { stubs },
    });
    await click(wrapper, '业务文件');
    await click(wrapper, '导入数据');
    await upload(wrapper);
    if (confirmedFirst)
      await wrapper.get('select[aria-label="R-1 category Original CNC"]').setValue('category-a');
    await click(wrapper, '核对文件与本次修改');
    const confirm = wrapper.findAll('button').find((button) => button.text() === '确认导入')!;
    expect(confirm.attributes('disabled') === undefined).toBe(allowed);
    if (allowed) {
      expect(wrapper.text()).toContain('其他独立正确项可以先导入');
      await click(wrapper, '确认导入');
      const body = request.mock.calls[2]?.[0].body as FormData;
      expect(JSON.parse(await (body.get('command') as Blob).text()).confirmations).toEqual(
        confirmedFirst
          ? [{ row: 'row-1', field: 'category', input: 'Original CNC', candidateId: 'category-a' }]
          : [],
      );
      expect(wrapper.text()).toContain('下载错误文件');
    } else {
      expect(request).toHaveBeenCalledTimes(2);
    }
    wrapper.unmount();
  },
);

it('downloads only the returned correction workbook and accepts it directly as the next upload', async () => {
  const request = vi
    .fn()
    .mockResolvedValueOnce(preview())
    .mockResolvedValueOnce(preview())
    .mockResolvedValueOnce(result(1))
    .mockResolvedValueOnce(preview());
  const create = vi.fn().mockReturnValue('blob:failed');
  vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: vi.fn() });
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(request), scopeKey: '' },
    global: { stubs },
  });
  await click(wrapper, '业务文件');
  await click(wrapper, '导入数据');
  await upload(wrapper);
  await click(wrapper, '核对文件与本次修改');
  await click(wrapper, '确认导入');
  expect(wrapper.text()).toContain('异常 1 项');
  expect(wrapper.text()).toContain('无需再准备成功项');
  await click(wrapper, '下载错误文件');
  expect(await (create.mock.calls[0]?.[0] as Blob).text()).toBe('failed workbook bytes');
  expect(request).toHaveBeenCalledTimes(3);
  await upload(wrapper, 'corrected.xlsx');
  expect(wrapper.text()).toContain('corrected.xlsx');
  expect(wrapper.text()).not.toContain('异常 1 项');
  expect(request.mock.calls[3]?.[0].path).toBe('/sales.order/exchange/files/preview');
  wrapper.unmount();
});

it('retains one confirmed reference when another reference on the same row still needs a choice', async () => {
  const pending = preview();
  const choice = (field: string) => ({
    field,
    input: `Machine ${field}`,
    candidates: [{ id: `${field}-a`, title: field, matchedText: `Machine ${field}`, kind: 'MACHINE' }],
  });
  pending.rows.push({
    ...pending.rows[0]!,
    row: 'pending-row',
    relation: 'R-2',
    choices: [choice('category'), choice('product')],
  });
  const reviewed = structuredClone(pending);
  reviewed.rows[1]!.choices = [choice('product')];
  const request = vi
    .fn()
    .mockResolvedValueOnce(pending)
    .mockResolvedValueOnce(reviewed)
    .mockResolvedValueOnce(result(1));
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(request), scopeKey: '' },
    global: { stubs },
  });
  await click(wrapper, '业务文件');
  await click(wrapper, '导入数据');
  await upload(wrapper);
  await wrapper.get('select[aria-label="R-2 category Machine category"]').setValue('category-a');
  await click(wrapper, '核对文件与本次修改');
  await click(wrapper, '确认导入');
  const reviewedCommand = JSON.parse(await (request.mock.calls[1]?.[0].body.get('command') as Blob).text());
  const importedCommand = JSON.parse(await (request.mock.calls[2]?.[0].body.get('command') as Blob).text());
  expect(importedCommand.confirmations).toEqual([
    { row: 'pending-row', field: 'category', input: 'Machine category', candidateId: 'category-a' },
  ]);
  expect(importedCommand.confirmations).toEqual(reviewedCommand.confirmations);
  expect(wrapper.text()).toContain('下载错误文件');
  wrapper.unmount();
});

it('enables declared static modules, hides unauthorized modules and ignores a late preview after workspace changes', async () => {
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
  expect(staticPage.text()).toContain('业务文件');
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
  await click(wrapper, '业务文件');
  await click(wrapper, '导入数据');
  await upload(wrapper);
  await wrapper.setProps({ scopeKey: 'new' });
  finish(preview());
  await flushPromises();
  expect(wrapper.find('input').exists()).toBe(false);
  expect(wrapper.emitted('changed')).toBeUndefined();
  wrapper.unmount();
});

it('exports an explicit file purpose and language under the exact successful query or a selected stable record identity', async () => {
  const request = vi.fn().mockRejectedValue(new Error('测试下载拒绝'));
  let snapshot = { status: 'loading', mode: 'normal' };
  const queryController = { snapshot: () => snapshot } as RecordQueryListQueryController;
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: {
      context: context(request, true, 'static'),
      queryController,
      selectedIds: ['category-id'],
      scopeKey: '',
    },
    global: { stubs },
  });
  await click(wrapper, '业务文件');
  await click(wrapper, '导出当前查询');
  expect(request).not.toHaveBeenCalled();
  snapshot = {
    status: 'ready',
    mode: 'normal',
    request: { quickSearch: '目标订单', externalQueryValues: { customerId: 'one' } },
    contentLanguage: 'en',
  } as typeof snapshot;
  await wrapper.get('select[aria-label="导出用途"]').setValue('READING');
  await wrapper.get('select[aria-label="文件名称解析与导出语言"]').setValue('de');
  await click(wrapper, '导出当前查询');
  expect(request.mock.calls[0]?.[0]).toMatchObject({
    path: '/sales.order/exchange/files/export',
    headers: { 'X-MuYun-Content-Language': 'en' },
    body: {
      query: { quickSearch: '目标订单', externalQueryValues: { customerId: 'one' } },
      options: { purpose: 'READING', language: 'de' },
    },
  });
  await click(wrapper, '导出所选记录');
  expect(request.mock.calls[1]?.[0].body).toMatchObject({
    ids: ['category-id'],
    options: { purpose: 'READING', language: 'de' },
  });
  expect(request.mock.calls[1]?.[0].body.query).toBeUndefined();
  wrapper.unmount();
});

it('withdraws a successful review after options change or a later review fails', async () => {
  const request = vi
    .fn()
    .mockResolvedValueOnce(preview())
    .mockResolvedValueOnce(preview())
    .mockRejectedValueOnce(new Error('无法重新核对'));
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(request), scopeKey: '' },
    global: { stubs },
  });
  await click(wrapper, '业务文件');
  await click(wrapper, '导入数据');
  await upload(wrapper);
  await click(wrapper, '核对文件与本次修改');
  const confirm = () => wrapper.findAll('button').find((button) => button.text() === '确认导入')!;
  expect(confirm().attributes('disabled')).toBeUndefined();
  await wrapper.get('select[aria-label="本次业务修改的填写语言"]').setValue('de');
  expect(confirm().attributes('disabled')).toBeDefined();
  await click(wrapper, '核对文件与本次修改');
  expect(confirm().attributes('disabled')).toBeDefined();
  expect(wrapper.text()).toContain('无法重新核对');
  wrapper.unmount();
});

it('exposes an authorized secondary action for the host toolbar and keeps its standalone trigger optional', async () => {
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(vi.fn()), scopeKey: 'one', showTrigger: false },
    global: { stubs },
  });
  const surface = wrapper.vm as unknown as {
    action?: { key: string; actionLevel: string; disabled: boolean };
    openFiles(): void;
  };
  expect(surface.action).toMatchObject({ key: '__platform-data-files', actionLevel: 'secondary' });
  expect(wrapper.find('button').exists()).toBe(false);
  surface.openFiles();
  await flushPromises();
  expect(wrapper.text()).toContain('导入数据');
  await wrapper.setProps({ disabled: true });
  expect(surface.action?.disabled).toBe(true);
  wrapper.unmount();
  const denied = mount(ModuleDataExchangeSurface, {
    props: { context: context(vi.fn(), false), scopeKey: 'one', showTrigger: false },
    global: { stubs },
  });
  expect((denied.vm as unknown as { action?: unknown }).action).toBeUndefined();
  denied.unmount();
});
