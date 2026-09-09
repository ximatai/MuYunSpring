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
    .find((button) => button.text() === '普通字段')!
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

it('creates a module reference in simple mode and preserves advanced settings across mode switches', async () => {
  const http = fakeHttp();
  const request = vi.spyOn(http, 'request');
  vi.mocked(confirmAction).mockResolvedValue(false);
  configureModuleContext({ http });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '模块引用')!
    .trigger('click');
  await flushPromises();
  const field = (label: string) => wrapper.findAll('label').find((item) => item.text().startsWith(label))!;
  expect(wrapper.text()).not.toContain('存储字段规格');
  expect(wrapper.text()).not.toContain('匹配键字段');
  field('目标模块').findComponent({ name: 'UiSelect' }).vm.$emit('update:value', 'iam.user');
  await flushPromises();
  expect(field('显示名称').findComponent({ name: 'UiInput' }).props('value')).toBe('用户');
  field('显示名称').findComponent({ name: 'UiInput' }).vm.$emit('update:value', '负责人');
  wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'ADVANCED');
  await flushPromises();
  field('被引用记录删除时').findComponent({ name: 'UiSelect' }).vm.$emit('update:value', 'RESTRICT');
  wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'SIMPLE');
  await flushPromises();
  expect(field('目标模块').findComponent({ name: 'UiSelect' }).props('value')).toBe('iam.user');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '保存')!
    .trigger('click');
  await flushPromises();
  const preview = request.mock.calls.find(([options]) => options.path.endsWith('change-set-preview'));
  expect(JSON.stringify(preview?.[0].body)).toContain('refFuZeRenId');
  expect(JSON.stringify(preview?.[0].body)).toContain('RESTRICT');
  expect(JSON.stringify(preview?.[0].body)).toContain('displayName');
  expect(request.mock.calls.some(([options]) => options.path.endsWith('change-set-apply'))).toBe(false);
});

it('selects a dictionary in simple mode without requiring application aliases', async () => {
  configureModuleContext({ http: fakeHttp() });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '数据字典')!
    .trigger('click');
  await flushPromises();
  const select = wrapper.findComponent({ name: 'UiSelect' });
  expect(select.props('options')).toEqual([{ value: 'education.status', label: '状态 · education.status' }]);
  select.vm.$emit('update:value', 'education.status');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiInput' }).props('value')).toBe('状态');
  expect(wrapper.text()).not.toContain('存储字段规格');
  wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'ADVANCED');
  await flushPromises();
  expect(wrapper.findAllComponents({ name: 'UiSelect' })[0]!.props('value')).toBe('education.status');
});

it.each(['MODULE_REFERENCE', 'DICTIONARY'])(
  'links suggested names to changing targets while preserving custom names (%s)',
  async (kind) => {
    const http = fakeHttp();
    const original = http.request;
    vi.spyOn(http, 'request').mockImplementation((options) => {
      if (options.path.endsWith('/reference-target-modules'))
        return Promise.resolve([
          { alias: 'iam.user', title: '用户' },
          { alias: 'iam.employee', title: '职员' },
        ]) as never;
      if (options.path === '/platform.dictionary_category/query')
        return Promise.resolve({
          records: [
            { alias: 'zone', applicationAlias: 'platform', title: '时区', categoryKind: 'DICTIONARY' },
            { alias: 'gender', applicationAlias: 'iam', title: '性别', categoryKind: 'DICTIONARY' },
          ],
          pages: 1,
          totalKnown: true,
        }) as never;
      return original(options);
    });
    configureModuleContext({ http });
    const wrapper = shallowMount(MetadataGovernanceSurface, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: governanceStubs() },
    });
    mounted.add(wrapper);
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === (kind === 'DICTIONARY' ? '数据字典' : '模块引用'))!
      .trigger('click');
    await flushPromises();
    const target = () => wrapper.findComponent({ name: 'UiSelect' });
    const title = () =>
      wrapper
        .findAll('label')
        .find((item) => item.text() === '显示名称')!
        .findComponent({ name: 'UiInput' });
    const first = kind === 'DICTIONARY' ? 'platform.zone' : 'iam.user';
    const second = kind === 'DICTIONARY' ? 'iam.gender' : 'iam.employee';
    target().vm.$emit('update:value', first);
    await flushPromises();
    expect(title().props('value')).toBe(kind === 'DICTIONARY' ? '时区' : '用户');
    target().vm.$emit('update:value', second);
    await flushPromises();
    expect(title().props('value')).toBe(kind === 'DICTIONARY' ? '性别' : '职员');
    target().vm.$emit('update:value', null);
    await flushPromises();
    expect(title().props('value')).toBe('');
    target().vm.$emit('update:value', first);
    await flushPromises();
    title().vm.$emit('update:value', '负责人');
    target().vm.$emit('update:value', second);
    await flushPromises();
    expect(title().props('value')).toBe('负责人');
    wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'ADVANCED');
    await flushPromises();
    expect(
      wrapper
        .findAll('label')
        .find((item) => item.text().startsWith('字段名称'))!
        .findComponent({ name: 'UiInput' })
        .props('value'),
    ).toBe(kind === 'DICTIONARY' ? 'dictFuZeRen' : 'refFuZeRenId');
    title().vm.$emit('update:value', '');
    await flushPromises();
    target().vm.$emit('update:value', first);
    await flushPromises();
    expect(title().props('value')).toBe(kind === 'DICTIONARY' ? '时区' : '用户');
    const technical = (label: string) =>
      wrapper
        .findAll('label')
        .find((item) => item.text().startsWith(label))!
        .findComponent({ name: 'UiInput' });
    technical('字段名称').vm.$emit('update:value', 'customRole');
    technical('物理列名').vm.$emit('update:value', 'custom_column');
    title().vm.$emit('update:value', '新的名称');
    await flushPromises();
    expect(technical('字段名称').props('value')).toBe('customRole');
    expect(technical('物理列名').props('value')).toBe('custom_column');
  },
);

