import { flushPromises, shallowMount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { configureModuleContext, type HttpClient, type HttpRequestOptions } from '@/web-core';
import MetadataGovernanceSurface from '@/views/MetadataGovernanceSurface.vue';
import { confirmAction } from '@muyun/vue-ui-antdv';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));

const mounted = new Set<ReturnType<typeof shallowMount>>();

afterEach(() => {
  mounted.forEach((wrapper) => wrapper.unmount());
  mounted.clear();
  vi.clearAllMocks();
});

it('keeps main entity capabilities out of the data-model editor', async () => {
  const http = fakeHttp();
  const request = vi.spyOn(http, 'request');
  configureModuleContext({ http });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  expect(wrapper.text()).toContain('＋ 字段');
  expect(wrapper.text()).toContain('＋ 子元数据');
  expect(wrapper.find('[data-testid="capability-checkbox"]').exists()).toBe(false);
  expect(request.mock.calls.map(([options]) => options.path)).not.toContain(
    '/platform.module/education.exam/metadata-model/change-set-preview',
  );
});

it('places metadata sorting in the explorer header as an icon action', async () => {
  configureModuleContext({ http: fakeHttp() });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  expect(wrapper.get('[data-testid="icon-button"]').attributes('title')).toBe('调整排序');
  expect(wrapper.findAll('[data-testid="action-button"]').map((button) => button.text())).not.toContain(
    '调整排序',
  );
});

it.each([
  [true, false, false],
  [false, false, false],
  [true, true, false],
  [true, true, true],
])(
  'saves a drop immediately and restores the correct order (success=%s, systemFields=%s, readFailure=%s)',
  async (success, systemFields, readFailure) => {
    const http = fakeHttp();
    const original = http.request;
    const application = deferred<unknown>();
    let persistedOrder = ['a', 'b', 'c'];
    let committed = false;
    const request = vi.spyOn(http, 'request').mockImplementation((options) => {
      if (options.path.endsWith('change-set-apply')) return application.promise as never;
      if (options.path === '/platform.metadata/meta-main/fields/query') {
        if (readFailure && committed) return Promise.reject(new Error('回读失败')) as never;
        return Promise.resolve({
          records: persistedOrder.map((id, sortOrder) => ({
            id,
            fieldName: id,
            title: id,
            sortOrder,
            fieldOwnership: systemFields && id === 'c' ? 'STANDARD' : 'BUSINESS',
            systemManaged: systemFields && id === 'c',
            fieldForm: 'PHYSICAL',
          })),
          pages: 1,
          totalKnown: true,
        }) as never;
      }
      return original(options);
    });
    configureModuleContext({ http });
    const wrapper = shallowMount(MetadataGovernanceSurface, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: governanceStubs() },
    });
    mounted.add(wrapper);
    await flushPromises();
    if (systemFields) {
      wrapper.findComponent({ name: 'UiSwitch' }).vm.$emit('update:checked', true);
      await flushPromises();
    }
    const tree = () => wrapper.findComponent({ name: 'UiTree' });
    const fields = () =>
      tree()
        .props('nodes')[0]
        .children.filter((node: { modelKind: string }) => node.modelKind === 'FIELD');
    await wrapper.get('[data-testid="icon-button"]').trigger('click');
    expect(wrapper.text()).not.toContain('保存排序');
    const drop = () =>
      tree().vm.$emit('drop', {
        operation: 'move',
        source: { node: fields()[2] },
        target: { kind: 'node', node: fields()[0], position: 'before' },
      });
    const treeInstance = tree().vm;
    request.mockClear();
    drop();
    await flushPromises();
    expect(fields().map((node: { fieldId: string }) => node.fieldId)).toEqual(['c', 'a', 'b']);
    expect(confirmAction).not.toHaveBeenCalled();
    const applications = () =>
      request.mock.calls.filter(([options]) => options.path.endsWith('change-set-apply'));
    expect(applications()).toHaveLength(1);
    expect(applications()[0]![0].body).toMatchObject({
      proposal: {
        relationDrafts: [],
        relationOrders: [],
        fieldOrders: [{ relationId: 'rel-main', fieldIds: ['c', 'a', 'b'] }],
      },
    });
    drop();
    await flushPromises();
    expect(applications()).toHaveLength(1);
    if (success) {
      persistedOrder = ['c', 'a', 'b'];
      committed = true;
      application.resolve({});
    } else application.reject(new Error('排序保存失败'));
    await flushPromises();
    expect(fields().map((node: { fieldId: string }) => node.fieldId)).toEqual(persistedOrder);
    expect(tree().vm).toBe(treeInstance);
    const reads = request.mock.calls
      .map(([options]) => options.path)
      .filter((path) => !path.endsWith('change-set-preview') && !path.endsWith('change-set-apply'));
    expect(reads).toEqual(success ? ['/platform.metadata/meta-main/fields/query'] : []);
    if (success) {
      persistedOrder = ['b', 'c', 'a'];
      drop();
      await flushPromises();
      expect(applications()).toHaveLength(2);
      expect(applications()[1]![0].body).toMatchObject({
        proposal: {
          fieldOrders: [{ relationId: 'rel-main', fieldIds: persistedOrder }],
        },
      });
      expect(tree().vm).toBe(treeInstance);
    }
    expect(wrapper.get('[data-testid="icon-button"]').attributes('title')).toBe('结束排序');
    await wrapper.get('[data-testid="icon-button"]').trigger('click');
    expect(wrapper.get('[data-testid="icon-button"]').attributes('title')).toBe('调整排序');
  },
);

