import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ModuleDataExchangeSurface from '@/platform-components/RecordDataExchangeSurface.vue';
import {
  createLanguageContext,
  provideLanguageContext,
  type ExchangePreview,
  type ModuleContext,
} from '@muyun/web-core';
import { defineComponent, h } from 'vue';
import type { RecordQueryListQueryController } from '@muyun/platform-components';

const stubs = {
  UiActionButton: {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>',
  },
  UiSelect: {
    props: ['value', 'options', 'disabled', 'ariaLabel', 'placeholder'],
    template: `<select :value="value || ''" :disabled="disabled" :aria-label="ariaLabel" @change="$emit('update:value', $event.target.value)"><option value="">{{ placeholder }}</option><option v-for="item in options" :key="item.value" :value="item.value" :disabled="item.disabled">{{ item.label }}</option></select>`,
  },
  RecordPanelButton: {
    props: ['disabled'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  UiModal: {
    props: ['open', 'confirmDisabled', 'confirmText'],
    template: '<section v-if="open"><slot /><slot name="operation" /></section>',
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
  expect(wrapper.text()).toContain('此确认不表示译文质量已确认');
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
  await click(wrapper, '导出文件');
  expect(request).not.toHaveBeenCalled();
  snapshot = {
    status: 'ready',
    mode: 'normal',
    request: { quickSearch: '目标订单', externalQueryValues: { customerId: 'one' } },
    contentLanguage: 'en',
  } as typeof snapshot;
  await wrapper.get('select[aria-label="导出用途"]').setValue('READING');
  await wrapper.get('select[aria-label="文件名称解析与导出语言"]').setValue('de');
  await click(wrapper, '导出文件');
  expect(request.mock.calls[0]?.[0]).toMatchObject({
    path: '/sales.order/exchange/files/export',
    headers: { 'X-MuYun-Content-Language': 'en' },
    body: {
      query: { quickSearch: '目标订单', externalQueryValues: { customerId: 'one' } },
      options: { purpose: 'READING', language: 'de' },
    },
  });
  await wrapper.get('select[aria-label="导出范围"]').setValue('selected');
  await click(wrapper, '导出文件');
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

it.each([undefined, 'code'])(
  'uses only an evidenced recommended matching key, otherwise requires a choice (%s)',
  async (recommendedMatchField) => {
    const parsed = preview();
    parsed.sheets[0]!.matchFields = ['description', 'code'];
    parsed.sheets[0]!.recommendedMatchField = recommendedMatchField;
    const request = vi.fn().mockResolvedValue(parsed);
    const wrapper = mount(ModuleDataExchangeSurface, {
      props: { context: context(request), scopeKey: '' },
      global: { stubs },
    });
    await click(wrapper, '业务文件');
    await click(wrapper, '导入数据');
    await upload(wrapper);
    const matching = wrapper.get('select[aria-label="订单 匹配字段"]');
    expect((matching.element as HTMLSelectElement).value).toBe(recommendedMatchField ?? '');
    const review = wrapper.findAll('button').find((item) => item.text() === '核对文件与本次修改')!;
    expect(review.attributes('disabled') !== undefined).toBe(!recommendedMatchField);
    if (!recommendedMatchField) await matching.setValue('code');
    expect(review.attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  },
);

it('prioritizes pending objects, preserves file work on close, and keeps language controls in disclosure', async () => {
  const parsed = preview();
  parsed.rows.push({
    ...parsed.rows[0]!,
    row: 'problem',
    relation: 'R-pending',
    operation: 'ERROR',
    changes: { description: 'Pending item' },
    choices: [
      {
        field: 'category',
        input: 'Same name',
        candidates: [
          {
            id: 'cat-one',
            title: 'Same name',
            subtitle: 'CAT-001',
            matchedText: 'Same name',
            kind: 'MACHINE',
          },
        ],
      },
    ],
  });
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(vi.fn().mockResolvedValue(parsed)), scopeKey: '' },
    global: { stubs },
  });
  await click(wrapper, '业务文件');
  await click(wrapper, '导入数据');
  await upload(wrapper);
  expect(wrapper.text()).toContain('Pending item');
  expect(wrapper.text()).not.toContain('New English business description');
  expect(wrapper.text()).toContain('CAT-001');
  const languageDisclosure = wrapper
    .findAll('details')
    .find((item) => item.text().includes('名称解析与本次填写语言'))!;
  expect(languageDisclosure.attributes('open')).toBeUndefined();
  await wrapper.get('select[aria-label="R-pending category Same name"]').setValue('cat-one');
  await click(wrapper, '全部（2）');
  expect(wrapper.text()).toContain('New English business description');
  await click(wrapper, '关闭');
  await click(wrapper, '业务文件');
  await click(wrapper, '导入数据');
  expect(wrapper.text()).toContain('orders.xlsx');
  expect(
    (wrapper.get('select[aria-label="R-pending category Same name"]').element as HTMLSelectElement).value,
  ).toBe('cat-one');
  wrapper.unmount();
});

it('shows authorized reference names above collapsed stable identities without editing raw changes', async () => {
  const parsed = preview();
  parsed.rows[0]!.changes = { category: 'cat-stable-id' };
  parsed.rows[0]!.currentValues = { category: 'old-stable-id' };
  parsed.rows[0]!.references = { category: { current: 'Old equipment', submitted: 'New equipment' } };
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: { context: context(vi.fn().mockResolvedValue(parsed)), scopeKey: '' },
    global: { stubs },
  });
  await click(wrapper, '业务文件');
  await click(wrapper, '导入数据');
  await upload(wrapper);
  const values = wrapper.get('.exchange-row dd').findAll(':scope > div');
  expect(values.map((item) => item.text())).toEqual(['当前内容: Old equipment', '本次修改: New equipment']);
  const identity = wrapper.get('.exchange-row dd details');
  expect(identity.attributes('open')).toBeUndefined();
  expect(identity.text()).toContain('cat-stable-id');
  expect(parsed.rows[0]!.changes.category).toBe('cat-stable-id');
  wrapper.unmount();
});

