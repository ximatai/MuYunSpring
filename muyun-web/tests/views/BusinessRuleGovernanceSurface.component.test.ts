import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { configureModuleContext, type HttpClient, type HttpRequestOptions } from '@/web-core';
import BusinessRuleGovernanceSurface from '@/views/BusinessRuleGovernanceSurface.vue';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';

const mounted = new Set<{ unmount: () => void }>();
const UiButton = defineComponent({
  name: 'UiButton',
  emits: ['click'],
  template: '<button @click="$emit(\'click\')"><slot /></button>',
});
const UiModal = defineComponent({
  name: 'UiModal',
  emits: ['confirm', 'cancel'],
  template: '<section><slot /></section>',
});
const UiTextArea = defineComponent({
  name: 'UiTextArea',
  props: { value: String, disabled: Boolean },
  emits: ['update:value', 'selection', 'drop'],
  setup(_, { expose }) {
    let selection = { start: 0, end: 0 };
    expose({
      selection: () => selection,
      focusSelection: (start: number, end = start) => {
        selection = { start, end };
      },
    });
  },
  template:
    '<textarea :value="value" :disabled="disabled" @input="$emit(\'update:value\', $event.target.value)" />',
});
const SlotPassthrough = defineComponent({ template: '<section><slot /><slot name="actions" /></section>' });
const DetailLayout = defineComponent({
  name: 'RecordDetailLayout',
  props: { subtitle: { type: String, default: undefined } },
  template: '<section><slot /><slot name="actions" /></section>',
});
const ExplorerPanel = defineComponent({ template: '<section><slot /><slot name="actions" /></section>' });
const RuleDrawer = defineComponent({
  name: 'RecordDetailDrawer',
  props: { open: Boolean },
  emits: ['close'],
  template: '<section><slot /><slot name="operation" /></section>',
});
const RuleListCell = defineComponent({
  name: 'RecordQueryListCell',
  props: { record: { type: Object, required: true }, column: { type: Object, required: true } },
  template: '<span class="record-query-list-cell">{{ record[column.key] }}</span>',
});
const RuleListSurface = defineComponent({
  name: 'RecordQueryListSurface',
  props: {
    rows: { type: Array, default: () => [] },
    columns: { type: Array, default: () => [] },
    quickSearchValue: { type: String, default: '' },
    pageNum: { type: Number, default: 1 },
    pageSize: { type: Number, default: 10 },
    pages: { type: Number, default: 1 },
    total: { type: Number, default: 0 },
    actionColumnWidth: { type: [String, Number], default: undefined },
    actionColumnFixed: { type: Boolean, default: true },
    fillHeight: { type: Boolean, default: true },
    showTitle: { type: Boolean, default: true },
  },
  emits: ['update:quickSearchValue', 'rowClick', 'rowDblclick', 'pageChange', 'pageSizeChange'],
  template: `
    <section class="record-query-list-surface">
      <slot name="operations" />
      <input
        class="record-query-list-search"
        :value="quickSearchValue"
        @input="$emit('update:quickSearchValue', $event.target.value)"
      />
      <slot name="beforeTable" />
      <div
        v-for="row in rows"
        :key="row.id"
        class="record-query-list-row"
        @click="$emit('rowClick', row)"
        @dblclick="$emit('rowDblclick', row)"
      >
        <template v-for="column in columns" :key="column.key">
          <slot name="cell" :column="column" :record="row" :value="row[column.key]">
            {{ row[column.key] }}
          </slot>
        </template>
        <slot name="rowActions" :record="row" :row-key="row.id" />
      </div>
    </section>
  `,
});
const Workspace = defineComponent({
  name: 'ManagementWorkspace',
  props: { editing: Boolean },
  template: '<section><slot /></section>',
});

afterEach(() => {
  mounted.forEach((wrapper) => wrapper.unmount());
  mounted.clear();
  vi.restoreAllMocks();
});

