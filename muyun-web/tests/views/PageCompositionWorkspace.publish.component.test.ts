import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { configureModuleContext, type HttpClient, type HttpRequestOptions } from '@/web-core';
import PageCompositionDescriptorPreview from '@/views/PageCompositionDescriptorPreview.vue';
import PageCompositionWorkspace from '@/views/PageCompositionWorkspace.vue';
import PageCompositionTree from '@/views/PageCompositionTree.vue';
import { confirmAction } from '@muyun/vue-ui-antdv';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));

describe('PageCompositionWorkspace publication flow', () => {
  afterEach(() => {
    vi.clearAllMocks();
    vi.unstubAllGlobals();
  });

  it('saves the same local tree before publishing and creates the next draft from that snapshot', async () => {
    const requests: HttpRequestOptions[] = [];
    const http = publicationFlowHttp(requests);
    configureModuleContext({ http });
    vi.mocked(confirmAction).mockResolvedValue(true);

    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam', moduleTitle: '考试管理' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const publishButton = wrapper
      .findAll('[data-testid="publish-button"]')
      .find((button) => button.text().includes('发布草稿'));
    expect(publishButton?.exists()).toBe(true);

    await publishButton?.trigger('click');
    await vi.waitFor(() => {
      expect(
        requests.some(
          (request) => request.path === '/platform.presentation-variant/variant-1/revisions/insert',
        ),
      ).toBe(true);
    });

    const flow = requests.filter((request) =>
      [
        '/platform.presentation-variant/variant-1/revisions/update/revision-1',
        '/platform.presentation_publish/revisions/revision-1/publish',
        '/platform.presentation-variant/variant-1/revisions/insert',
      ].includes(request.path),
    );
    expect(flow.map((request) => request.path)).toEqual([
      '/platform.presentation-variant/variant-1/revisions/update/revision-1',
      '/platform.presentation_publish/revisions/revision-1/publish',
      '/platform.presentation-variant/variant-1/revisions/insert',
    ]);

    const saveRequest = flow[0];
    const followUpRequest = flow[2];
    expect(saveRequest.method).toBe('POST');
    expect(followUpRequest.method).toBe('POST');
    expect((saveRequest.body as { uiTreeJson: string }).uiTreeJson).toBe(
      (followUpRequest.body as { uiTreeJson: string }).uiTreeJson,
    );
    expect((followUpRequest.body as { status: string }).status).toBe('draft');
    expect(vi.mocked(confirmAction)).toHaveBeenCalledTimes(1);
  });

  it('ignores an older module load after the workspace switches modules', async () => {
    const requests: HttpRequestOptions[] = [];
    const oldRelations = deferred<unknown>();
    configureModuleContext({
      http: {
        request: <T>(options: HttpRequestOptions) => {
          requests.push(options);
          if (options.path === '/platform.module/platform.module/context')
            return Promise.resolve({ moduleAlias: 'platform.module', capabilities: [], actions: [] } as T);
          if (options.path === '/platform.module/education.old/metadata-relations/query')
            return oldRelations.promise as Promise<T>;
          if (options.path === '/platform.module/education.new/metadata-relations/query')
            return Promise.resolve(
              page([
                {
                  id: 'relation-new',
                  metadataId: 'metadata-new',
                  relationAlias: '新主实体',
                  relationRole: 'main',
                },
              ]) as T,
            );
          if (options.path === '/platform.metadata/metadata-new/fields/query')
            return Promise.resolve(page([{ id: 'field-new', fieldName: 'title', title: '新字段' }]) as T);
          if (options.path === '/platform.module/education.new/pages/query')
            return Promise.resolve(page([]) as T);
          throw new Error(`Unexpected request: ${options.method ?? 'GET'} ${options.path}`);
        },
      },
    });

    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.old' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await wrapper.setProps({ moduleAlias: 'education.new' });
    await flushPromises();
    oldRelations.resolve(
      page([
        { id: 'relation-old', metadataId: 'metadata-old', relationAlias: '旧主实体', relationRole: 'main' },
      ]),
    );
    await flushPromises();

    expect(requests.some((request) => request.path === '/platform.module/education.old/pages/query')).toBe(
      false,
    );
    expect(requests.some((request) => request.path === '/platform.module/education.new/pages/query')).toBe(
      true,
    );
    expect(wrapper.text()).toContain('新主实体');
    expect(wrapper.text()).not.toContain('旧主实体');
  });

  it('shows known child metadata as a detail association source', async () => {
    configureModuleContext({ http: publicationFlowHttp([]) });

    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('参考学生');
    expect(wrapper.text()).toContain('子实体 · 拖入详情创建关联列表');
    expect(wrapper.text()).toContain('学生姓名');
  });

  it('keeps runtime-reserved metadata out of page composition sources', async () => {
    configureModuleContext({
      http: publicationFlowHttp([], initialTree(), [
        {
          id: 'field-id',
          fieldName: 'id',
          title: 'ID',
          fieldOwnership: 'STANDARD',
          systemManaged: true,
        },
        {
          id: 'field-tenant',
          fieldName: 'tenantId',
          title: '租户',
          fieldOwnership: 'STANDARD',
          systemManaged: true,
        },
        {
          id: 'field-enabled',
          fieldName: 'enabled',
          title: '启用',
          fieldOwnership: 'STANDARD',
          systemManaged: true,
        },
        {
          id: 'field-title',
          fieldName: 'title',
          title: '考试名称',
          fieldOwnership: 'BUSINESS',
          fieldForm: 'PHYSICAL',
        },
      ]),
    });

    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const metadataTree = wrapper.findComponent({ name: 'UiTree' });
    const nodes = metadataTree.props('nodes');
    expect(treeNode(nodes, 'metadata:field:field-id')).toBeUndefined();
    expect(treeNode(nodes, 'metadata:field:field-tenant')).toBeUndefined();
    expect(treeNode(nodes, 'metadata:field:field-enabled')).toBeDefined();
    expect(treeNode(nodes, 'metadata:field:field-title')).toBeDefined();
  });

  it('accepts a metadata field through the dedicated composer-tree drop contract', async () => {
    configureModuleContext({ http: publicationFlowHttp([]) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const metadataTree = wrapper.findComponent({ name: 'UiTree' });
    const pageTree = wrapper.findComponent(PageCompositionTree);
    const metadataField = treeNode(metadataTree.props('nodes'), 'metadata:field:field-title');
    expect(metadataField).toBeDefined();
    expect(metadataTree.props('dragOperations')).toEqual(['copy']);

    pageTree.vm.$emit('metadata-drop', { kind: 'list' }, metadataDrop());
    await flushPromises();

    expect(pageTree.props('listFields')).toMatchObject([{ id: 'field-title', title: '考试名称' }]);
  });

  it.each(['list', 'form', 'group'] as const)(
    'inserts and reorders metadata fields at the indicated %s position',
    async (kind) => {
      configureModuleContext({
        http: publicationFlowHttp(
          [],
          JSON.stringify({
            template: 'management',
            templateVersion: 1,
            nodes: [
              {
                slot: kind === 'list' ? 'list' : 'form',
                fields: kind === 'group' ? [] : ['other'],
                ...(kind === 'group'
                  ? { groups: [{ group: 'target', title: '目标分组', fields: ['other'] }] }
                  : {}),
              },
            ],
          }),
          [
            {
              id: 'field-title',
              fieldName: 'title',
              title: '考试名称',
              fieldOwnership: 'BUSINESS',
              fieldForm: 'PHYSICAL',
            },
            {
              id: 'field-other',
              fieldName: 'other',
              title: '其他字段',
              fieldOwnership: 'BUSINESS',
              fieldForm: 'PHYSICAL',
            },
          ],
        ),
      });
      const wrapper = mount(PageCompositionWorkspace, {
        props: { moduleAlias: 'education.exam' },
        global: { stubs: workspaceStubs() },
      });
      await flushPromises();
      await flushPromises();
      const pageTree = wrapper.findComponent(PageCompositionTree);
      const target = kind === 'group' ? { kind, groupId: 'target' } : { kind };
      const fields = () =>
        kind === 'group'
          ? pageTree.props('formGroups')[0].fields
          : pageTree.props(kind === 'list' ? 'listFields' : 'formFields');

      pageTree.vm.$emit('metadata-drop', { ...target, index: 0 }, metadataDrop());
      await flushPromises();
      expect(fields().map((field: { id: string }) => field.id)).toEqual(['field-title', 'field-other']);

      pageTree.vm.$emit('metadata-drop', { ...target, index: 1 }, metadataDrop());
      await flushPromises();
      expect(fields().map((field: { id: string }) => field.id)).toEqual(['field-other', 'field-title']);
      wrapper.unmount();
    },
  );

  it('rejects missing and malformed drops even after a prior metadata drag', async () => {
    configureModuleContext({ http: publicationFlowHttp([]) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();
    const metadataTree = wrapper.findComponent({ name: 'UiTree' });
    const pageTree = wrapper.findComponent(PageCompositionTree);
    metadataTree.vm.$emit('drag-start', {
      node: treeNode(metadataTree.props('nodes'), 'metadata:field:field-title'),
    });
    for (const dataTransfer of [
      undefined,
      { getData: () => '{' },
      { getData: () => '{"kind":"field","fieldId":{}}' },
    ]) {
      pageTree.vm.$emit('metadata-drop', { kind: 'list' }, { dataTransfer });
      await flushPromises();
      expect(pageTree.props('listFields')).toEqual([]);
    }
    wrapper.unmount();
  });

  it('accepts a metadata field dropped directly onto the active preview and updates the draft slot', async () => {
    configureModuleContext({ http: publicationFlowHttp([]) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const metadataTree = wrapper.findComponent({ name: 'UiTree' });
    await vi.waitFor(() =>
      expect(wrapper.findComponent(PageCompositionDescriptorPreview).exists()).toBe(true),
    );
    const preview = wrapper.findComponent(PageCompositionDescriptorPreview);
    const metadataField = treeNode(metadataTree.props('nodes'), 'metadata:field:field-title');
    expect(metadataField).toBeDefined();
    expect(preview.exists()).toBe(true);

    preview.vm.$emit('metadata-drop', 'list', metadataDrop());
    await flushPromises();

    expect(wrapper.findComponent(PageCompositionTree).props('listFields')).toMatchObject([
      { id: 'field-title', title: '考试名称' },
    ]);
    expect(wrapper.text()).toContain('列表预览');
  });

  it('repositions an already placed form field when dropped onto another group', async () => {
    configureModuleContext({
      http: publicationFlowHttp(
        [],
        JSON.stringify({
          template: 'management',
          templateVersion: 1,
          nodes: [
            {
              slot: 'form',
              title: '详情 / 表单',
              fields: [],
              groups: [
                { group: 'source', title: '来源分组', fields: ['title'] },
                { group: 'target', title: '目标分组', fields: [] },
              ],
            },
          ],
        }),
      ),
    });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const metadataTree = wrapper.findComponent({ name: 'UiTree' });
    const pageTree = wrapper.findComponent(PageCompositionTree);
    const metadataField = treeNode(metadataTree.props('nodes'), 'metadata:field:field-title');
    expect(metadataField).toBeDefined();

    pageTree.vm.$emit('metadata-drop', { kind: 'group', groupId: 'target' }, metadataDrop());
    await flushPromises();

    expect(pageTree.props('formGroups')).toMatchObject([
      { id: 'source', fields: [] },
      { id: 'target', fields: [{ id: 'field-title' }] },
    ]);
  });

  it('removes a grouped field through the toolbar and reports a removal', async () => {
    configureModuleContext({
      http: publicationFlowHttp(
        [],
        JSON.stringify({
          template: 'management',
          templateVersion: 1,
          nodes: [
            { slot: 'list', fields: ['title'] },
            { slot: 'form', fields: [], groups: [{ group: 'target', title: '目标分组', fields: ['title'] }] },
          ],
        }),
      ),
    });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();
    const tree = wrapper.findComponent(PageCompositionTree);
    tree.vm.$emit('select', 'ui:group-field:form:target:field-title');
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '移除')!
      .trigger('click');
    await flushPromises();

    expect(tree.props('formGroups')).toMatchObject([{ id: 'target', fields: [] }]);
    expect(tree.props('formFields')).toEqual([]);
    expect(tree.props('listFields')).toMatchObject([{ id: 'field-title' }]);
    expect(wrapper.text()).toContain('移除 1 个字段');
    expect(wrapper.text()).not.toContain('新增 1 个字段');
    wrapper.unmount();
  });

  it('sends the dropped draft to the live preview resolver', async () => {
    const requests: HttpRequestOptions[] = [];
    configureModuleContext({ http: publicationFlowHttp(requests) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const metadataTree = wrapper.findComponent({ name: 'UiTree' });
    await vi.waitFor(() =>
      expect(wrapper.findComponent(PageCompositionDescriptorPreview).exists()).toBe(true),
    );
    const preview = wrapper.findComponent(PageCompositionDescriptorPreview);
    const metadataField = treeNode(metadataTree.props('nodes'), 'metadata:field:field-title');
    expect(metadataField).toBeDefined();
    const before = requests.filter((request) => request.path.endsWith('/preview')).length;

    preview.vm.$emit('metadata-drop', 'list', metadataDrop());

    await vi.waitFor(
      () => {
        const previewRequests = requests.filter((request) => request.path.endsWith('/preview'));
        expect(previewRequests.length).toBeGreaterThan(before);
        expect(JSON.stringify(previewRequests.at(-1)?.body)).toContain('title');
      },
      { timeout: 1000 },
    );
  });

  it('rehydrates persisted page structure into the editor tree and preview state', async () => {
    const requests: HttpRequestOptions[] = [];
    configureModuleContext({
      http: publicationFlowHttp(
        requests,
        JSON.stringify({
          template: 'management',
          templateVersion: 1,
          props: { list: { searchPlaceholder: '搜索考试标题' } },
          nodes: [
            {
              slot: 'list',
              title: '列表',
              fields: [{ field: 'title', props: { label: '考试标题', width: '180px' } }],
            },
            {
              slot: 'form',
              title: '详情 / 表单',
              fields: [],
              groups: [{ group: 'basic', title: '基础信息', fields: ['title'] }],
            },
          ],
        }),
      ),
    });

    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const pageTree = wrapper.findComponent(PageCompositionTree);
    expect(pageTree.props('listFields')).toMatchObject([
      { id: 'field-title', title: '考试名称', properties: { label: '考试标题', width: '180px' } },
    ]);
    expect(pageTree.props('formGroups')).toMatchObject([
      { id: 'basic', groupCode: 'basic', title: '基础信息', fields: [{ id: 'field-title' }] },
    ]);
    await vi.waitFor(() =>
      expect(
        requests.some(
          (request) =>
            request.path === '/platform.presentation-variant/variant-1/revisions/revision-1/preview' &&
            JSON.stringify(request.body).includes('搜索考试标题'),
        ),
      ).toBe(true),
    );
  });

  it('selects a grouped field when the live preview reports its runtime field name', async () => {
    configureModuleContext({
      http: publicationFlowHttp(
        [],
        JSON.stringify({
          template: 'management',
          templateVersion: 1,
          nodes: [
            {
              slot: 'form',
              title: '详情 / 表单',
              fields: [],
              groups: [{ group: 'basic', title: '基础信息', fields: ['title'] }],
            },
          ],
        }),
        undefined,
        {
          schemaVersion: '1',
          moduleAlias: 'education.exam',
          page: {
            template: 'FLAT_MANAGEMENT',
            detail: {
              emptyDescription: '暂无详情',
              createTitle: '新建',
              editor: {
                viewCode: 'editor',
                viewKind: 'FORM',
                fields: [{ fieldRef: { fieldName: 'title' }, label: '考试名称', uiType: 'input' }],
              },
            },
          },
        },
      ),
    });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const pageTree = wrapper.findComponent(PageCompositionTree);
    pageTree.vm.$emit('select', 'ui:group-field:form:basic:field-title');
    await flushPromises();
    await vi.waitFor(() =>
      expect(wrapper.findComponent(PageCompositionDescriptorPreview).exists()).toBe(true),
    );
    const preview = wrapper.findComponent(PageCompositionDescriptorPreview);
    expect(preview.props('mode')).toBe('edit');

    preview.vm.$emit('selectField', 'form', 'title');
    await flushPromises();

    expect(pageTree.props('selectedKey')).toBe('ui:group-field:form:basic:field-title');
  });

  it('keeps first-class group ordering available as a visible fallback to drag sorting', async () => {
    configureModuleContext({ http: publicationFlowHttp([]) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const pageTree = wrapper.findComponent(PageCompositionTree);
    pageTree.vm.$emit('select', 'ui:slot:form');
    await flushPromises();
    const addGroup = wrapper.findAll('button').find((button) => button.text() === '添加分组');
    await addGroup?.trigger('click');
    await addGroup?.trigger('click');
    pageTree.vm.$emit('select', 'ui:group:form:group_2');
    await flushPromises();

    expect(wrapper.text()).toContain('已选：分组 2');
    const moveUp = wrapper.findAll('button').find((button) => button.text() === '上移分组');
    await moveUp?.trigger('click');

    expect((pageTree.props('formGroups') as Array<{ id: string }>).map((group) => group.id)).toEqual([
      'group_2',
      'group_1',
    ]);
  });
});

function treeNode(nodes: unknown, key: string): { key: string; title: string } | undefined {
  if (!Array.isArray(nodes)) return undefined;
  for (const node of nodes) {
    if (!node || typeof node !== 'object') continue;
    const candidate = node as { key?: unknown; title?: unknown; children?: unknown };
    if (candidate.key === key && typeof candidate.title === 'string') {
      return { key: candidate.key, title: candidate.title };
    }
    const child = treeNode(candidate.children, key);
    if (child) return child;
  }
  return undefined;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((nextResolve) => {
    resolve = nextResolve;
  });
  return { promise, resolve };
}

function page(records: unknown[]) {
  return { records, pages: 1, totalKnown: true };
}

type MetadataFieldFixture = {
  id: string;
  fieldName: string;
  title: string;
  fieldOwnership: string;
  fieldForm?: string;
  systemManaged?: boolean;
};

function publicationFlowHttp(
  requests: HttpRequestOptions[],
  draftTree = initialTree(),
  mainFields: MetadataFieldFixture[] = [
    {
      id: 'field-title',
      fieldName: 'title',
      title: '考试名称',
      fieldOwnership: 'BUSINESS',
      fieldForm: 'PHYSICAL',
    },
  ],
  previewDescriptor: unknown = {},
): HttpClient {
  let published = false;
  return {
    request: <T>(options: HttpRequestOptions) => {
      requests.push(options);
      const response = responseFor(options, published, draftTree, mainFields, previewDescriptor);
      if (options.path === '/platform.presentation_publish/revisions/revision-1/publish') published = true;
      return Promise.resolve(response as T);
    },
  };
}

function responseFor(
  options: HttpRequestOptions,
  published: boolean,
  draftTree = initialTree(),
  mainFields: MetadataFieldFixture[] = [
    {
      id: 'field-title',
      fieldName: 'title',
      title: '考试名称',
      fieldOwnership: 'BUSINESS',
      fieldForm: 'PHYSICAL',
    },
  ],
  previewDescriptor: unknown = {},
) {
  if (options.path === '/platform.module/platform.module/context') {
    return { moduleAlias: 'platform.module', capabilities: [], actions: [] };
  }
  if (options.path === '/platform.module/education.exam/metadata-relations/query') {
    return {
      records: [
        { id: 'relation-1', metadataId: 'metadata-1', relationAlias: '考试', relationRole: 'main' },
        {
          id: 'relation-participant',
          metadataId: 'metadata-participant',
          parentMetadataId: 'metadata-1',
          relationAlias: '参考学生',
          relationRole: 'child',
        },
      ],
      pages: 1,
      totalKnown: true,
    };
  }
  if (options.path === '/platform.metadata/metadata-1/fields/query') {
    return {
      records: mainFields,
      pages: 1,
      totalKnown: true,
    };
  }
  if (options.path === '/platform.metadata/metadata-participant/fields/query') {
    return {
      records: [
        {
          id: 'field-student-name',
          fieldName: 'studentName',
          title: '学生姓名',
          fieldOwnership: 'BUSINESS',
          fieldForm: 'PHYSICAL',
        },
      ],
      pages: 1,
      totalKnown: true,
    };
  }
  if (options.path === '/platform.module/education.exam/pages/query') {
    return {
      records: [
        {
          id: 'page-1',
          alias: 'management',
          title: '考试管理',
          contractType: 'management',
          mainRelationId: 'relation-1',
        },
      ],
      pages: 1,
      totalKnown: true,
    };
  }
  if (options.path === '/platform.module/education.exam/pages/page-1/presentation-variants/query') {
    return {
      records: [
        { id: 'variant-1', title: 'Web 全局呈现', clientType: 'web', scopeType: 'global', pageId: 'page-1' },
      ],
      pages: 1,
      totalKnown: true,
    };
  }
  if (options.path === '/platform.presentation-variant/variant-1/revisions/query') {
    const draft = published
      ? {
          id: 'revision-2',
          revisionNo: 2,
          templateAlias: 'management',
          templateVersion: 1,
          uiTreeJson: draftTree,
          status: 'draft',
        }
      : {
          id: 'revision-1',
          revisionNo: 1,
          templateAlias: 'management',
          templateVersion: 1,
          uiTreeJson: draftTree,
          status: 'draft',
        };
    const records =
      options.body && JSON.stringify(options.body).includes('published')
        ? published
          ? [{ ...draft, id: 'revision-1', revisionNo: 1, status: 'published' }]
          : []
        : [draft];
    return { records, pages: 1, totalKnown: true };
  }
  if (options.path === '/platform.presentation-variant/variant-1/revisions/update/revision-1') {
    return { ...(options.body as object), id: 'revision-1' };
  }
  if (options.path === '/platform.presentation-variant/variant-1/revisions/insert') {
    return { ...(options.body as object), id: 'revision-2' };
  }
  if (options.path === '/platform.presentation_publish/revisions/revision-1/publish') return 1;
  if (options.path === '/platform.presentation-variant/variant-1/revisions/revision-1/preview') {
    return {
      pageId: 'page-1',
      variantId: 'variant-1',
      revisionId: 'revision-1',
      uiDescriptor: previewDescriptor,
    };
  }
  throw new Error(`Unexpected request: ${options.method ?? 'GET'} ${options.path}`);
}

function initialTree() {
  return JSON.stringify({ template: 'management', templateVersion: 1, nodes: [] });
}

function workspaceStubs() {
  return {
    ManagementWorkspace: { template: '<div><slot /></div>' },
    ManagementExplorerColumn: { template: '<div><slot /></div>' },
    RecordExplorerPanel: { template: '<div><slot /><slot name="header-actions" /></div>' },
    RecordDetailPanel: { template: '<section><slot name="actions" /><slot /></section>' },
    RecordDetailDrawer: { template: '<aside><slot /></aside>' },
    UiButton: {
      props: { disabled: Boolean, loading: Boolean },
      emits: ['click'],
      template:
        '<button data-testid="publish-button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    },
    UiInput: { template: '<input />' },
    UiSelect: { template: '<select />' },
    UiSpin: { template: '<span><slot /></span>' },
    UiSwitch: { template: '<button><slot /></button>' },
    UiTabs: { template: '<div><slot /></div>' },
    UiTree: {
      name: 'UiTree',
      props: { nodes: Array, dragOperations: Array },
      emits: ['drag-start', 'external-drop'],
      template: '<div>{{ JSON.stringify(nodes) }}</div>',
    },
    UiEmpty: { template: '<div><slot /></div>' },
  };
}

function metadataDrop() {
  return { kind: 'field', fieldId: 'field-title' };
}