it.each(['CONTEXT', 'ERROR'])(
  'only omits matching controls after the server verifies read-only context (%s)',
  async (operation) => {
    const parsed = preview();
    parsed.sheets[0]!.matchFields = ['orderNo', 'description'];
    parsed.rows[0]!.operation = operation;
    parsed.rows[0]!.changes = {};
    parsed.rows.push({
      ...parsed.rows[0]!,
      row: 'child',
      entity: 'line',
      operation: 'UPDATE',
      changes: { qty: 0 },
    });
    const request = vi
      .fn()
      .mockResolvedValueOnce(parsed)
      .mockResolvedValueOnce(parsed)
      .mockResolvedValueOnce(result());
    const wrapper = mount(ModuleDataExchangeSurface, {
      props: { context: context(request), scopeKey: '' },
      global: { stubs },
    });
    await click(wrapper, '业务文件');
    await click(wrapper, '导入数据');
    await upload(wrapper);
    expect(wrapper.find('select[aria-label="订单 匹配字段"]').exists()).toBe(operation !== 'CONTEXT');
    const review = wrapper.findAll('button').find((item) => item.text() === '核对文件与本次修改')!;
    expect(review.attributes('disabled') === undefined).toBe(operation === 'CONTEXT');
    if (operation === 'CONTEXT') {
      expect(wrapper.text()).toContain('仅用于关联已有业务');
      await click(wrapper, '核对文件与本次修改');
      await click(wrapper, '确认导入');
      const body = request.mock.calls[2]?.[0].body as FormData;
      expect(JSON.parse(await (body.get('command') as Blob).text()).sheets).toEqual([
        { entityAlias: 'line', matchField: 'sku', duplicateStrategy: 'ERROR' },
      ]);
    }
    wrapper.unmount();
  },
);

it('retains an import rejection with its file without leaking it into export settings', async () => {
  const wrapper = mount(ModuleDataExchangeSurface, {
    props: {
      context: context(vi.fn().mockRejectedValue(new Error('Reading-only file cannot be imported'))),
      scopeKey: '',
    },
    global: { stubs },
  });
  await click(wrapper, '业务文件');
  await click(wrapper, '导入数据');
  await upload(wrapper, 'reading.xlsx');
  expect(wrapper.text()).toContain('Reading-only file cannot be imported');
  await click(wrapper, '关闭');
  await click(wrapper, '业务文件');
  expect(wrapper.text()).not.toContain('Reading-only file cannot be imported');
  await click(wrapper, '导入数据');
  expect(wrapper.text()).toContain('reading.xlsx');
  expect(wrapper.text()).toContain('Reading-only file cannot be imported');
  expect(
    wrapper
      .findAll('button')
      .find((item) => item.text() === '确认导入')!
      .attributes('disabled'),
  ).toBeDefined();
  wrapper.unmount();
});