it('keeps actions available in sorting mode and pauses dragging in the editor', async () => {
  configureModuleContext({ http: fakeHttp() });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await wrapper.get('[data-testid="icon-button"]').trigger('click');
  expect(wrapper.text()).toContain('＋ 字段');
  expect(wrapper.text()).toContain('＋ 子元数据');
  expect(wrapper.text()).toContain('删除');
  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((button) => button.text() === '＋ 字段')!
    .trigger('click');
  expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('editing')).toBe(true);
  expect(wrapper.findComponent({ name: 'UiTree' }).attributes('draggable')).toBe('false');
  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((button) => button.text() === '取消')!
    .trigger('click');
  expect(wrapper.get('[data-testid="icon-button"]').attributes('title')).toBe('结束排序');
  expect(wrapper.findComponent({ name: 'UiTree' }).attributes('draggable')).toBe('true');
});

it('retains tree instance and existing nodes while refreshing after changes', async () => {
  const http = fakeHttp();
  const original = http.request;
  configureModuleContext({ http });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  const tree = wrapper.findComponent({ name: 'UiTree' });
  const pending = deferred<unknown>();
  vi.spyOn(http, 'request').mockImplementation((options) =>
    options.path.endsWith('/metadata-relations/query') ? (pending.promise as never) : original(options),
  );
  wrapper.findComponent({ name: 'RecordExplorerPanel' }).vm.$emit('refresh');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiTree' }).vm).toBe(tree.vm);
  expect(tree.props('nodes')[0].children[0].fieldId).toBe('title');
  pending.resolve(
    responseFor({ path: '/platform.module/education.exam/metadata-relations/query', method: 'POST' }),
  );
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiTree' }).vm).toBe(tree.vm);
});

it('removes a child without clearing the surviving entity fields', async () => {
  vi.mocked(confirmAction).mockResolvedValue(true);
  const http = fakeHttp();
  const original = http.request;
  vi.spyOn(http, 'request').mockImplementation((options) =>
    options.method === 'DELETE' ? (Promise.resolve({}) as never) : original(options),
  );
  configureModuleContext({ http });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  const tree = wrapper.findComponent({ name: 'UiTree' });
  tree.vm.$emit(
    'select',
    tree.props('nodes')[0].children.find((node: { modelKind: string }) => node.modelKind === 'METADATA'),
  );
  await flushPromises();
  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((button) => button.text() === '删除')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiTree' }).vm).toBe(tree.vm);
  expect(tree.props('nodes')[0].children.map((node: { fieldId: string }) => node.fieldId)).toEqual(['title']);
});

it('keeps the field editor open while save confirmation is pending', async () => {
  const confirmation = deferred<boolean>();
  vi.mocked(confirmAction).mockReturnValue(confirmation.promise);
  configureModuleContext({ http: fakeHttp() });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  await wrapper.get('[data-testid="model-tree"]').trigger('click');
  await flushPromises();
  const edit = wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === '编辑');
  await edit?.trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('存储字段规格');

  const save = wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === '保存');
  await save?.trigger('click');
  await flushPromises();

  expect(vi.mocked(confirmAction)).toHaveBeenCalledTimes(1);
  expect(wrapper.text()).toContain('存储字段规格');
  confirmation.resolve(false);
  await flushPromises();
  expect(wrapper.text()).toContain('存储字段规格');
});