it('keeps an existing reference visible in simple mode and blocks invalid target settings without rewriting them', async () => {
  const http = fakeHttp();
  const original = http.request;
  const request = vi.spyOn(http, 'request').mockImplementation((options) => {
    if (options.path.endsWith('/field-properties'))
      return Promise.resolve([
        {
          fieldId: 'title',
          fieldName: 'title',
          kind: 'MODULE_REFERENCE',
          bindingVersion: 4,
          reference: {
            targetModuleAlias: 'iam.user',
            targetKeyField: 'id',
            targetLabelField: 'retiredLabel',
            cardinality: 'ONE',
            targetUnavailablePolicy: 'RESTRICT',
            projectionMappings: ['displayName:userName'],
          },
        },
      ]) as never;
    return original(options);
  });
  configureModuleContext({ http });
  const wrapper = shallowMount(MetadataGovernanceSurface, {
    props: { moduleAlias: 'education.exam' },
    global: { stubs: governanceStubs() },
  });
  mounted.add(wrapper);
  await flushPromises();
  await wrapper.get('[data-testid="model-tree"]').trigger('click');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '编辑')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.findComponent({ name: 'UiSelect' }).props('value')).toBe('iam.user');
  expect(wrapper.text()).toContain('retiredLabel');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '保存')!
    .trigger('click');
  await flushPromises();
  expect(request.mock.calls.some(([options]) => options.path.endsWith('change-set-preview'))).toBe(false);
  wrapper.findComponent({ name: 'UiRadioGroup' }).vm.$emit('update:value', 'ADVANCED');
  await flushPromises();
  const field = (label: string) => wrapper.findAll('label').find((item) => item.text().startsWith(label))!;
  expect(field('目标展示字段').findComponent({ name: 'UiSelect' }).props('value')).toBe('retiredLabel');
  expect(field('被引用记录删除时').findComponent({ name: 'UiSelect' }).props('value')).toBe('RESTRICT');
  expect(wrapper.findComponent({ name: 'UiTextArea' }).props('value')).toBe('displayName:userName');
});

function governanceStubs() {
  return {
    RecordFieldLabel: false,
    UiDropdown: {
      props: ['items'],
      emits: ['select'],
      template: `<div><slot :toggle="() => {}" /><button v-for="item in items" :key="item.key" data-testid="action-button" @click="$emit('select', item.key)">{{ item.title }}</button></div>`,
    },
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
  if (options.path.endsWith('/reference-target-modules')) return [{ alias: 'iam.user', title: '用户' }];
  if (options.path.includes('/reference-target-field-catalog?'))
    return {
      targetModuleAlias: 'iam.user',
      keyFields: [{ fieldName: 'id', defaultField: true, selectable: true }],
      labelFields: [{ fieldName: 'displayName', defaultField: true, selectable: true }],
    };
  if (options.path === '/platform.dictionary_category/query')
    return {
      records: [
        { id: 'folder', categoryKind: 'FOLDER', title: '目录' },
        {
          id: 'tenant-status',
          categoryKind: 'DICTIONARY',
          applicationAlias: 'education',
          alias: 'status',
          title: '状态',
        },
        {
          id: 'status',
          categoryKind: 'DICTIONARY',
          applicationAlias: 'education',
          alias: 'status',
          title: '状态',
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
