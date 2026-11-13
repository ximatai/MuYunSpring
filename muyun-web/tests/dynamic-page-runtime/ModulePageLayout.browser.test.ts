import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import ModulePageHost from '@/dynamic-page-runtime/ModulePageHost.vue';
import { configureModuleContext, createHttpClient } from '@muyun/web-core';
import type { ModulePageSessionView } from '@/dynamic-page-runtime/useModulePageSession';
import { configureModulePageEnhancements } from '@/dynamic-page-runtime/modulePageEnhancements';
import '@/styles.css';

// Real standard Host, template, list/tree and detail components; only HTTP is replaced.
// This checks viewport geometry, not the backend's own descriptor compilation or authorization.
it.each([1440, 980])(
  'keeps standard application, module and position layouts bounded at %ipx',
  async (width) => {
    await page.viewport(width, 814);
    for (const [alias, template, tree] of [
      ['platform.application', 'FLAT_MANAGEMENT', false],
      ['platform.module', 'TREE_MANAGEMENT', true],
      ['iam.position', 'LIST_DETAIL_CARD', false],
    ] as const) {
      const records = Array.from({ length: 45 }, (_, index) => ({
        id: `record-${index}`,
        title: `样板记录 ${index + 1}`,
        parentId: null,
        sortOrder: index,
        ...Object.fromEntries(
          Array.from({ length: 24 }, (_, field) => [`field${field}`, `较长的业务内容 ${field} `.repeat(5)]),
        ),
      }));
      const fields = Array.from({ length: 24 }, (_, field) => ({
        fieldRef: { fieldName: `field${field}` },
        label: `业务字段 ${field}`,
        valueType: 'STRING',
        visible: { constant: true },
        readOnly: { constant: false },
      }));
      const runtime = {
        moduleAlias: alias,
        title: '布局样板',
        moduleKind: 'STATIC',
        abilities: tree ? ['crud', 'tree', 'reference'] : ['crud'],
        capabilities: tree ? ['TREE'] : [],
        actions: ['view', 'query', 'reference'].map((actionCode) => ({ actionCode, authorized: true })),
        uiDescriptor: {
          moduleAlias: alias,
          recordLabelField: 'title',
          page: {
            template,
            ...(template === 'FLAT_MANAGEMENT'
              ? {
                  navigator: {
                    levels: [
                      {
                        key: 'organization',
                        kind: 'MICRO_LIST',
                        sourceModuleAlias: 'iam.organization',
                        title: '机构',
                        singleResultPolicy: 'AUTO_SELECT_AND_HIDE',
                      },
                    ],
                    contextBindings: [],
                  },
                }
              : {}),
            traits: ['STANDARD_CRUD'],
            sortPartitionFields: [],
            explorer: { labelField: 'title' },
            list: {
              fields: {
                viewCode: 'list',
                viewKind: 'LIST',
                fields: [{ fieldRef: { fieldName: 'title' }, label: '名称', valueType: 'STRING' }],
              },
            },
            detail: { editor: { viewCode: 'form', viewKind: 'FORM', fields } },
          },
        },
      };
      const queries: string[] = [];
      vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
        const request = new Request(input, init);
        const path = new URL(request.url).pathname;
        queries.push(path);
        if (path.endsWith('/context')) return Response.json(runtime);
        if (path.includes('/iam.organization/') && path.endsWith('/query'))
          return Response.json({
            records: [{ id: 'only-organization', title: '唯一机构' }],
            total: 1,
            pageNum: 1,
            pageSize: 200,
            pages: 1,
            totalKnown: true,
          });
        if (path.endsWith('/reference-context'))
          return Response.json({
            moduleAlias: 'iam.organization',
            abilities: ['crud'],
            capabilities: [],
            actions: [],
          });
        if (path.includes('/view/')) return Response.json(records[0]);
        if (path.includes('/actions'))
          return Response.json({ actions: [{ actionCode: 'view', available: true }] });
        if (path.endsWith('/query/schema'))
          return Response.json({
            scopeName: alias,
            quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
            fields: [],
            externalCriteria: [],
            defaultSorts: [],
          });
        if (/\/tree(?:\/query)?$/.test(path))
          return Response.json({
            records: records.map((record) => ({ record, children: [] })),
            total: records.length,
          });
        if (path.endsWith('/query'))
          return Response.json({
            records,
            total: records.length,
            pageNum: 1,
            pageSize: 50,
            pages: 1,
            totalKnown: true,
          });
        return Response.json([]);
      });
      configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
      configureModulePageEnhancements([
        {
          id: 'default-range-review',
          target: { moduleAlias: 'platform.application' },
          navigator: { emptySelectionLabel: () => '默认范围' },
        },
      ]);
      const host = document.createElement('div');
      host.style.cssText = `width:${width - 32}px;height:650px;position:relative;overflow:hidden`;
      document.body.append(host);
      const wrapper = mount(ModulePageHost, {
        attachTo: host,
        props: {
          descriptor: {
            pageType: 'dynamic-module',
            openMode: 'dynamic-runner',
            hostType: 'module-page-host',
            tabPolicy: { identity: 'by-menu' },
            target: { moduleAlias: alias, pageMode: 'LIST' },
          },
        },
      });
      // Vue Test Utils inserts a mount point; give it the same bounded height as the workbench outlet.
      (host.firstElementChild as HTMLElement).style.height = '100%';
      try {
        await expect.poll(() => wrapper.find('.management-workspace').exists()).toBe(true);
        await flushPromises();
        await expect.poll(() => wrapper.text().includes('样板记录 1')).toBe(true);
        const session = wrapper
          .findComponent({ name: 'ModulePageHostRuntime' })
          .props('session') as ModulePageSessionView;
        await session.openFlatManagementRecord(records[0]);
        await flushPromises();
        await expect
          .poll(
            () => wrapper.findAll('.record-detail-field').length || wrapper.text().includes('业务字段 23'),
          )
          .toBeTruthy();
        const workspace = wrapper.get('.management-workspace').element as HTMLElement;
        expect(workspace.clientHeight).toBeGreaterThan(200);
        expect(workspace.getBoundingClientRect().bottom, alias).toBeLessThanOrEqual(
          host.getBoundingClientRect().bottom + 1,
        );
        expect(workspace.scrollWidth).toBeLessThanOrEqual(workspace.clientWidth + 1);
        if (template === 'FLAT_MANAGEMENT') {
          expect(session.selectedNavigatorRecords.organization).toBeUndefined();
          expect(wrapper.find('.page-navigator-default-range').exists()).toBe(true);
          await expect
            .poll(() => wrapper.findAll('.management-explorer-column--collapsed').length)
            .toBe(width === 980 ? 1 : 0);
          expect(
            wrapper.get('.record-detail-panel-region').element.getBoundingClientRect().right,
          ).toBeLessThanOrEqual(host.getBoundingClientRect().right + 1);
        }
        const scrollOwners = [...host.querySelectorAll<HTMLElement>('*')].filter(
          (element) =>
            /auto|scroll/.test(getComputedStyle(element).overflowY) &&
            element.scrollHeight > element.clientHeight + 1,
        );
        expect(scrollOwners.length, `${alias}: long list/detail needs a scroll owner`).toBeGreaterThan(0);
        const scrollOwner = scrollOwners[scrollOwners.length - 1]!;
        scrollOwner.scrollTop = 120;
        expect(scrollOwner.scrollTop).toBeGreaterThan(0);
        expect(queries.some((path) => path.includes('/view/'))).toBe(true);
      } finally {
        wrapper.unmount();
        host.remove();
        vi.unstubAllGlobals();
        configureModulePageEnhancements([]);
      }
    }
  },
);
