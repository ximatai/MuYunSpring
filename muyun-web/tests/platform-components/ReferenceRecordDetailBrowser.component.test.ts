/* eslint-disable vue/one-component-per-file -- Local slot fixtures cover distinct reference-field layouts. */
import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { AppError, type HttpClient } from '@/web-core';
import ReferenceRecordDetailBrowser from '@/platform-components/ReferenceRecordDetailBrowser.vue';
import ReadonlyReferenceValue from '@/platform-components/ReadonlyReferenceValue.vue';

const ReferenceField = defineComponent({
  components: { ReadonlyReferenceValue },
  template: `
    <ReadonlyReferenceValue
      :reference="{ targetModuleAlias: 'iam.user', cardinality: 'ONE' }"
      value="user-1"
      :summary="{ id: 'user-1', title: '平台管理员' }"
    />`,
});

const TwoReferenceFields = defineComponent({
  components: { ReadonlyReferenceValue },
  template: `
    <ReadonlyReferenceValue
      :reference="{ targetModuleAlias: 'iam.user', cardinality: 'ONE' }"
      value="user-1"
      :summary="{ id: 'user-1', title: '甲' }"
    />
    <ReadonlyReferenceValue
      :reference="{ targetModuleAlias: 'iam.user', cardinality: 'ONE' }"
      value="user-2"
      :summary="{ id: 'user-2', title: '乙' }"
    />`,
});

function flush() {
  return Promise.resolve()
    .then(() => Promise.resolve())
    .then(nextTick);
}

function viewContext() {
  return {
    moduleAlias: 'iam.user',
    actions: [],
    capabilities: [],
    uiDescriptor: {
      page: {
        detail: { display: { fields: [{ fieldRef: { fieldName: 'title' }, label: '名称' }] } },
      },
    },
  };
}