it('keeps unconfirmed field changes out of the tree while sorting is enabled', async () => {
  vi.mocked(confirmAction).mockResolvedValue(false);
  configureModuleContext({ http: fakeHttp() });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await wrapper.get('[data-testid="icon-button"]').trigger('click');
  await wrapper.get('[data-testid="model-tree"]').trigger('click');
  await flushPromises();
  const action = (label: string) =>
    wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === label)!;
  await action('编辑').trigger('click');
  const input = wrapper
    .findAllComponents({ name: 'UiInput' })
    .find((field) => field.props('value') === '考试名称')!;
  input.vm.$emit('update:value', '未提交名称');
  await action('保存').trigger('click');
  await flushPromises();
  expect(confirmAction).toHaveBeenCalled();
  const node = wrapper.findComponent({ name: 'UiTree' }).props('nodes')[0].children[0];
  expect(JSON.stringify(node)).toContain('考试名称');
  expect(JSON.stringify(node)).not.toContain('未提交名称');
});

it('keeps the edited field selected after a successful save refresh', async () => {
  vi.mocked(confirmAction).mockResolvedValue(true);
  configureModuleContext({ http: fakeHttp() });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await flushPromises();

  await wrapper.get('[data-testid="model-tree"]').trigger('click');
  await flushPromises();
  const edit = wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === '编辑');
  await edit?.trigger('click');
  await flushPromises();
  const save = wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === '保存');
  await save?.trigger('click');
  await flushPromises();
  await flushPromises();

  expect(wrapper.text()).toContain('编辑');
  expect(wrapper.text()).not.toContain('＋ 字段');
});

it('creates a child in simple mode from its title and keeps a failed draft locked for retry', async () => {
  const creation = deferred<unknown>();
  const http = fakeHttp();
  const originalRequest = http.request;
  const request = vi
    .spyOn(http, 'request')
    .mockImplementation((options) =>
      options.path.endsWith('/create-child-metadata')
        ? (creation.promise as never)
        : originalRequest(options),
    );
  configureModuleContext({ http });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  const action = (title: string) =>
    wrapper.findAll('[data-testid="action-button"]').find((button) => button.text() === title)!;
  await action('＋ 子元数据').trigger('click');
  await action('创建').trigger('click');
  expect(request.mock.calls.some(([options]) => options.path.endsWith('/create-child-metadata'))).toBe(false);
  expect(wrapper.text()).toContain('请填写子元数据名称');
  await wrapper.findComponent({ name: 'UiInput' }).vm.$emit('update:value', '参考学生');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('editing')).toBe(true);
  expect(wrapper.findComponent({ name: 'RecordDetailPanel' }).props('title')).toBe('新增子元数据');
  expect(wrapper.text()).toContain('can_kao_xue_sheng');
  await action('创建').trigger('click');
  expect(
    request.mock.calls.find(([options]) => options.path.endsWith('/create-child-metadata'))?.[0].body,
  ).toMatchObject({ alias: 'can_kao_xue_sheng', title: '参考学生' });
  creation.reject(new Error('标识已存在'));
  await flushPromises();
  expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('editing')).toBe(true);
  expect(wrapper.text()).toContain('can_kao_xue_sheng');
  await action('取消').trigger('click');
  expect(wrapper.findComponent({ name: 'ManagementWorkspace' }).props('editing')).toBe(false);
});

it('preserves an explicitly edited child alias across title and mode changes', async () => {
  configureModuleContext({ http: fakeHttp() });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await wrapper
    .findAll('[data-testid="action-button"]')
    .find((button) => button.text() === '＋ 子元数据')!
    .trigger('click');
  wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'ADVANCED');
  await flushPromises();
  const inputs = wrapper.findAllComponents({ name: 'UiInput' });
  inputs[0]!.vm.$emit('update:value', 'exam_students');
  inputs[1]!.vm.$emit('update:value', '参考学生');
  await flushPromises();
  wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'SIMPLE');
  await flushPromises();
  expect(wrapper.text()).toContain('exam_students');
});

