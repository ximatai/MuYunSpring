import { mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { beforeAll, describe, expect, it } from 'vitest';
import { commands, page } from 'vitest/browser';
import { actionResultData, createHttpClient, type HttpClient } from '@muyun/web-core';
import type { CurrentUser, WebActionResultEnvelope } from '@muyun/web-contracts';
import { clearAuthToken, saveAuthToken } from '@/platform-admin-runtime/authSession';
import { clearWorkbenchSessionTabs } from '@/app/workbenchSessionTabs';
import '@/styles.css';

declare const __SCHOOL_LAYOUT_ENABLED__: boolean;
declare const __PLATFORM_BUSINESS_TOKEN__: string;

const subjectModule = 'education.subject_category';
const classroomModule = 'education.classroom';
const tree = '.module-tree-workspace .tree-record-explorer .ui-tree';
const classroomWorkspace = '.module-flat-management-workspace';
const list = `${classroomWorkspace} .record-list-explorer`;
const detail = '.record-detail-panel-region';
const detailContent = `${classroomWorkspace} ${detail} .record-detail-layout-content`;
const subjectName = 'TD-040 学科 45';
const classroomName = 'TD-040 班级 01';
const wait = { timeout: 8000 };

// Run against a disposable :muyun-boot:demoBootRun database with a demo tenant administrator
// session issued after the initial password change. Export MUYUN_SCHOOL_LAYOUT_SERVICE=1,
// MUYUN_PLATFORM_SERVICE_URL (the origin, without /api), and MUYUN_PLATFORM_BUSINESS_TOKEN.
// npm run test:browser -- tests/app/SchoolWorkbenchLayout.browser.test.ts
describe.skipIf(!__SCHOOL_LAYOUT_ENABLED__)('school-demo workbench layout', () => {
  beforeAll(async () => {
    expect(__PLATFORM_BUSINESS_TOKEN__, 'Set MUYUN_PLATFORM_BUSINESS_TOKEN').not.toBe('');
    const http = createHttpClient({ baseUrl: '/api', token: __PLATFORM_BUSINESS_TOKEN__ });
    await expect(http.request<CurrentUser>({ path: '/iam.auth/context' })).resolves.toMatchObject({
      tenantId: 'demo',
      system: false,
    });

    for (const [moduleAlias, template] of [
      [subjectModule, 'TREE_MANAGEMENT'],
      [classroomModule, 'FLAT_MANAGEMENT'],
    ]) {
      await expect(http.request({ path: `/platform.module/${moduleAlias}/context` })).resolves.toMatchObject({
        moduleAlias,
        uiDescriptor: { page: { template } },
      });
    }

    await ensureRecords(http, subjectModule, '学科', 45, (number) => ({
      code: `TD040-S-${number}`,
    }));
    const students = await ensureRecords(http, 'education.student', '学生', 45, (number) => ({
      studentNo: `TD040-S-${number}`,
      grade: '高一',
    }));
    await ensureRecords(http, classroomModule, '班级', 40, (number) => ({
      classCode: `TD040-C-${number}`,
      academicYear: '2026',
      homeroomTeacherId: 'demo_teacher_1001',
      ...(number === '01' ? { members: students.map(({ id }) => ({ studentId: id })) } : {}),
    }));
  }, 60000);

  it.each([1440, 980])(
    'bounds tree, list and detail at %ipx in the real workbench',
    async (width) => {
      await withMountedWorkbench(width, async () => {
        await openSchoolMenu('学科分类');
        await expect.poll(() => location.pathname.endsWith('/education/subject-category'), wait).toBe(true);
        const lastSubject = page.getByText(subjectName, { exact: true });
        await expect.element(lastSubject, wait).toBeVisible();
        await wheelInside(tree);
        await lastSubject.click(wait);
        await expect
          .poll(
            () => document.querySelector(`.module-tree-workspace ${detail} h2`)?.textContent?.trim(),
            wait,
          )
          .toBe(subjectName);
        await expect.element(page.getByText('TD040-S-45', { exact: true }), wait).toBeVisible();
        await expectBounded('.module-tree-workspace');

        await openSchoolMenu('班级管理');
        await expect.poll(() => location.pathname.endsWith('/education/classroom'), wait).toBe(true);
        const classroom = (await locator(list)).getByText(classroomName, { exact: true });
        await expect.element(classroom, wait).toBeVisible();
        await wheelInside(list);
        await classroom.click(wait);
        await expect
          .element(await locator(`${classroomWorkspace} ${detail} h2`), wait)
          .toHaveTextContent(classroomName);
        await expect
          .poll(() => document.querySelectorAll(`${detailContent} table tbody tr`).length, wait)
          .toBe(45);
        await expectBounded(classroomWorkspace);

        // Exercise the workbench's compact fallback at the breakpoint with an
        // actual expanded sidebar preference, while keeping the same detail open.
        const expandMenu = page.getByRole('button', { name: '展开侧栏菜单', exact: true });
        const collapseMenu = page.getByRole('button', { name: '收敛侧栏菜单', exact: true });
        if (width === 980) await page.viewport(1440, 814);
        await expect
          .poll(() => expandMenu.query() !== null || collapseMenu.query() !== null, wait)
          .toBe(true);
        if (expandMenu.query()) await expandMenu.click(wait);
        await expect.element(collapseMenu, wait).toBeVisible();
        if (width === 980) {
          await page.viewport(width, 814);
          await expect
            .element(page.getByRole('button', { name: '系统菜单', exact: true }), wait)
            .toBeVisible();
          await expect.element(collapseMenu, wait).not.toBeInTheDocument();
        }
        await expect.element(classroom, wait).toBeVisible();
        await expectBounded(classroomWorkspace);

        const header = await element(`${classroomWorkspace} ${detail} .management-panel-header`);
        const headerTop = header.getBoundingClientRect().top;
        await wheelInside(detailContent);
        expect(header.getBoundingClientRect().top).toBeCloseTo(headerTop, 0);
        const edit = page.elementLocator(header).getByRole('button', { name: /编\s*辑/, exact: true });
        await edit.click(wait);
        const cancel = page.getByRole('button', { name: /取\s*消/, exact: true });
        await expect.element(cancel, wait).toBeVisible();
        await cancel.click(wait);
        await expect.element(page.getByRole('button', { name: /编\s*辑/, exact: true }), wait).toBeVisible();

        if (width === 980) await page.viewport(1440, 814);
        await collapseMenu.click(wait);
      });
    },
    45000,
  );

  // The menu URL identifies the module; an inline tree selection is page-local state.
  it('cold-starts the subject tree layout from the real menu URL', async () => {
    const { menuUrl, restoredUrl, layout } = await commands.restoreSchoolSubjectMenu();
    expect(new URL(menuUrl).pathname).toMatch(/\/education\/subject-category$/);
    expect(restoredUrl).toBe(menuUrl);
    expect(layout.treeHeight).toBeGreaterThan(100);
    expect(layout.treeScrollHeight).toBeGreaterThan(layout.treeHeight + 150);
    expect(layout.workspaceBottom).toBeLessThanOrEqual(layout.hostBottom + 1);
    expect(layout.workspaceHorizontalOverflow).toBeLessThanOrEqual(1);
  }, 45000);
});

async function withMountedWorkbench(width: number, run: () => Promise<void>) {
  await page.viewport(width, 814);
  const original = { url: location.href, state: history.state };
  history.replaceState(null, '', import.meta.env.BASE_URL);
  saveAuthToken(__PLATFORM_BUSINESS_TOKEN__);
  clearWorkbenchSessionTabs();

  const { default: App } = await import('@/App.vue');
  const { router } = await import('@/app/router');
  const { VueQueryPlugin, queryClient } = await import('@/app/query');
  const root = document.createElement('div');
  root.id = 'app';
  document.body.append(root);
  const wrapper = mount(App, {
    attachTo: root,
    global: { plugins: [createPinia(), router, [VueQueryPlugin, { queryClient }]] },
  });
  try {
    await router.isReady();
    await router.replace('/');
    await expect.element(page.getByRole('button', { name: /账号菜单.*demo_admin/ }), wait).toBeVisible();
    const collapseMenu = page.getByRole('button', { name: '收敛侧栏菜单', exact: true });
    if (collapseMenu.query()) await collapseMenu.click(wait);
    await run();
  } finally {
    try {
      await router.replace('/');
    } finally {
      wrapper.unmount();
      root.remove();
      queryClient.clear();
      clearAuthToken();
      clearWorkbenchSessionTabs();
      history.replaceState(original.state, '', original.url);
    }
  }
}

async function openSchoolMenu(title: string) {
  await page.getByRole('button', { name: '系统菜单', exact: true }).click(wait);
  await page.getByRole('button', { name: '平台管理', exact: true }).hover(wait);
  await page.getByRole('button', { name: title, exact: true }).click(wait);
}

async function wheelInside(selector: string) {
  const owner = await element(selector);
  await expect.poll(() => owner.clientHeight, wait).toBeGreaterThan(100);
  await expect.poll(() => owner.scrollHeight - owner.clientHeight, wait).toBeGreaterThan(150);
  expect(getComputedStyle(owner).overflowY).toMatch(/^(auto|scroll)$/);
  owner.scrollTop = 0;
  await commands.treeWheel(selector, 480);
  await expect.poll(() => owner.scrollTop, wait).toBeGreaterThan(0);
  expect((await element('.app-content')).scrollTop).toBe(0);
  expect(document.scrollingElement?.scrollTop).toBe(0);
}

async function expectBounded(selector: string) {
  const workspace = await element(selector);
  const host = await element('.app-content');
  await expect
    .poll(() => workspace.getBoundingClientRect().bottom, wait)
    .toBeLessThanOrEqual(host.getBoundingClientRect().bottom + 1);
  await expect.poll(() => workspace.scrollWidth - workspace.clientWidth, wait).toBeLessThanOrEqual(1);
}

async function element(selector: string) {
  await expect.poll(() => document.querySelector(selector) !== null, wait).toBe(true);
  return document.querySelector<HTMLElement>(selector)!;
}

async function locator(selector: string) {
  return page.elementLocator(await element(selector));
}

interface SchoolRecord {
  id: string;
  title: string;
}

async function ensureRecords(
  http: HttpClient,
  alias: string,
  label: string,
  count: number,
  fields: (number: string) => Record<string, unknown>,
) {
  const { records } = await http.request<{ records: SchoolRecord[] }>({
    method: 'POST',
    path: `/${alias}/query`,
    body: { page: { pageNum: 1, pageSize: 100 } },
  });
  const byTitle = new Map(records.map((record) => [record.title, record]));
  const fixture: SchoolRecord[] = [];
  for (let index = 1; index <= count; index++) {
    const number = String(index).padStart(2, '0');
    const title = `TD-040 ${label} ${number}`;
    const existing = byTitle.get(title);
    fixture.push(
      existing ??
        actionResultData(
          await http.request<WebActionResultEnvelope<SchoolRecord>>({
            method: 'POST',
            path: `/${alias}/insert`,
            body: { title, ...fields(number) },
          }),
        ),
    );
  }
  return fixture;
}