describe('ReferenceRecordDetailBrowser', () => {
  it('shows an explicit empty state when the target has no detail display fields', async () => {
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) {
          return { moduleAlias: 'iam.user', actions: [], capabilities: [] } as never;
        }
        return { id: 'user-1', title: '平台管理员' } as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: ReferenceField },
      global: {
        stubs: {
          RecordDetailDrawer: { template: '<aside><slot /></aside>' },
          RecordPanelState: { props: ['description'], template: '<p>{{ description }}</p>' },
        },
      },
    });

    await wrapper.get('button').trigger('click');
    await flush();

    expect(wrapper.text()).toContain('当前模块未配置可展示的详情字段。');
    wrapper.unmount();
  });

  it('uses target VIEW context and standard target view without source scope', async () => {
    const requests: Array<{ path: string; headers?: Record<string, string> }> = [];
    const http: HttpClient = {
      async request(options) {
        requests.push(options);
        if (options.path.endsWith('/view-context')) {
          return viewContext() as never;
        }
        return { id: 'user-1', title: '平台管理员' } as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: ReferenceField },
      global: {
        stubs: {
          RecordDetailDrawer: { template: '<aside><slot /></aside>' },
          RecordPanelState: { template: '<p />' },
          RecordDetailFields: { props: ['record'], template: '<p>{{ record.title }}</p>' },
        },
      },
    });

    await wrapper.get('button').trigger('click');
    await flush();

    expect(requests.map((request) => request.path)).toEqual([
      '/platform.module/iam.user/view-context',
      '/iam.user/view/user-1',
    ]);
    expect(requests.every((request) => request.headers === undefined)).toBe(true);
    expect(wrapper.text()).toContain('平台管理员');
    wrapper.unmount();
  });

  it('forwards a target mutation from a record-only renderer without replacing the source slot draft', async () => {
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) return viewContext() as never;
        return { id: 'user-1', title: '修改前' } as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: {
        default: ReferenceField,
        detail: ({ context, record, reportMutation }) =>
          h(
            'button',
            {
              'data-testid': 'save-target',
              onClick: () =>
                reportMutation({
                  targetModuleAlias: context.moduleAlias,
                  recordId: String(record.id),
                  type: 'saved',
                  record: { id: String(record.id), title: '修改后' },
                }),
            },
            '保存',
          ),
      },
      global: { stubs: { RecordDetailDrawer: { template: '<aside><slot /></aside>' } } },
    });

    await wrapper.get('button[title="查看 平台管理员"]').trigger('click');
    await flush();
    await wrapper.get('[data-testid="save-target"]').trigger('click');
    await flush();

    expect(wrapper.emitted('record-change')).toEqual([
      [expect.objectContaining({ type: 'saved', recordId: 'user-1', targetModuleAlias: 'iam.user' })],
    ]);
    expect(
      (wrapper.vm as unknown as { browser: { active: { value: { record: { title: string } } } } }).browser
        .active.value.record.title,
    ).toBe('修改后');
    wrapper.unmount();
  });

  it('turns a target projection into plain text after VIEW authorization is denied', async () => {
    const http: HttpClient = {
      async request() {
        throw new AppError('forbidden', { status: 403 });
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: ReferenceField },
      global: { stubs: { RecordDetailDrawer: { template: '<aside><slot /></aside>' } } },
    });

    await wrapper.get('button').trigger('click');
    await flush();

    expect(wrapper.find('button').exists()).toBe(false);
    expect(wrapper.text()).toContain('平台管理员');
    wrapper.unmount();
  });

  it('degrades only the record rejected by standard view scope', async () => {
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) return viewContext() as never;
        if (options.path.endsWith('/user-1')) throw new AppError('forbidden', { status: 403 });
        return { id: 'user-2', title: '乙详情' } as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: TwoReferenceFields },
      global: {
        stubs: {
          RecordDetailDrawer: { template: '<aside><slot /></aside>' },
          RecordDetailFields: { props: ['record'], template: '<p>{{ record.title }}</p>' },
        },
      },
    });

    await wrapper.get('button[title="查看 甲"]').trigger('click');
    await flush();
    expect(wrapper.find('button[title="查看 甲"]').exists()).toBe(false);
    await wrapper.get('button[title="查看 乙"]').trigger('click');
    await flush();

    expect(wrapper.text()).toContain('乙详情');
    wrapper.unmount();
  });

  it('does not restore a closed drawer from an older view response', async () => {
    let resolveView!: (record: { id: string; title: string }) => void;
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) return viewContext() as never;
        return new Promise((resolve) => {
          resolveView = resolve;
        }) as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: ReferenceField },
      global: { stubs: { RecordDetailDrawer: { template: '<aside><slot /></aside>' } } },
    });

    await wrapper.get('button').trigger('click');
    await flush();
    (wrapper.vm as unknown as { browser: { close(): void; active: { value: unknown } } }).browser.close();
    resolveView({ id: 'user-1', title: '过期详情' });
    await flush();

    expect(
      (wrapper.vm as unknown as { browser: { active: { value: unknown } } }).browser.active.value,
    ).toBeUndefined();
    wrapper.unmount();
  });

  it.each([
    [
      'success',
      (resolve: (value: { id: string; title: string }) => void, reject: (cause: unknown) => void) => {
        void reject;
        resolve({ id: 'user-1', title: '过期成功' });
      },
    ],
    [
      '403',
      (resolve: (value: { id: string; title: string }) => void, reject: (cause: unknown) => void) => {
        void resolve;
        reject(new AppError('forbidden', { status: 403 }));
      },
    ],
    [
      '500',
      (resolve: (value: { id: string; title: string }) => void, reject: (cause: unknown) => void) => {
        void resolve;
        reject(new AppError('failed', { status: 500 }));
      },
    ],
  ])('keeps a newer reference active when the earlier request ends with %s', async (_outcome, settleOld) => {
    let resolveOld!: (record: { id: string; title: string }) => void;
    let rejectOld!: (cause: unknown) => void;
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) return viewContext() as never;
        if (options.path.endsWith('/user-1')) {
          return new Promise((resolve, reject) => {
            resolveOld = resolve;
            rejectOld = reject;
          }) as never;
        }
        return { id: 'user-2', title: '新记录详情' } as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: TwoReferenceFields },
      global: {
        stubs: {
          RecordDetailDrawer: { template: '<aside><slot /></aside>' },
          RecordDetailFields: { props: ['record'], template: '<p>{{ record.title }}</p>' },
        },
      },
    });

    await wrapper.get('button[title="查看 甲"]').trigger('click');
    await flush();
    await wrapper.get('button[title="查看 乙"]').trigger('click');
    await flush();
    settleOld(resolveOld, rejectOld);
    await flush();

    expect(wrapper.text()).toContain('新记录详情');
    expect(wrapper.find('button[title="查看 甲"]').exists()).toBe(true);
    expect(wrapper.find('button[title="查看 乙"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('does not request a detail after unmounting during delayed VIEW context loading', async () => {
    let resolveContext!: (value: ReturnType<typeof viewContext>) => void;
    const request = vi.fn((options: { path: string }) => {
      if (options.path.endsWith('/view-context')) {
        return new Promise((resolve) => {
          resolveContext = resolve;
        });
      }
      return Promise.resolve({ id: 'user-1', title: '不应请求' });
    });
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http: { request } as HttpClient },
      slots: { default: ReferenceField },
      global: { stubs: { RecordDetailDrawer: { template: '<aside><slot /></aside>' } } },
    });

    await wrapper.get('button').trigger('click');
    await flush();
    wrapper.unmount();
    resolveContext(viewContext());
    await flush();

    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/platform.module/iam.user/view-context' }),
    );
  });

  it.each([
    [404, '记录已不存在，或你已无权查看。'],
    [500, '详情暂时无法加载，请关闭后重试。'],
  ])('distinguishes a %s view failure from a temporary error', async (status, description) => {
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) return viewContext() as never;
        throw new AppError('failed', { status });
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: ReferenceField },
      global: {
        stubs: {
          RecordDetailDrawer: { template: '<aside><slot /></aside>' },
          RecordPanelState: { props: ['description'], template: '<p>{{ description }}</p>' },
        },
      },
    });

    await wrapper.get('button').trigger('click');
    await flush();

    expect(wrapper.text()).toContain(description);
    wrapper.unmount();
  });

  it('opens an inline RecordDetailDrawer inside a standard workspace host', async () => {
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) {
          return viewContext() as never;
        }
        return { id: 'user-1', title: '抽屉详情' } as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http, inlineAnchor: true },
      slots: { default: ReferenceField },
      global: {
        stubs: {
          ADrawer: {
            name: 'ADrawer',
            props: ['getContainer'],
            template: '<aside data-testid="inline-reference-drawer"><slot /></aside>',
          },
          RecordDetailFields: { props: ['record'], template: '<p>{{ record.title }}</p>' },
        },
      },
    });

    await wrapper.get('button').trigger('click');
    await flush();

    expect(wrapper.findComponent({ name: 'ADrawer' }).props('getContainer')).toBe(false);
    expect(wrapper.get('[data-testid="inline-reference-drawer"]').text()).toContain('抽屉详情');
    wrapper.unmount();
  });

  it('uses the viewport side-panel contract for a public wrapper without a side-panel host', async () => {
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) {
          return viewContext() as never;
        }
        return { id: 'user-1', title: '公共抽屉详情' } as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: ReferenceField },
      global: {
        stubs: {
          UiSidePanel: {
            name: 'UiSidePanel',
            props: ['scope'],
            template: '<aside data-testid="viewport-reference-drawer"><slot /></aside>',
          },
          RecordDetailFields: { props: ['record'], template: '<p>{{ record.title }}</p>' },
        },
      },
    });

    await wrapper.get('button').trigger('click');
    await flush();

    expect(wrapper.findComponent({ name: 'UiSidePanel' }).props('scope')).toBe('viewport');
    expect(wrapper.get('[data-testid="viewport-reference-drawer"]').text()).toContain('公共抽屉详情');
    wrapper.unmount();
  });

  it('retries a failed view-context request and describes temporary detail failures accurately', async () => {
    let viewContextAttempts = 0;
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) {
          viewContextAttempts += 1;
          if (viewContextAttempts === 1) throw new AppError('network', { status: 500 });
          return viewContext() as never;
        }
        return { id: 'user-1', title: '重试成功' } as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: ReferenceField },
      global: {
        stubs: {
          RecordDetailDrawer: { template: '<aside><slot /></aside>' },
          RecordPanelState: { props: ['description'], template: '<p>{{ description }}</p>' },
          RecordDetailFields: { props: ['record'], template: '<p>{{ record.title }}</p>' },
        },
      },
    });

    await wrapper.get('button').trigger('click');
    await flush();
    expect(wrapper.text()).toContain('详情暂时无法加载，请关闭后重试。');

    await wrapper.get('button').trigger('click');
    await flush();

    expect(viewContextAttempts).toBe(2);
    expect(wrapper.text()).toContain('重试成功');
    wrapper.unmount();
  });

  it('forces teardown of a busy reference session so late target work cannot keep a drawer active', async () => {
    const http: HttpClient = {
      async request(options) {
        if (options.path.endsWith('/view-context')) return viewContext() as never;
        return { id: 'user-1', title: '待保存目标' } as never;
      },
    };
    const wrapper = mount(ReferenceRecordDetailBrowser, {
      props: { http },
      slots: { default: ReferenceField },
      global: { stubs: { RecordDetailDrawer: { template: '<aside><slot /></aside>' } } },
    });

    await wrapper.get('button').trigger('click');
    await flush();
    const browser = wrapper.vm.browser;
    browser.setBusy('iam.user', 'user-1', true);
    browser.close();
    expect(browser.active.value?.recordId).toBe('user-1');

    wrapper.unmount();
    expect(browser.active.value).toBeUndefined();
  });
});