function snapshot(moduleAlias = 'education.exam', fields = true) {
  return {
    moduleAlias,
    baselineFingerprint: `baseline:${moduleAlias}`,
    editableFields: fields
      ? [
          { fieldName: 'quantity', title: '数量', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
          { fieldName: 'enabled', title: '启用', fieldSpecAlias: 'boolean', valueType: 'BOOLEAN' },
          { fieldName: 'effectiveDate', title: '生效日期', fieldSpecAlias: 'date', valueType: 'DATE' },
          { fieldName: 'amount', title: '金额', fieldSpecAlias: 'decimal', valueType: 'DECIMAL' },
        ]
      : [],
    rules: [
      {
        code: 'calculation_amount',
        kind: 'CALCULATION',
        phase: 'FORM_COMPUTE',
        targetField: 'amount',
        expression: '{quantity} * 10',
        enabled: true,
        editable: true,
      },
      {
        code: 'validation_quantity',
        kind: 'VALIDATION',
        phase: 'BEFORE_SAVE',
        targetField: 'quantity',
        expression: '{quantity} > 0',
        enabled: true,
        messageTemplate: '数量必须大于零',
        editable: true,
      },
      {
        code: 'legacy_default',
        kind: 'DEFAULT',
        phase: 'BEFORE_SAVE',
        expression: '1',
        enabled: true,
        editable: false,
        readOnlyReason: '默认值在首期不提供治理入口。',
      },
    ],
  };
}

function fakeHttp(): HttpClient {
  return {
    request: vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/page-reference-fields'))
        return {
          moduleAlias: 'education.exam',
          fields: [
            { id: 'quantity', name: 'quantity', label: '数量', valueType: 'DECIMAL', formulaReadable: true },
            {
              id: 'supplierId',
              name: 'supplierId',
              label: '供应商',
              valueType: 'REFERENCE',
              referenceCardinality: 'ONE',
              expandable: true,
              formulaReadable: true,
            },
            {
              id: 'lines',
              name: 'lines',
              label: '明细',
              valueType: 'REFERENCE',
              referenceCardinality: 'MANY',
              formulaReadable: false,
              formulaDisabledReason: '集合引用不能作为标量公式字段。',
            },
          ],
        } as never;
      if (options.path.endsWith('page-reference-fields?path=supplierId'))
        return {
          moduleAlias: 'education.exam',
          path: 'supplierId',
          fields: [
            { id: 'supplier-title', name: 'supplierId.title', label: '供应商名称', valueType: 'STRING' },
            {
              id: 'supplier-secret',
              name: 'supplierId.secret',
              label: '供应商受保护备注',
              valueType: 'STRING',
              formulaReadable: false,
              formulaDisabledReason: '受保护引用字段不能读取',
            },
          ],
        } as never;
      if (options.path.endsWith('/preview'))
        return {
          snapshot: snapshot(),
          proposalFingerprint: 'proposal-1',
          executionOrder: ['calculation_amount', 'validation_quantity'],
          errors: [],
        } as never;
      if (options.path.endsWith('/trial'))
        return {
          preview: {
            snapshot: snapshot(),
            proposalFingerprint: 'proposal-1',
            executionOrder: [],
            errors: [],
          },
          values: { quantity: 12, enabled: true, effectiveDate: '2026-09-10', amount: 120 },
          changedFields: ['amount'],
          errors: [],
        } as never;
      if (options.path.endsWith('/apply'))
        return { snapshot: snapshot(), preview: {}, activatedModules: ['education.exam'] } as never;
      return snapshot(options.path.includes('other') ? 'education.other' : 'education.exam') as never;
    }),
  };
}

function mountSurface(http: HttpClient, moduleAlias = 'education.exam') {
  configureModuleContext({ http });
  const wrapper = shallowMount(BusinessRuleGovernanceSurface, {
    props: { moduleAlias },
    global: {
      stubs: {
        UiButton,
        UiModal,
        UiTextArea,
        ManagementWorkspace: Workspace,
        ManagementExplorerColumn: SlotPassthrough,
        RecordExplorerPanel: ExplorerPanel,
        RecordDetailLayout: DetailLayout,
        RecordDetailDrawer: RuleDrawer,
        RecordQueryListCell: RuleListCell,
        RecordQueryListSurface: RuleListSurface,
      },
    },
  });
  mounted.add(wrapper);
  return wrapper;
}

function mountSurfaceAsSystemUser(http: HttpClient, currentUser: { system: boolean; tenantId?: string }) {
  configureModuleContext({ http });
  const Host = defineComponent({
    setup() {
      provideCurrentUserContext(ref({ userId: 'system-admin', ...currentUser } as never));
      return () => h(BusinessRuleGovernanceSurface, { moduleAlias: 'education.exam' });
    },
  });
  const wrapper = mount(Host, {
    global: {
      stubs: {
        UiButton,
        UiModal,
        UiTextArea,
        ManagementWorkspace: Workspace,
        ManagementExplorerColumn: SlotPassthrough,
        RecordExplorerPanel: ExplorerPanel,
        RecordDetailLayout: DetailLayout,
        RecordDetailDrawer: RuleDrawer,
        RecordQueryListCell: RuleListCell,
        RecordQueryListSurface: RuleListSurface,
      },
    },
  });
  mounted.add(wrapper);
  return wrapper.findComponent(BusinessRuleGovernanceSurface);
}