function governanceStubs() {
  return {
    RecordFieldLabel: false,
    ManagementWorkspace: {
      name: 'ManagementWorkspace',
      props: ['editing'],
      template: '<section><slot /></section>',
    },
    ManagementExplorerColumn: { template: '<section><slot /></section>' },
    RecordExplorerPanel: {
      name: 'RecordExplorerPanel',
      template:
        '<section data-testid="explorer"><slot name="actions" /><slot /><slot name="footer" /></section>',
    },
    RecordDetailPanel: {
      name: 'RecordDetailPanel',
      props: ['title', 'subtitle'],
      template: '<section><slot name="status" /><slot /><slot name="actions" /></section>',
    },
    RecordFormGrid: { template: '<form><slot /></form>' },
    UiTree: {
      name: 'UiTree',
      props: { nodes: Array },
      emits: ['select', 'drop'],
      template:
        '<button data-testid="model-tree" @click="$emit(\'select\', nodes[0]?.children?.[0])"><slot /></button>',
    },
    UiActionButton: {
      emits: ['click'],
      template: '<button data-testid="action-button" @click="$emit(\'click\')"><slot /></button>',
    },
    UiButton: {
      props: { title: String },
      emits: ['click'],
      template:
        '<button data-testid="icon-button" :title="title" @click="$emit(\'click\')"><slot /></button>',
    },
  };
}

function fakeHttp(): HttpClient {
  return {
    request: <T>(request: HttpRequestOptions) => Promise.resolve(responseFor(request) as T),
  };
}

function responseFor(options: HttpRequestOptions) {
  if (options.path === '/platform.module/platform.module/context')
    return { moduleAlias: 'platform.module', capabilities: [], actions: [] };
  if (options.path === '/platform.module/education.exam/metadata-relations/query')
    return {
      records: [
        { id: 'rel-main', metadataId: 'meta-main', relationRole: 'MAIN' },
        { id: 'rel-child', metadataId: 'meta-child', relationRole: 'CHILD', parentMetadataId: 'meta-main' },
      ],
      pages: 1,
      totalKnown: true,
    };
  if (options.path === '/platform.metadata/view/meta-main')
    return { id: 'meta-main', alias: 'exam', title: '考试', version: 3 };
  if (options.path === '/platform.metadata/view/meta-child')
    return { id: 'meta-child', alias: 'exam_student', title: '参考学生', version: 2 };
  if (options.path === '/platform.metadata/meta-main/fields/query')
    return {
      records: [
        {
          id: 'title',
          fieldName: 'title',
          columnName: 'title',
          fieldSpecAlias: 'string',
          title: '考试名称',
          fieldOwnership: 'BUSINESS',
          fieldForm: 'PHYSICAL',
        },
      ],
      pages: 1,
      totalKnown: true,
    };
  if (options.path === '/platform.metadata/meta-child/fields/query')
    return {
      records: [
        {
          id: 'student',
          fieldName: 'studentId',
          title: '学生',
          fieldOwnership: 'BUSINESS',
          fieldForm: 'PHYSICAL',
        },
      ],
      pages: 1,
      totalKnown: true,
    };
  if (options.path.endsWith('/field-properties')) return [];
  if (options.path.endsWith('/record-count')) return { relationId: 'rel-main', recordCount: 0 };
  if (options.path.endsWith('/capabilities'))
    return {
      systemFields: [],
      capabilities: [
        {
          capability: 'TREE',
          enabled: false,
          configurable: true,
          reason: options.path.includes('rel-child')
            ? '子元数据不能启用该模块保留能力。'
            : '可由主元数据声明。',
          fieldContributions: [],
          defaultKind: 'RUNTIME',
          defaultDescription: '',
        },
      ],
    };
  if (options.path === '/platform.field_spec/query') return { records: [], pages: 1, totalKnown: true };
  if (options.path.endsWith('/metadata-model/change-set-preview'))
    return {
      errors: [],
      fieldImpacts: [{ operation: 'UPDATE', fieldName: 'title', columnName: 'title' }],
      schemaImpacts: [],
      orderImpacts: [],
      proposalFingerprint: 'fingerprint',
    };
  if (options.path.endsWith('/metadata-model/change-set-apply')) return {};
  throw new Error(`Unexpected request: ${options.path}`);
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((next, fail) => {
    reject = fail;
    resolve = next;
  });
  return { promise, resolve, reject };
}
