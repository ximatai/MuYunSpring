import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { configureModuleContext, type HttpClient, type HttpRequestOptions } from '@/web-core';
import PageCompositionWorkspace from '@/views/PageCompositionWorkspace.vue';
import { confirmAction } from '@muyun/vue-ui-antdv';

vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn(),
}));

describe('PageCompositionWorkspace publication flow', () => {
  afterEach(() => {
    vi.clearAllMocks();
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
});

function publicationFlowHttp(requests: HttpRequestOptions[]): HttpClient {
  let published = false;
  return {
    request: <T>(options: HttpRequestOptions) => {
      requests.push(options);
      const response = responseFor(options, published);
      if (options.path === '/platform.presentation_publish/revisions/revision-1/publish') published = true;
      return Promise.resolve(response as T);
    },
  };
}

function responseFor(options: HttpRequestOptions, published: boolean) {
  if (options.path === '/platform.module/platform.module/context') {
    return { moduleAlias: 'platform.module', capabilities: [], actions: [] };
  }
  if (options.path === '/platform.module/education.exam/metadata-relations/query') {
    return {
      records: [{ id: 'relation-1', metadataId: 'metadata-1', relationAlias: '考试' }],
      pages: 1,
      totalKnown: true,
    };
  }
  if (options.path === '/platform.metadata/metadata-1/fields/query') {
    return {
      records: [
        {
          id: 'field-title',
          fieldName: 'title',
          title: '考试名称',
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
          uiTreeJson: initialTree(),
          status: 'draft',
        }
      : {
          id: 'revision-1',
          revisionNo: 1,
          templateAlias: 'management',
          templateVersion: 1,
          uiTreeJson: initialTree(),
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
    return { pageId: 'page-1', variantId: 'variant-1', revisionId: 'revision-1', uiDescriptor: {} };
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
    UiTree: { template: '<div />' },
    UiEmpty: { template: '<div><slot /></div>' },
  };
}