function action(wrapper: ReturnType<typeof shallowMount>, title: string) {
  return wrapper.findAll('button').find((button) => button.text() === title)!;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => (resolve = next));
  return { promise, resolve };
}

describe('BusinessRuleGovernanceSurface', () => {
  it('loads RHS rules and retains unsupported rules as read-only', async () => {
    const http = fakeHttp();
    const wrapper = mountSurface(http);
    await flushPromises();
    expect(wrapper.text()).toContain('字段计算');
    expect(wrapper.text()).toContain('业务校验');
    expect(wrapper.text()).toContain('默认值在首期不提供治理入口。');
    expect(http.request).toHaveBeenCalledWith({ path: '/platform.module/education.exam/business-rules' });
  });

  it('renders editable rules through the standard query-list surface with business columns', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();

    const list = wrapper.findComponent({ name: 'RecordQueryListSurface' });
    expect(list.exists()).toBe(true);
    expect((list.props('columns') as Array<{ title: string }>).map((column) => column.title)).toEqual([
      '业务名称',
      '目标字段或条件摘要',
      '启用状态',
      '变更状态',
    ]);
    expect(wrapper.find('.record-query-list-row').text()).toContain('计算金额');
    expect(list.props('actionColumnWidth')).toBe(120);
    expect(list.props('actionColumnFixed')).toBe(false);
    expect(list.props('fillHeight')).toBe(false);
    expect(list.props('showTitle')).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordDetailLayout' }).props('subtitle')).toBeUndefined();
    expect(action(wrapper, '应用更改')).toBeUndefined();
    expect(action(wrapper, '试算整组规则').exists()).toBe(true);
    expect(wrapper.text()).toContain('默认值在首期不提供治理入口。');
  });

  it('keeps automatic checking and application scoped to all local rules after search and pagination', async () => {
    const listedSnapshot = {
      ...snapshot(),
      rules: [
        ...snapshot().rules,
        {
          code: 'calculation_quantity',
          kind: 'CALCULATION',
          phase: 'FORM_COMPUTE',
          targetField: 'quantity',
          expression: '{amount} / 10',
          enabled: true,
          editable: true,
        },
      ],
    };
    const fallback = fakeHttp();
    const http: HttpClient = {
      request: vi.fn((options: HttpRequestOptions) => {
        if (options.path.endsWith('/business-rules')) return Promise.resolve(listedSnapshot) as never;
        if (options.path.endsWith('/preview'))
          return Promise.resolve({
            snapshot: listedSnapshot,
            proposalFingerprint: 'all-local-rules',
            executionOrder: [],
            errors: [],
          }) as never;
        if (options.path.endsWith('/apply'))
          return Promise.resolve({ snapshot: listedSnapshot, preview: {}, activatedModules: [] }) as never;
        return fallback.request(options);
      }) as HttpClient['request'],
    };
    const wrapper = mountSurface(http);
    await flushPromises();
    const list = wrapper.findComponent({ name: 'RecordQueryListSurface' });
    list.vm.$emit('pageSizeChange', 1);
    list.vm.$emit('pageChange', 2);
    await flushPromises();
    expect(list.props('pageNum')).toBe(2);
    list.vm.$emit('update:quickSearchValue', 'calculation_quantity');
    await flushPromises();
    expect(list.props('pageNum')).toBe(1);
    expect((list.props('rows') as Array<{ code: string }>).map((rule) => rule.code)).toEqual([
      'calculation_quantity',
    ]);

    wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.vm.$emit('update:value', '{quantity} * 12');
    await flushPromises();
    await action(wrapper, '应用更改').trigger('click');
    await flushPromises();

    const applyCall = vi
      .mocked(http.request)
      .mock.calls.find(([options]) => options.path.endsWith('/apply'))![0];
    expect((applyCall.body as { rules: unknown[] }).rules).toHaveLength(3);
  });

  it('clamps the local list page after deletion and opens rows only on double click', async () => {
    const listedSnapshot = {
      ...snapshot(),
      rules: [
        ...snapshot().rules,
        {
          code: 'calculation_quantity',
          kind: 'CALCULATION',
          phase: 'FORM_COMPUTE',
          targetField: 'quantity',
          expression: '{amount} / 10',
          enabled: true,
          editable: true,
        },
      ],
    };
    const fallback = fakeHttp();
    const http: HttpClient = {
      request: vi.fn((options: HttpRequestOptions) =>
        options.path.endsWith('/business-rules')
          ? (Promise.resolve(listedSnapshot) as never)
          : fallback.request(options),
      ) as HttpClient['request'],
    };
    const wrapper = mountSurface(http);
    await flushPromises();
    const list = wrapper.findComponent({ name: 'RecordQueryListSurface' });
    list.vm.$emit('pageSizeChange', 1);
    list.vm.$emit('pageChange', 2);
    await flushPromises();
    const row = (list.props('rows') as Array<Record<string, unknown>>)[0]!;
    list.vm.$emit('rowClick', row);
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).props('open')).toBe(false);
    list.vm.$emit('rowDblclick', row);
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).props('open')).toBe(true);
    await action(wrapper, '删除规则').trigger('click');
    await flushPromises();
    expect(list.props('pageNum')).toBe(1);
  });

  it('keeps rule navigation and creation available while local changes await application', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();
    wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.vm.$emit('update:value', '{quantity} * 12');
    await flushPromises();
    expect(wrapper.text()).toContain('未应用 1 项');
    await action(wrapper, '业务校验').trigger('click');
    await action(wrapper, '新增规则').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('保存校验通用条件');
  });

  it('guides a new calculation to select its target before exposing the expression editor', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();
    await action(wrapper, '新增规则').trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).props('open')).toBe(true);
    expect(wrapper.text()).toContain('请先在“业务目的”中选择计算目标字段');
    expect(wrapper.findAllComponents({ name: 'UiTextArea' })).toHaveLength(0);
    const target = wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((select) => select.props('placeholder') === '选择可写字段')!;
    target.vm.$emit('update:value', 'amount');
    await flushPromises();
    expect(wrapper.findAllComponents({ name: 'UiTextArea' })).toHaveLength(1);
  });

  it('explains the save condition while editing a validation rule', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();
    await action(wrapper, '业务校验').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('满足以下条件才允许保存；不满足时显示失败提示，并可定位到字段。');
    const message = wrapper
      .findAllComponents({ name: 'UiInput' })
      .find((input) => input.props('value') === '数量必须大于零')!;
    message.vm.$emit('update:value', '请输入大于零的数量');
    await flushPromises();
    expect(
      wrapper
        .findAllComponents({ name: 'UiInput' })
        .some((input) => input.props('value') === '请输入大于零的数量'),
    ).toBe(true);
  });

  it('opens a rule in the wide drawer and keeps local validation input after closing it', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();
    await action(wrapper, '业务校验').trigger('click');
    await wrapper.find('.record-query-list-row').trigger('dblclick');
    const drawer = wrapper.findComponent({ name: 'RecordDetailDrawer' });
    expect(drawer.props('open')).toBe(true);
    wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.vm.$emit('update:value', 'PRESENT({quantity})');
    await flushPromises();
    await action(wrapper, '完成编辑').trigger('click');
    await flushPromises();
    expect(drawer.props('open')).toBe(false);
    expect(wrapper.find('.record-query-list-row').text()).toContain('PRESENT');
  });

  it('keeps field insertion in the left directory and never overwrites a nonempty expression with a template', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();
    expect(wrapper.findComponent({ name: 'UiTree' }).exists()).toBe(true);
    expect(wrapper.text()).toContain('已有表达式不会被模板覆盖');
  });

  it('applies a template only to an empty new rule', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();
    await action(wrapper, '业务校验').trigger('click');
    await action(wrapper, '新增规则').trigger('click');
    await flushPromises();
    const template = wrapper.findAll('button').find((button) => button.text().startsWith('字段有值：'))!;
    await template.trigger('click');
    await flushPromises();

    expect(wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.props('value')).toBe('PRESENT({quantity})');
  });

  it('inserts a supported function then places a field inside its parameter', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();
    await action(wrapper, '业务校验').trigger('click');
    await action(wrapper, '新增规则').trigger('click');
    await flushPromises();
    await action(wrapper, '函数与运算符').trigger('click');
    await action(wrapper, 'PRESENT（字段有值）').trigger('click');
    await flushPromises();
    await action(wrapper, '插入函数').trigger('click');
    await flushPromises();
    expect(wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.props('value')).toBe('PRESENT()');
    wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.vm.$emit('drop', {
      source: {
        payloadType: 'formula-field',
        payload: { kind: 'formula-field', moduleAlias: 'education.exam', fieldName: 'quantity' },
      },
      selection: { start: 8, end: 8 },
    });
    await flushPromises();

    expect(wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.props('value')).toBe('PRESENT({quantity})');
  });

  it('keeps each rule caret when switching modes and inserts a tree field at that caret', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();
    await action(wrapper, '业务校验').trigger('click');
    await flushPromises();
    const expression = () => wrapper.findAllComponents({ name: 'UiTextArea' })[0]!;
    expression().vm.$emit('selection', { start: 0, end: 0 });
    await action(wrapper, '字段计算').trigger('click');
    await action(wrapper, '业务校验').trigger('click');
    await flushPromises();
    expression().vm.$emit('drop', {
      source: {
        payloadType: 'formula-field',
        payload: { kind: 'formula-field', moduleAlias: 'education.exam', fieldName: 'quantity' },
      },
      selection: { start: 0, end: 0 },
    });
    await flushPromises();
    expect(expression().props('value')).toBe('{quantity}{quantity} > 0');
  });

  it('loads ONE reference descendants once and exposes MANY as unavailable', async () => {
    const http = fakeHttp();
    const wrapper = mountSurface(http);
    await flushPromises();
    const tree = wrapper.findComponent({ name: 'UiTree' });
    const many = (tree.props('nodes') as Array<{ key: string; disabled?: boolean }>).find((node) =>
      node.key.endsWith(':lines'),
    );
    expect(many?.disabled).toBe(true);
    const loader = tree.props('loadChildren') as (
      node: { key: string },
      request: { signal: AbortSignal },
    ) => Promise<{ nodes: unknown[] }>;
    const response = await loader(
      { key: 'formula-field:supplierId' },
      { signal: new AbortController().signal },
    );
    expect(response.nodes).toHaveLength(2);
    expect(response.nodes).toContainEqual(
      expect.objectContaining({ key: 'formula-field:supplierId.secret', disabled: true }),
    );
    expect(vi.mocked(http.request)).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/platform.module/education.exam/page-reference-fields?path=supplierId',
      }),
    );
  });

  it('rejects a protected dotted field payload even when a caller bypasses the tree drag guard', async () => {
    const wrapper = mountSurface(fakeHttp());
    await flushPromises();
    const tree = wrapper.findComponent({ name: 'UiTree' });
    const loader = tree.props('loadChildren') as (
      node: { key: string },
      request: { signal: AbortSignal },
    ) => Promise<{ nodes: unknown[] }>;
    await loader({ key: 'formula-field:supplierId' }, { signal: new AbortController().signal });
    const expression = wrapper.findAllComponents({ name: 'UiTextArea' })[0]!;
    expression.vm.$emit('drop', {
      source: {
        payloadType: 'formula-field',
        payload: { kind: 'formula-field', moduleAlias: 'education.exam', fieldName: 'supplierId.secret' },
      },
      selection: { start: 0, end: 0 },
    });
    await flushPromises();

    expect(expression.props('value')).toBe('{quantity} * 10');
  });

  it('automatically checks before applying and sends the captured baseline and proposal tokens', async () => {
    const http = fakeHttp();
    const wrapper = mountSurface(http);
    await flushPromises();
    wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.vm.$emit('update:value', '{quantity} * 12');
    await flushPromises();
    expect(action(wrapper, '检查规则')).toBeUndefined();
    expect(wrapper.find('[data-testid="business-rule-preview"]').exists()).toBe(false);
    await action(wrapper, '应用更改').trigger('click');
    await flushPromises();
    const previewCall = vi
      .mocked(http.request)
      .mock.calls.find(([options]) => options.path.endsWith('/preview'))![0];
    expect((previewCall.body as { rules: unknown[] }).rules).toContainEqual(
      expect.objectContaining({ expression: '{quantity} * 12' }),
    );
    const applyCall = vi
      .mocked(http.request)
      .mock.calls.find(([options]) => options.path.endsWith('/apply'))![0];
    expect(applyCall.body).toMatchObject({
      baselineFingerprint: 'baseline:education.exam',
      proposalFingerprint: 'proposal-1',
    });
  });

  it('links an automatic check error back to its editable rule without applying', async () => {
    const http = fakeHttp();
    vi.mocked(http.request).mockImplementation((options) => {
      if (options.path.endsWith('/preview'))
        return Promise.resolve({
          snapshot: snapshot(),
          proposalFingerprint: 'proposal-invalid',
          executionOrder: [],
          errors: [
            { code: 'INVALID_RULE_EXPRESSION', ruleCode: 'validation_quantity', message: '表达式不合法' },
          ],
        }) as never;
      return fakeHttp().request(options);
    });
    const wrapper = mountSurface(http);
    await flushPromises();
    wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.vm.$emit('update:value', '{quantity} * 12');
    await flushPromises();
    await action(wrapper, '应用更改').trigger('click');
    await flushPromises();
    await wrapper.find('.business-rule-governance__issue-link').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('校验数量');
    expect(vi.mocked(http.request).mock.calls.some(([options]) => options.path.endsWith('/apply'))).toBe(
      false,
    );
  });

  it('locates incomplete local rules and does not send them for checking', async () => {
    const http = fakeHttp();
    const wrapper = mountSurface(http);
    await flushPromises();
    await action(wrapper, '新增规则').trigger('click');
    await flushPromises();
    await action(wrapper, '应用更改').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-testid="business-rule-issues"]').text()).toContain('请选择计算目标字段');
    expect(vi.mocked(http.request).mock.calls.some(([options]) => options.path.endsWith('/preview'))).toBe(
      false,
    );
    expect(vi.mocked(http.request).mock.calls.some(([options]) => options.path.endsWith('/apply'))).toBe(
      false,
    );
  });

  it('does not duplicate automatic checking when the application action is clicked twice', async () => {
    const pendingPreview = deferred<unknown>();
    const http = fakeHttp();
    vi.mocked(http.request).mockImplementation((options) =>
      options.path.endsWith('/preview') ? (pendingPreview.promise as never) : fakeHttp().request(options),
    );
    const wrapper = mountSurface(http);
    await flushPromises();
    wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.vm.$emit('update:value', '{quantity} * 12');
    await flushPromises();
    await action(wrapper, '应用更改').trigger('click');
    await action(wrapper, '应用更改').trigger('click');
    expect(
      vi.mocked(http.request).mock.calls.filter(([options]) => options.path.endsWith('/preview')),
    ).toHaveLength(1);

    pendingPreview.resolve({
      snapshot: snapshot(),
      proposalFingerprint: 'proposal-1',
      executionOrder: [],
      errors: [],
    });
    await flushPromises();
    expect(
      vi.mocked(http.request).mock.calls.filter(([options]) => options.path.endsWith('/apply')),
    ).toHaveLength(1);
  });

  it('uses typed sample input and does not apply while trialing', async () => {
    const http = fakeHttp();
    const wrapper = mountSurface(http);
    await flushPromises();
    await action(wrapper, '试算整组规则').trigger('click');
    wrapper
      .findAllComponents({ name: 'UiInput' })
      .find((input) => input.props('type') === 'number')!
      .vm.$emit('update:value', '12');
    wrapper
      .findAllComponents({ name: 'UiInput' })
      .find((input) => input.props('type') === 'date')!
      .vm.$emit('update:value', '2026-09-10');
    wrapper
      .findAllComponents({ name: 'UiSwitch' })
      .find((input) => input.props('checkedText') === '是')!
      .vm.$emit('update:checked', true);
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
    await flushPromises();
    const trialCall = vi
      .mocked(http.request)
      .mock.calls.find(([options]) => options.path.endsWith('/trial'))![0];
    expect(trialCall.body).toMatchObject({
      sampleValues: { quantity: 12, enabled: true, effectiveDate: '2026-09-10' },
    });
    expect(vi.mocked(http.request).mock.calls.some(([options]) => options.path.endsWith('/apply'))).toBe(
      false,
    );
  });

  it('only exposes trial when at least one enabled rule can execute', async () => {
    const withoutRunnableRules = {
      ...snapshot(),
      rules: snapshot().rules.map((rule) => (rule.editable ? { ...rule, enabled: false } : rule)),
    };
    const http: HttpClient = { request: vi.fn(async () => withoutRunnableRules as never) };
    const wrapper = mountSurface(http);
    await flushPromises();

    expect(action(wrapper, '试算整组规则')).toBeUndefined();
  });

  it('allows trialing an applied snapshot and drops a deferred result after sample input changes', async () => {
    const pendingTrial = deferred<unknown>();
    const http = fakeHttp();
    vi.mocked(http.request).mockImplementation((options) =>
      options.path.endsWith('/trial') ? (pendingTrial.promise as never) : fakeHttp().request(options),
    );
    const wrapper = mountSurface(http);
    await flushPromises();
    await action(wrapper, '试算整组规则').trigger('click');
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
    await flushPromises();
    wrapper
      .findAllComponents({ name: 'UiInput' })
      .find((input) => input.props('type') === 'number')!
      .vm.$emit('update:value', '12');
    pendingTrial.resolve({ preview: {}, values: { quantity: 1 }, changedFields: ['amount'], errors: [] });
    await flushPromises();

    expect(wrapper.find('[data-testid="business-rule-trial"]').exists()).toBe(false);
    expect(vi.mocked(http.request).mock.calls.some(([options]) => options.path.endsWith('/apply'))).toBe(
      false,
    );
  });

  it('requires a system tenant for referenced trials and invalidates a prior tenant result when it changes', async () => {
    const pendingTrial = deferred<unknown>();
    const referencedSnapshot = {
      ...snapshot(),
      rules: [
        {
          ...snapshot().rules[0],
          expression: 'PRESENT({supplierId.title})',
        },
      ],
    };
    const fallback = fakeHttp();
    const http: HttpClient = {
      request: vi.fn((options: HttpRequestOptions) => {
        if (options.path.endsWith('/business-rules')) return Promise.resolve(referencedSnapshot) as never;
        if (options.path === '/iam.tenant/navigator/reference/query')
          return Promise.resolve({
            records: [
              { id: 'tenant-a', title: '租户 A' },
              { id: 'tenant-b', title: '租户 B' },
            ],
          }) as never;
        if (options.path.endsWith('/trial')) return pendingTrial.promise as never;
        return fallback.request(options);
      }) as HttpClient['request'],
    };
    const wrapper = mountSurfaceAsSystemUser(http, { system: true });
    await flushPromises();
    await action(wrapper, '试算整组规则').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('请选择业务租户后再读取引用字段试算。');
    const tenantSelect = wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((select) => select.props('placeholder') === '请选择引用读取所在租户')!;
    tenantSelect.vm.$emit('update:value', 'tenant-a');
    await flushPromises();
    wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
    await flushPromises();
    const trialCall = vi
      .mocked(http.request)
      .mock.calls.find(([options]) => options.path.endsWith('/trial'))![0];
    expect(trialCall.headers).toMatchObject({ 'X-MuYun-Tenant-Id': 'tenant-a' });

    tenantSelect.vm.$emit('update:value', 'tenant-b');
    pendingTrial.resolve({
      preview: {},
      values: { 'supplierId.title': '旧值' },
      changedFields: [],
      errors: [],
    });
    await flushPromises();
    expect(wrapper.find('[data-testid="business-rule-trial"]').exists()).toBe(false);
  });

  it('keeps the tenant boundary when a calculated root supplies a dotted reference', async () => {
    const calculatedReferenceSnapshot = {
      ...snapshot(),
      editableFields: [
        ...snapshot().editableFields,
        { fieldName: 'supplierId', title: '供应商', fieldSpecAlias: 'reference', valueType: 'REFERENCE' },
      ],
      rules: [
        { ...snapshot().rules[0], targetField: 'supplierId', expression: '{quantity}' },
        { ...snapshot().rules[1], expression: 'PRESENT({supplierId.title})' },
      ],
    };
    const fallback = fakeHttp();
    const http: HttpClient = {
      request: vi.fn((options: HttpRequestOptions) => {
        if (options.path.endsWith('/business-rules'))
          return Promise.resolve(calculatedReferenceSnapshot) as never;
        if (options.path === '/iam.tenant/navigator/reference/query')
          return Promise.resolve({ records: [] }) as never;
        return fallback.request(options);
      }) as HttpClient['request'],
    };
    const wrapper = mountSurfaceAsSystemUser(http, { system: true });
    await flushPromises();
    await action(wrapper, '试算整组规则').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('请选择业务租户后再读取引用字段试算。');
    expect(wrapper.text()).not.toContain('请输入引用记录 ID；点路径字段由服务端读取。');
  });

  it('can automatically check and apply removal of the final editable rule', async () => {
    const http: HttpClient = {
      request: vi.fn(async (options: HttpRequestOptions) => {
        if (options.path.endsWith('/preview'))
          return {
            snapshot: snapshot(),
            proposalFingerprint: 'empty-rules',
            executionOrder: [],
            errors: [],
          } as never;
        if (options.path.endsWith('/apply'))
          return { snapshot: { ...snapshot(), rules: [] }, preview: {}, activatedModules: [] } as never;
        return { ...snapshot(), rules: [snapshot().rules[0]] } as never;
      }),
    };
    const wrapper = mountSurface(http);
    await flushPromises();
    await action(wrapper, '删除规则').trigger('click');
    await flushPromises();
    await action(wrapper, '应用更改').trigger('click');
    await flushPromises();
    const previewCall = vi
      .mocked(http.request)
      .mock.calls.find(([options]) => options.path.endsWith('/preview'))![0];
    expect(previewCall.body).toEqual({ rules: [] });
    expect(
      vi.mocked(http.request).mock.calls.find(([options]) => options.path.endsWith('/apply'))![0].body,
    ).toMatchObject({
      rules: [],
      proposalFingerprint: 'empty-rules',
    });
  });

  it('does not apply a preview response that becomes stale after module navigation', async () => {
    let resolvePreview!: (value: unknown) => void;
    const http = fakeHttp();
    vi.mocked(http.request).mockImplementation((options) =>
      options.path.endsWith('/preview')
        ? (new Promise((resolve) => (resolvePreview = resolve)) as never)
        : fakeHttp().request(options),
    );
    const wrapper = mountSurface(http);
    await flushPromises();
    wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.vm.$emit('update:value', '{quantity} * 12');
    await flushPromises();
    await action(wrapper, '应用更改').trigger('click');
    await wrapper.setProps({ moduleAlias: 'education.other' });
    resolvePreview({
      snapshot: snapshot(),
      proposalFingerprint: 'old',
      executionOrder: ['calculation_amount'],
      errors: [],
    });
    await flushPromises();
    expect(vi.mocked(http.request).mock.calls.some(([options]) => options.path.endsWith('/apply'))).toBe(
      false,
    );
  });

  it('keeps an empty expression as text and blocks mutations while apply is pending', async () => {
    const pendingApply = deferred<unknown>();
    const http = fakeHttp();
    vi.mocked(http.request).mockImplementation((options) =>
      options.path.endsWith('/apply') ? (pendingApply.promise as never) : fakeHttp().request(options),
    );
    const wrapper = mountSurface(http);
    await flushPromises();
    const expression = () => wrapper.findAllComponents({ name: 'UiTextArea' })[0]!;
    expression().vm.$emit('update:value', '');
    await flushPromises();
    expect(expression().props('value')).toBe('');
    expression().vm.$emit('update:value', '{quantity} * 12');
    await flushPromises();
    await action(wrapper, '应用更改').trigger('click');
    await flushPromises();
    expression().vm.$emit('update:value', '{quantity} * 13');
    await action(wrapper, '新增规则').trigger('click');
    await flushPromises();
    expect(expression().props('value')).toBe('{quantity} * 12');
    expect(wrapper.text()).not.toContain('validation1');
    pendingApply.resolve({ snapshot: snapshot(), preview: {}, activatedModules: [] });
    await flushPromises();
  });

  it('retains local input and asks for reload when a stale apply is rejected', async () => {
    const http = fakeHttp();
    vi.mocked(http.request).mockImplementation((options) => {
      if (options.path.endsWith('/apply')) return Promise.reject(new Error('stale snapshot')) as never;
      return fakeHttp().request(options);
    });
    const wrapper = mountSurface(http);
    await flushPromises();
    wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.vm.$emit('update:value', '{quantity} * 12');
    await flushPromises();
    await action(wrapper, '应用更改').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('请重新加载后再应用');
    expect(wrapper.findAllComponents({ name: 'UiTextArea' })[0]!.props('value')).toBe('{quantity} * 12');
  });

  it('disables calculation creation when no writable main field exists', async () => {
    const http: HttpClient = { request: vi.fn(async () => snapshot('education.exam', false) as never) };
    const wrapper = mountSurface(http);
    await flushPromises();
    expect(action(wrapper, '新增规则').attributes('title')).toContain('没有可写的主表字段');
  });
});
