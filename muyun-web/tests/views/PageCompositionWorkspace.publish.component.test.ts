import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppError, configureModuleContext, type HttpClient, type HttpRequestOptions } from '@/web-core';
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

  it.each(['TREE_CARD', 'MICRO_LIST_CARD'])(
    'persists %s skeleton bindings and deduplicated query fields without changing the form',
    async (mode) => {
      const requests: HttpRequestOptions[] = [];
      const delegate = publicationFlowHttp(requests);
      configureModuleContext({
        http: {
          request: <T>(options: HttpRequestOptions) =>
            options.path.endsWith('/overview-mode')
              ? Promise.resolve({ ...compositionProfile(), overviewMode: mode } as T)
              : delegate.request<T>(options),
        },
      });
      const wrapper = mount(PageCompositionWorkspace, {
        props: { moduleAlias: 'education.exam', moduleTitle: '考试管理' },
        global: { stubs: workspaceStubs() },
      });
      await flushPromises();
      await flushPromises();
      const tree = wrapper.findComponent(PageCompositionTree);
      const before = tree.props('formFields');
      tree.vm.$emit('source-drop', { kind: 'explorer-title' }, metadataDrop());
      tree.vm.$emit('source-drop', { kind: 'explorer-secondary' }, metadataDrop());
      tree.vm.$emit('source-drop', { kind: 'quick-search' }, metadataDrop());
      tree.vm.$emit('source-drop', { kind: 'quick-search' }, metadataDrop());
      await flushPromises();
      expect(tree.props('quickSearchFields')).toEqual([{ fieldName: 'title', title: '考试名称' }]);
      expect(tree.props('formFields')).toEqual(before);
      await wrapper
        .findAll('[data-testid="publish-button"]')
        .find((button) => button.text().includes('保存草稿'))!
        .trigger('click');
      await flushPromises();
      const saved = requests.find((request) => request.path.endsWith('/revisions/update/revision-1'))!;
      const json = JSON.parse((saved.body as { uiTreeJson: string }).uiTreeJson);
      expect(json.mode).toBe(mode);
      expect(json.quickSearchFields).toEqual(['title']);
      expect(json.nodes.find((node: { slot: string }) => node.slot === 'explorer')).toMatchObject({
        titleField: 'title',
        secondaryField: 'title',
      });
      expect(json.nodes.some((node: { slot: string }) => node.slot === 'list')).toBe(false);
      tree.vm.$emit('node-action', 'remove', 'ui:binding:quick-search:title');
      await flushPromises();
      expect(tree.props('quickSearchFields')).toEqual([]);
      wrapper.unmount();
    },
  );

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

  it.each([true, false])(
    'keeps the acknowledged publication state when reloading fails (next draft created: %s)',
    async (createNext) => {
      const requests: HttpRequestOptions[] = [];
      const http = publicationFlowHttp(requests);
      const original = http.request;
      let published = false;
      vi.spyOn(http, 'request').mockImplementation((request) => {
        if (published && request.path.endsWith('/pages/query')) return Promise.reject(new Error('刷新失败'));
        if (published && request.path.endsWith('/revisions/insert') && !createNext)
          return Promise.reject(new Error('创建草稿失败'));
        if (published && request.path.endsWith('/revisions/query'))
          return Promise.resolve(page([{ id: 'revision-1', revisionNo: 1, status: 'published' }])) as never;
        if (request.path.endsWith('/publish')) published = true;
        if (request.path.endsWith('/update/revision-2')) {
          requests.push(request);
          return Promise.resolve({ ...(request.body as object), id: 'revision-2' }) as never;
        }
        return original(request);
      });
      configureModuleContext({ http });
      vi.mocked(confirmAction).mockResolvedValue(true);
      const wrapper = mount(PageCompositionWorkspace, {
        props: { moduleAlias: 'education.exam' },
        global: { stubs: workspaceStubs() },
      });
      try {
        await flushPromises();
        const button = (name: string) => wrapper.findAll('button').find((item) => item.text() === name);
        await button('发布草稿')!.trigger('click');
        await flushPromises();
        expect(wrapper.text()).toContain('最近发布 v1');
        expect(wrapper.text()).not.toContain('草稿 v1');
        if (createNext) {
          expect(wrapper.text()).toContain('草稿 v2');
          wrapper
            .findComponent(PageCompositionTree)
            .vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
          await flushPromises();
          await button('保存草稿')!.trigger('click');
          await flushPromises();
          expect(requests.some((request) => request.path.endsWith('/update/revision-2'))).toBe(true);
        } else {
          expect(button('保存草稿')).toBeUndefined();
          expect(button('发布草稿')).toBeUndefined();
          expect(button('基于已发布版本创建草稿')).toBeDefined();
        }
      } finally {
        wrapper.unmount();
      }
    },
  );

  it.each(['保存草稿', '发布草稿'])(
    'preserves local edits after a conflict from %s and explicitly reloads the latest snapshot',
    async (action) => {
      const requests: HttpRequestOptions[] = [];
      const http = publicationFlowHttp(requests);
      const original = http.request;
      let failReload = false;
      vi.spyOn(http, 'request').mockImplementation((request) => {
        if (request.path.endsWith('/update/revision-1')) {
          requests.push(request);
          return Promise.reject(
            new AppError('数据已被更新，请刷新后重试', { code: 'CONFLICT_VERSION', status: 409 }),
          );
        }
        if (failReload && request.path.endsWith('/pages/query')) return Promise.reject(new Error('读取失败'));
        return original(request);
      });
      configureModuleContext({ http });
      vi.mocked(confirmAction).mockResolvedValue(true);
      const wrapper = mount(PageCompositionWorkspace, {
        props: { moduleAlias: 'education.exam' },
        global: { stubs: workspaceStubs() },
      });
      await flushPromises();
      const tree = wrapper.findComponent(PageCompositionTree);
      const button = (name: string) => wrapper.findAll('button').find((item) => item.text() === name)!;
      tree.vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
      await flushPromises();
      await button(action).trigger('click');
      await flushPromises();
      expect(wrapper.get('[role="alert"]').text()).toContain('本地修改已保留');
      expect(tree.props('listFields')).toHaveLength(1);
      expect(canDiscardChanges(wrapper)).toBe(true);
      expect(button('保存草稿').attributes('disabled')).toBeDefined();
      expect(button('发布草稿').attributes('disabled')).toBeDefined();
      expect(requests.some((request) => request.path.endsWith('/publish'))).toBe(false);
      expect(requests.filter((request) => request.path.endsWith('/update/revision-1'))).toHaveLength(1);

      vi.mocked(confirmAction).mockResolvedValue(false);
      await button('加载最新草稿').trigger('click');
      await flushPromises();
      expect(tree.props('listFields')).toHaveLength(1);
      failReload = true;
      vi.mocked(confirmAction).mockResolvedValue(true);
      await button('加载最新草稿').trigger('click');
      await flushPromises();
      expect(tree.props('listFields')).toHaveLength(1);
      expect(wrapper.get('[role="alert"]').text()).toContain('本地修改已保留');
      failReload = false;
      await button('加载最新草稿').trigger('click');
      await flushPromises();
      expect(tree.props('listFields')).toEqual([]);
      expect(wrapper.find('[role="alert"]').exists()).toBe(false);
      expect(canDiscardChanges(wrapper)).toBe(false);
      expect(button('发布草稿').attributes('disabled')).toBeUndefined();
      wrapper.unmount();
    },
  );

  it('discards local edits only after confirmation and preserves them when cancelled', async () => {
    configureModuleContext({ http: publicationFlowHttp([]) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    const tree = wrapper.findComponent(PageCompositionTree);
    tree.vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
    await flushPromises();
    const discard = () => wrapper.findAll('button').find((item) => item.text() === '放弃本次更改')!;
    vi.mocked(confirmAction).mockResolvedValue(false);
    await discard().trigger('click');
    await flushPromises();
    expect(tree.props('listFields')).toHaveLength(1);
    expect(canDiscardChanges(wrapper)).toBe(true);
    vi.mocked(confirmAction).mockResolvedValue(true);
    await discard().trigger('click');
    await flushPromises();
    expect(tree.props('listFields')).toEqual([]);
    expect(canDiscardChanges(wrapper)).toBe(false);
    wrapper.unmount();
  });

  it.each(['success', 'conflict'] as const)(
    'ignores a late save %s after switching modules',
    async (outcome) => {
      const requests: HttpRequestOptions[] = [];
      const http = publicationFlowHttp(requests);
      const original = http.request;
      const pending = deferred<unknown>();
      let saveBody: unknown;
      vi.spyOn(http, 'request').mockImplementation((request) => {
        if (request.path.endsWith('/update/revision-1')) {
          saveBody = request.body;
          return pending.promise.then((result) => {
            if (result instanceof Error) throw result;
            return result;
          }) as never;
        }
        return original({ ...request, path: request.path.replace('education.next', 'education.exam') });
      });
      configureModuleContext({ http });
      const wrapper = mount(PageCompositionWorkspace, {
        props: { moduleAlias: 'education.exam' },
        global: { stubs: workspaceStubs() },
      });
      await flushPromises();
      const tree = wrapper.findComponent(PageCompositionTree);
      tree.vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
      await flushPromises();
      await wrapper
        .findAll('button')
        .find((item) => item.text() === '保存草稿')!
        .trigger('click');
      await flushPromises();
      expect(saveBody).toBeDefined();
      await wrapper.setProps({ moduleAlias: 'education.next' });
      await flushPromises();
      pending.resolve(
        outcome === 'conflict'
          ? new AppError('旧模块冲突', { code: 'CONFLICT_VERSION', status: 409 })
          : { ...(saveBody as object), id: 'old-revision', version: 2 },
      );
      await flushPromises();
      expect(tree.props('listFields')).toEqual([]);
      expect(wrapper.find('[role="alert"]').exists()).toBe(false);
      expect(canDiscardChanges(wrapper)).toBe(false);
      expect(
        wrapper
          .findAll('button')
          .find((item) => item.text() === '发布草稿')!
          .attributes('disabled'),
      ).toBeUndefined();
      wrapper.unmount();
    },
  );

  it('ignores an older module load after the workspace switches modules', async () => {
    const requests: HttpRequestOptions[] = [];
    const oldRelations = deferred<unknown>();
    configureModuleContext({
      http: {
        request: <T>(options: HttpRequestOptions) => {
          requests.push(options);
          if (options.path === '/platform.module/platform.module/context')
            return Promise.resolve({ moduleAlias: 'platform.module', capabilities: [], actions: [] } as T);
          if (options.path.endsWith('/overview-mode')) return Promise.resolve(compositionProfile() as T);
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
    expect(wrapper.text()).toContain('子表');
    expect(wrapper.text()).toContain('学生姓名');
  });

  it('toggles system sources without exposing reserved fields or changing the page draft', async () => {
    const requests: HttpRequestOptions[] = [];
    const http = publicationFlowHttp(requests, initialTree(), [
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
    ]);
    const originalRequest = http.request;
    http.request = (options) =>
      options.path === '/platform.metadata/metadata-participant/fields/query'
        ? (Promise.resolve(
            page([
              { id: 'child-sort', fieldName: 'sortOrder', title: '子表排序', systemManaged: true },
              { id: 'child-name', fieldName: 'name', title: '学生姓名' },
            ]),
          ) as never)
        : originalRequest(options);
    configureModuleContext({ http });

    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const pageTree = wrapper.findComponent(PageCompositionTree);
    const draftBefore = JSON.stringify(pageTree.props());
    const metadataTree = wrapper.findComponent({ name: 'UiTree' });
    expect(
      treeNode(metadataTree.props('nodes'), 'metadata:relation-field:relation-participant:child-sort'),
    ).toBeUndefined();
    expect(treeNode(metadataTree.props('nodes'), 'metadata:field:field-enabled')).toBeUndefined();
    const toggle = wrapper.get('[aria-label="显示系统字段"]');
    await toggle.trigger('click');
    const nodes = metadataTree.props('nodes');
    expect(treeNode(nodes, 'metadata:relation-field:relation-participant:child-sort')).toBeDefined();
    expect(treeNode(nodes, 'metadata:field:field-id')).toBeUndefined();
    expect(treeNode(nodes, 'metadata:field:field-tenant')).toBeUndefined();
    expect(treeNode(nodes, 'metadata:field:field-enabled')).toBeDefined();
    expect(treeNode(nodes, 'metadata:field:field-title')).toBeDefined();
    await wrapper.get('[aria-label="隐藏系统字段"]').trigger('click');
    expect(treeNode(metadataTree.props('nodes'), 'metadata:field:field-enabled')).toBeUndefined();
    expect(treeNode(metadataTree.props('nodes'), 'metadata:field:field-title')).toBeDefined();
    expect(
      treeNode(metadataTree.props('nodes'), 'metadata:relation-field:relation-participant:child-sort'),
    ).toBeUndefined();
    expect(
      treeNode(metadataTree.props('nodes'), 'metadata:relation-field:relation-participant:child-name'),
    ).toBeDefined();
    expect(JSON.stringify(pageTree.props())).toBe(draftBefore);
    expect(requests.some((request) => /\/(update|publish|insert)(\/|$)/.test(request.path))).toBe(false);
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

    pageTree.vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
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

      pageTree.vm.$emit('source-drop', { ...target, index: 0 }, metadataDrop());
      await flushPromises();
      expect(fields().map((field: { id: string }) => field.id)).toEqual(['field-title', 'field-other']);

      pageTree.vm.$emit('source-drop', { ...target, index: 1 }, metadataDrop());
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
      pageTree.vm.$emit('source-drop', { kind: 'list' }, { dataTransfer });
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

    preview.vm.$emit(
      'placement-drop',
      { kind: 'metadata', metadata: metadataDrop() },
      { container: { kind: 'list' }, position: 'inside' },
    );
    await flushPromises();

    expect(wrapper.findComponent(PageCompositionTree).props('listFields')).toMatchObject([
      { id: 'field-title', title: '考试名称' },
    ]);
    expect(wrapper.findComponent(PageCompositionDescriptorPreview).props('mode')).toBe('list');
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

    pageTree.vm.$emit('source-drop', { kind: 'group', groupId: 'target' }, metadataDrop());
    await flushPromises();

    expect(pageTree.props('formGroups')).toMatchObject([
      { id: 'source', fields: [] },
      { id: 'target', fields: [{ id: 'field-title' }] },
    ]);
  });

  it('removes a grouped field and restores its placement through undo', async () => {
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
    tree.vm.$emit('node-action', 'remove', 'ui:group-field:form:target:field-title');
    await flushPromises();

    expect(tree.props('formGroups')).toMatchObject([{ id: 'target', fields: [] }]);
    expect(tree.props('formFields')).toEqual([]);
    expect(tree.props('listFields')).toMatchObject([{ id: 'field-title' }]);
    expect(canDiscardChanges(wrapper)).toBe(true);
    const undo = wrapper.findAll('button').find((button) => button.text() === '撤销移除');
    expect(undo).toBeDefined();
    await undo!.trigger('click');
    await flushPromises();
    expect(tree.props('formGroups')).toMatchObject([{ id: 'target', fields: [{ id: 'field-title' }] }]);
    expect(tree.props('formFields')).toEqual([]);
    expect(tree.props('listFields')).toMatchObject([{ id: 'field-title' }]);
    expect(canDiscardChanges(wrapper)).toBe(false);
    wrapper.unmount();
  });

  it('configures a child column by double click, validates and persists page properties', async () => {
    const requests: HttpRequestOptions[] = [];
    const draft = JSON.stringify({
      template: 'management',
      templateVersion: 1,
      nodes: [
        { slot: 'list', fields: [] },
        {
          slot: 'form',
          fields: [],
          relations: [
            {
              relation: '参考学生',
              title: '参考学生',
              fields: [{ field: 'studentName', props: { label: '学生', width: '120px', align: 'right' } }],
            },
          ],
        },
      ],
    });
    configureModuleContext({ http: publicationFlowHttp(requests, draft) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    try {
      await flushPromises();
      const tree = wrapper.findComponent(PageCompositionTree);
      const field = tree.props('formRelations')[0].fields[0];
      const key = `ui:relation-field:form:relation-participant:${field.id}`;
      tree.vm.$emit('double-click', key);
      await flushPromises();
      const input = (title: string) =>
        wrapper
          .findAll('label')
          .find((label) => label.text().startsWith(title))!
          .get('input');
      expect((input('列宽').element as HTMLInputElement).value).toBe('120');
      expect((input('展示标题').element as HTMLInputElement).value).toBe('学生');
      expect(wrapper.text()).toContain('对齐');
      expect(wrapper.text()).not.toContain('字段跨度');
      await input('展示标题').setValue('学生姓名');
      await input('列宽').setValue('0');
      const save = () => wrapper.findAll('button').find((button) => button.text() === '保存草稿')!;
      expect(save().attributes('disabled')).toBeDefined();
      tree.vm.$emit('select', 'ui:template:list:quick-search');
      await flushPromises();
      expect(save().attributes('disabled')).toBeDefined();
      tree.vm.$emit('node-action', 'configure', key);
      await flushPromises();
      await input('列宽').setValue('');
      expect(tree.props('formRelations')[0].fields[0].properties?.width).toBeUndefined();
      await input('列宽').setValue('180');
      expect(save().attributes('disabled')).toBeUndefined();
      await save().trigger('click');
      await flushPromises();
      const saved = requests.find((request) => request.path.endsWith('/update/revision-1'))!.body as {
        uiTreeJson: string;
      };
      expect(JSON.parse(saved.uiTreeJson).nodes[1].relations[0].fields).toEqual([
        { field: 'studentName', props: { label: '学生姓名', width: '180px', align: 'right' } },
      ]);
      await vi.waitFor(() =>
        expect(wrapper.findComponent(PageCompositionDescriptorPreview).exists()).toBe(true),
      );
      wrapper
        .findComponent(PageCompositionDescriptorPreview)
        .vm.$emit('configureRelationField', '参考学生', 'studentName');
      await flushPromises();
      expect((input('列宽').element as HTMLInputElement).value).toBe('180');
    } finally {
      wrapper.unmount();
    }
  });

  it('updates properties directly in the local draft and blocks invalid widths across selection changes', async () => {
    const requests: HttpRequestOptions[] = [];
    configureModuleContext({ http: publicationFlowHttp(requests) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    try {
      await flushPromises();
      const tree = wrapper.findComponent(PageCompositionTree);
      tree.vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
      tree.vm.$emit('node-action', 'configure', 'ui:field:list:field-title');
      await flushPromises();
      const input = (title: string) =>
        wrapper
          .findAll('label')
          .find((label) => label.text().startsWith(title))!
          .get('input');
      const button = (title: string) => wrapper.findAll('button').find((button) => button.text() === title)!;
      await input('展示标题').setValue('自定义标题');
      await input('列宽').setValue('bad width');
      expect(tree.props('listFields')[0].properties).toEqual({ label: '自定义标题', width: 'bad width' });
      expect(
        requests.some((request) => request.path.includes('/update/') || request.path.endsWith('/publish')),
      ).toBe(false);
      expect(wrapper.text()).not.toContain('应用到草稿');
      tree.vm.$emit('select', 'ui:template:list:quick-search');
      await flushPromises();
      expect(button('保存草稿').attributes('disabled')).toBeDefined();
      expect(button('发布草稿').attributes('disabled')).toBeDefined();
      await button('自定义标题').trigger('click');
      expect((input('列宽').element as HTMLInputElement).value).toBe('bad width');
      await input('列宽').setValue('160px');
      expect(button('保存草稿').attributes('disabled')).toBeUndefined();
      tree.vm.$emit('node-action', 'configure', 'ui:template:list:quick-search');
      await flushPromises();
      await input('搜索占位提示').setValue('搜索考试');
      await button('保存草稿').trigger('click');
      await flushPromises();
      const saved = requests.find((request) => request.path.endsWith('/update/revision-1'))!.body as {
        uiTreeJson: string;
      };
      const treeJson = JSON.parse(saved.uiTreeJson);
      expect(treeJson.props.list.searchPlaceholder).toBe('搜索考试');
      expect(treeJson.nodes[0].fields[0].props).toEqual({ label: '自定义标题', width: '160px' });
    } finally {
      wrapper.unmount();
    }
  });

  it.each(['ui:group:form:basic', 'ui:relation:form:relation-participant'])(
    'undoes removal of %s with its fields and properties, and expires undo after further edits',
    async (key) => {
      const draft = JSON.stringify({
        template: 'management',
        templateVersion: 1,
        nodes: [
          { slot: 'list', fields: [] },
          {
            slot: 'form',
            fields: [],
            groups: [
              {
                group: 'basic',
                title: '基础信息',
                fields: [{ field: 'title', props: { label: '标题', readOnly: true } }],
              },
            ],
            relations: [{ relation: '参考学生', fields: ['studentName'] }],
          },
        ],
      });
      configureModuleContext({ http: publicationFlowHttp([], draft) });
      const wrapper = mount(PageCompositionWorkspace, {
        props: { moduleAlias: 'education.exam' },
        global: { stubs: workspaceStubs() },
      });
      try {
        await flushPromises();
        const tree = wrapper.findComponent(PageCompositionTree);
        const before = JSON.stringify({
          groups: tree.props('formGroups'),
          relations: tree.props('formRelations'),
        });
        tree.vm.$emit('node-action', 'remove', key);
        await flushPromises();
        const undo = () => wrapper.findAll('button').find((button) => button.text() === '撤销移除');
        expect(undo()).toBeDefined();
        await undo()!.trigger('click');
        expect(
          JSON.stringify({ groups: tree.props('formGroups'), relations: tree.props('formRelations') }),
        ).toBe(before);
        expect(canDiscardChanges(wrapper)).toBe(false);
        tree.vm.$emit('node-action', 'remove', key);
        await flushPromises();
        tree.vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
        await flushPromises();
        expect(undo()).toBeUndefined();
      } finally {
        wrapper.unmount();
      }
    },
  );

  it('undoes a local removal even before the page has been initialized', async () => {
    const http = publicationFlowHttp([]);
    const original = http.request;
    vi.spyOn(http, 'request').mockImplementation((request) =>
      request.path.endsWith('/pages/query') ? (Promise.resolve(page([])) as never) : original(request),
    );
    configureModuleContext({ http });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    try {
      await flushPromises();
      const tree = wrapper.findComponent(PageCompositionTree);
      tree.vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
      tree.vm.$emit('node-action', 'remove', 'ui:field:list:field-title');
      await flushPromises();
      expect(tree.props('listFields')).toEqual([]);
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '撤销移除')!
        .trigger('click');
      expect(tree.props('listFields')).toHaveLength(1);
    } finally {
      wrapper.unmount();
    }
  });

  it('adds source fields to explicit destinations through node actions without changing metadata', async () => {
    const requests: HttpRequestOptions[] = [];
    configureModuleContext({ http: publicationFlowHttp(requests) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    try {
      await flushPromises();
      const source = wrapper.findComponent({ name: 'UiTree' });
      const tree = wrapper.findComponent(PageCompositionTree);
      const field = treeNode(source.props('nodes'), 'metadata:field:field-title');
      source.vm.$emit('action', { key: 'add-list' }, field);
      await flushPromises();
      expect(tree.props('listFields')).toHaveLength(1);
      expect(tree.props('formFields')).toEqual([]);
      source.vm.$emit('action', { key: 'add-form' }, field);
      source.vm.$emit(
        'action',
        { key: 'add-form' },
        treeNode(source.props('nodes'), 'metadata:relation-field:relation-participant:field-student-name'),
      );
      await flushPromises();
      expect(tree.props('formFields')).toHaveLength(1);
      expect(tree.props('formRelations')[0].fields).toMatchObject([{ fieldName: 'studentName' }]);
      expect(
        requests.filter(
          (request) =>
            request.path.includes('/insert') ||
            request.path.includes('/update') ||
            request.path.includes('/delete'),
        ),
      ).toEqual([]);
    } finally {
      wrapper.unmount();
    }
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

    preview.vm.$emit(
      'placement-drop',
      { kind: 'metadata', metadata: metadataDrop() },
      { container: { kind: 'list' }, position: 'inside' },
    );

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

  it('adds groups at the form node and applies tree ordering', async () => {
    configureModuleContext({ http: publicationFlowHttp([]) });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    await flushPromises();
    await flushPromises();

    const pageTree = wrapper.findComponent(PageCompositionTree);
    pageTree.vm.$emit('node-action', 'add-group', 'ui:slot:form');
    pageTree.vm.$emit('node-action', 'add-group', 'ui:groups:form');
    await flushPromises();
    pageTree.vm.$emit('reorder-group', 'group_2', 0);
    await flushPromises();

    expect((pageTree.props('formGroups') as Array<{ id: string }>).map((group) => group.id)).toEqual([
      'group_2',
      'group_1',
    ]);
  });
  it('retains unavailable fields in every slot and saves only explicit repairs', async () => {
    const requests: HttpRequestOptions[] = [];
    configureModuleContext({
      http: publicationFlowHttp(
        requests,
        JSON.stringify({
          template: 'management',
          templateVersion: 1,
          nodes: [
            { slot: 'list', fields: [{ field: 'lost', props: { label: '旧字段' } }] },
            {
              slot: 'form',
              fields: [],
              groups: [{ group: 'basic', title: '分组', fields: ['lost'] }],
              relations: [{ relation: '参考学生', fields: ['lostChild'] }],
            },
          ],
        }),
      ),
    });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    try {
      await flushPromises();
      const tree = wrapper.findComponent(PageCompositionTree);
      expect(tree.props('listFields')).toMatchObject([
        { fieldName: 'lost', unavailable: true, properties: { label: '旧字段' } },
      ]);
      expect(tree.props('formGroups')[0].fields).toMatchObject([{ fieldName: 'lost', unavailable: true }]);
      expect(tree.props('formRelations')[0].fields).toMatchObject([
        { fieldName: 'lostChild', unavailable: true },
      ]);
      expect(tree.text()).toContain('来源失效');
      const button = (text: string) =>
        wrapper.findAll('[data-testid="publish-button"]').find((item) => item.text() === text)!;
      expect(button('发布草稿').attributes('disabled')).toBeDefined();
      tree.vm.$emit('node-action', 'remove', 'ui:field:list:missing_lost');
      await flushPromises();
      await button('保存草稿').trigger('click');
      await flushPromises();
      const saved = requests.find((request) => request.path.endsWith('/update/revision-1'))?.body as {
        uiTreeJson: string;
      };
      const declaration = JSON.parse(saved.uiTreeJson);
      expect(declaration.nodes[0].fields).toEqual([]);
      expect(declaration.nodes[1].groups[0].fields).toEqual(['lost']);
      expect(declaration.nodes[1].relations[0].fields).toEqual(['lostChild']);
      expect(saved.uiTreeJson).not.toContain('unavailable');
      expect(button('发布草稿').attributes('disabled')).toBeDefined();
    } finally {
      wrapper.unmount();
    }
  });

  it('refreshes query eligibility and module actions without losing local placements', async () => {
    const requests: HttpRequestOptions[] = [];
    const delegate = publicationFlowHttp(requests);
    let refreshed = false;
    configureModuleContext({
      http: {
        request: <T>(request: HttpRequestOptions) => {
          if (request.path.endsWith('/overview-mode'))
            return Promise.resolve({
              ...compositionProfile(),
              searchableFields: refreshed ? ['title'] : [],
            } as T);
          if (request.path.endsWith('/context'))
            return Promise.resolve({
              actions: refreshed
                ? [{ actionCode: 'create', title: '创建', authorized: true, actionLevel: 'LIST' }]
                : [],
            } as T);
          return delegate.request<T>(request);
        },
      },
    });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    try {
      await flushPromises();
      const tree = wrapper.findComponent(PageCompositionTree);
      tree.vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
      await flushPromises();
      expect(tree.props('searchableFieldIds')).toEqual([]);
      refreshed = true;
      wrapper.findAllComponents({ name: 'RecordExplorerPanel' })[0]!.vm.$emit('refresh');
      await flushPromises();
      expect(tree.props('listFields')).toHaveLength(1);
      expect(tree.props('searchableFieldIds')).toContain('field-title');
      expect(tree.props('moduleActions')).toMatchObject([{ actionCode: 'create' }]);
      tree.vm.$emit('source-drop', { kind: 'quick-search' }, metadataDrop());
      await flushPromises();
      expect(tree.props('quickSearchFields')).toMatchObject([{ fieldName: 'title' }]);
      expect(canDiscardChanges(wrapper)).toBe(true);
    } finally {
      wrapper.unmount();
    }
  });

  it('refreshes source facts in place while preserving local page changes and detecting lost sources', async () => {
    const requests: HttpRequestOptions[] = [];
    const fields = [
      {
        id: 'field-title',
        fieldName: 'title',
        title: '考试名称',
        fieldOwnership: 'BUSINESS',
        fieldForm: 'PHYSICAL',
      },
    ];
    const http = publicationFlowHttp(requests, initialTree(), fields);
    configureModuleContext({ http });
    const wrapper = mount(PageCompositionWorkspace, {
      props: { moduleAlias: 'education.exam' },
      global: { stubs: workspaceStubs() },
    });
    try {
      await flushPromises();
      const composer = wrapper.findComponent(PageCompositionTree);
      composer.vm.$emit('source-drop', { kind: 'list' }, metadataDrop());
      await flushPromises();
      const source = wrapper.findAllComponents({ name: 'UiTree' })[0]!;
      const sourceInstance = source.vm;
      const pending = deferred<unknown>();
      const original = http.request;
      vi.spyOn(http, 'request').mockImplementation((request) =>
        request.path === '/platform.metadata/metadata-1/fields/query'
          ? (pending.promise as never)
          : original(request),
      );
      wrapper.findAllComponents({ name: 'RecordExplorerPanel' })[0]!.vm.$emit('refresh');
      await flushPromises();
      expect(wrapper.findAllComponents({ name: 'UiTree' })[0]!.vm).toBe(sourceInstance);
      expect(composer.props('listFields')).toHaveLength(1);
      pending.resolve(page([]));
      await flushPromises();
      expect(wrapper.findAllComponents({ name: 'UiTree' })[0]!.vm).toBe(sourceInstance);
      expect(composer.props('listFields')).toMatchObject([{ fieldName: 'title', unavailable: true }]);
      expect(canDiscardChanges(wrapper)).toBe(true);
      expect(wrapper.text()).toContain('来源失效');
    } finally {
      wrapper.unmount();
    }
  });
});

// Unsaved state is exposed through the available recovery action, not a summary label.
function canDiscardChanges(wrapper: VueWrapper) {
  return wrapper.findAll('button').some((button) => button.text() === '放弃本次更改');
}

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
  if (options.path.endsWith('/overview-mode')) return compositionProfile();
  if (options.path === '/platform.module/platform.module/context') {
    return { moduleAlias: 'platform.module', capabilities: [], actions: [] };
  }
  if (options.path === '/platform.module/education.exam/context') {
    return { moduleAlias: 'education.exam', capabilities: [], actions: [] };
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
    RecordExplorerPanel: {
      name: 'RecordExplorerPanel',
      template:
        '<div><slot /><slot name="utility-actions" /><slot name="actions" /><slot name="footer" /></div>',
    },
    RecordDetailPanel: { template: '<section><slot name="actions" /><slot /></section>' },
    RecordDetailDrawer: { template: '<aside><slot /></aside>' },
    UiButton: {
      props: { disabled: Boolean, loading: Boolean },
      emits: ['click'],
      template:
        '<button data-testid="publish-button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    },
    UiInput: {
      props: ['value', 'disabled'],
      emits: ['update:value'],
      template:
        '<input :value="value" :disabled="disabled" @input="$emit(\'update:value\', $event.target.value)" />',
    },
    UiSelect: { props: ['options', 'value', 'disabled'], template: '<select :disabled="disabled" />' },
    UiSpin: { template: '<span><slot /></span>' },
    UiSwitch: {
      props: ['checked'],
      emits: ['update:checked'],
      template:
        '<button role="switch" :aria-checked="checked" @click="$emit(\'update:checked\', !checked)" />',
    },
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

function compositionProfile() {
  return {
    overviewMode: 'LIST_CARD',
    searchableFields: ['title'],
    compositionSkeletons: [
      {
        mode: 'LIST_CARD',
        title: '列表 + 卡片',
        navigationTitle: '记录列表',
        fieldGroupTitle: '列表展示字段',
        columns: true,
        maxIdentityFields: 0,
      },
      {
        mode: 'TREE_CARD',
        title: '树 + 卡片',
        navigationTitle: '树导航',
        fieldGroupTitle: '节点展示',
        columns: false,
        maxIdentityFields: 2,
      },
      {
        mode: 'MICRO_LIST_CARD',
        title: '微列表 + 卡片',
        navigationTitle: '记录导航',
        fieldGroupTitle: '条目展示',
        columns: false,
        maxIdentityFields: 2,
      },
    ],
  };
}
