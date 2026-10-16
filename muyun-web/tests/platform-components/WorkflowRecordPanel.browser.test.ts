import { mount } from '@vue/test-utils';
import { h } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import type { HttpClient, HttpRequestOptions, ModuleContext } from '@muyun/web-core';
import WorkflowRecordPanel from '@/platform-components/WorkflowRecordPanel.vue';
import UiThemeProvider from '@/vue-ui-antdv/components/UiThemeProvider.vue';
import UiSidePanelHost from '@/vue-ui-antdv/components/UiSidePanelHost.vue';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

// Feedback notices have their own adapter tests and outlive a mounted page.
vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/vue-ui-antdv')>()),
  showSuccessMessage: vi.fn(),
  showErrorMessage: vi.fn(),
}));

// Server decisions are supplied by HTTP; the panel, fields and adapters are real.
// Runtime transactions are verified separately by repository integration tests.
function fixture(initial: 'unsubmitted' | 'processing' | 'settlement') {
  let phase: string = initial,
    failSave = false;
  const writes: Array<{ path: string; body: unknown }> = [];
  const record = { id: 'expense', version: 3, costCenter: '教学', settled: false, settlementNote: '' };
  const action = (actionCode: string, title: string, reasonRequired = false) => ({
    actionCode,
    title,
    reasonRequired,
    taskId: 'task',
    nodeKey: 'review',
    nodeTitle: '费用审批',
  });
  const http: HttpClient = {
    async request<T>({ path, method, body }: HttpRequestOptions) {
      let result: unknown;
      if (path.endsWith('/submit/status'))
        result = {
          displayStatus:
            phase === 'unsubmitted'
              ? 'UNSUBMITTED'
              : phase === 'rejected'
                ? 'REJECTED'
                : ['settlement', 'completed'].includes(phase)
                  ? 'APPROVED'
                  : 'PROCESSING',
          canSubmit: ['unsubmitted', 'rejected'].includes(phase),
          instanceId: phase === 'unsubmitted' ? undefined : 'instance',
        };
      else if (path.endsWith('/bundle') || path.endsWith('/submit/preview'))
        result = {
          // Resubmission selects a new definition; status has no definition title.
          definition: { definitionTitle: '报销审批与结算' },
          instance: {
            id: 'instance',
            moduleAlias: 'test.expense',
            recordId: 'expense',
            instanceStatus: phase === 'completed' ? 'completed' : 'running',
          },
          nodes: [],
          routes: [],
          tasks: [],
        };
      else if (path.endsWith('/actions'))
        result = {
          records:
            phase === 'processing'
              ? [action('approve', '通过'), action('reject', '驳回', true)]
              : phase === 'settlement'
                ? [{ ...action('complete', '完成任务'), nodeTitle: '办理结算' }]
                : [],
        };
      else if (path.endsWith('/prepare'))
        result = {
          evaluation: {
            passed: false,
            checkResults: [],
            guides: [
              {
                guideKey: 'settle',
                guideKind: 'open_form',
                title: '登记结算结果',
                guideConfigText: '{"editableFields":["settled","settlementNote"]}',
              },
            ],
          },
        };
      else if (method === 'POST' && /\/actions\/(submitApproval|approve|reject)$/.test(path)) {
        writes.push({ path, body });
        phase = path.endsWith('/reject')
          ? 'rejected'
          : path.endsWith('/approve')
            ? 'settlement'
            : 'processing';
      } else if (path.endsWith('/execute')) {
        writes.push({ path, body });
        if (failSave) throw new Error('业务记录版本已变化，请刷新后重试');
        Object.assign(record, (body as { values: object }).values);
        phase = 'completed';
      } else if (
        /\/(history\/query|tasks(?:\/view)?|events(?:\/view)?|add-sign-explanations|manual-branches)$/.test(
          path,
        )
      )
        result = { records: [] };
      else throw new Error(`Unexpected workflow request: ${method ?? 'GET'} ${path}`);
      return result as T;
    },
  };
  const context = {
    moduleAlias: 'test.expense',
    http,
    crud: { view: async () => ({ ...record }) },
    runtime: {
      ready: Promise.resolve({
        uiDescriptor: {
          defaultEditor: {
            fields: [
              { fieldRef: { fieldName: 'costCenter' }, label: '费用归属', valueType: 'STRING' },
              { fieldRef: { fieldName: 'settled' }, label: '已结算', uiType: 'switch', valueType: 'BOOLEAN' },
              { fieldRef: { fieldName: 'settlementNote' }, label: '结算说明', valueType: 'STRING' },
            ],
          },
        },
      }),
    },
  } as unknown as ModuleContext<Record<string, unknown>>;
  const wrapper = mount(UiThemeProvider, {
    attachTo: document.body,
    slots: {
      default: () =>
        h(UiSidePanelHost, { style: 'height:700px;width:100%' }, () =>
          h(WorkflowRecordPanel, { context, recordId: 'expense' }),
        ),
    },
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  return {
    wrapper,
    writes,
    record,
    failNextSave: () => {
      failSave = true;
    },
    allowSave: () => {
      failSave = false;
    },
  };
}
let unmount: (() => void) | undefined;
afterEach(() => {
  unmount?.();
  unmount = undefined;
});

it('previews the selected definition, submits and advances approval to a business task', async () => {
  await page.viewport(1920, 1080);
  const f = fixture('unsubmitted');
  unmount = () => f.wrapper.unmount();
  await page.getByRole('button', { name: '预览并提交审批', exact: true }).click();
  await expect.element(page.getByText(/流程：报销审批与结算/)).toBeVisible();
  await page.getByRole('button', { name: '确认提交审批', exact: true }).click();
  await page.getByRole('button', { name: '费用审批 · 通过', exact: true }).click();
  await page.getByRole('textbox', { name: '操作意见', exact: true }).fill('同意报销');
  await page.getByRole('button', { name: '确认通过', exact: true }).click();
  await expect.element(page.getByRole('button', { name: '办理结算 · 办理任务', exact: true })).toBeVisible();
  expect(f.writes.map((write) => write.path)).toEqual([
    '/workflow/runtime/record/test.expense/expense/actions/submitApproval',
    '/workflow/runtime/task/task/actions/approve',
  ]);
});

it('requires a rejection reason and shows the selected definition on resubmission', async () => {
  await page.viewport(1920, 1080);
  const f = fixture('processing');
  unmount = () => f.wrapper.unmount();
  await page.getByRole('button', { name: '费用审批 · 驳回', exact: true }).click();
  await page.getByRole('button', { name: '确认驳回', exact: true }).click();
  await expect.element(page.getByRole('heading', { name: '驳回', exact: true })).toBeVisible();
  expect(f.writes).toHaveLength(0);
  await page.getByRole('textbox', { name: '操作意见', exact: true }).fill('补齐费用用途');
  await page.getByRole('button', { name: '确认驳回', exact: true }).click();
  expect(f.writes[0]?.body).toEqual(
    expect.objectContaining({ reason: '补齐费用用途', rejectResubmitMode: 'restart' }),
  );
  await page.getByRole('button', { name: '预览并提交审批', exact: true }).click();
  await expect.element(page.getByText(/流程：报销审批与结算/)).toBeVisible();
  await page.getByRole('button', { name: '确认提交审批', exact: true }).click();
  await expect.element(page.getByRole('button', { name: '费用审批 · 通过', exact: true })).toBeVisible();
});

it('retains failed business input and keeps completion visible on smaller desktops', async () => {
  await page.viewport(1366, 768);
  const f = fixture('settlement');
  unmount = () => f.wrapper.unmount();
  await page.getByRole('button', { name: '办理结算 · 办理任务', exact: true }).click();
  await page.getByRole('button', { name: '登记结算结果', exact: true }).click();
  await page.getByRole('switch', { name: '已结算', exact: true }).click();
  await page.getByRole('textbox', { name: '结算说明', exact: true }).fill('已登记付款凭证');
  await expect.element(page.getByRole('textbox', { name: '费用归属', exact: true })).not.toBeInTheDocument();
  const save = page.getByRole('button', { name: '保存业务并完成任务', exact: true });
  await expect.element(save).toBeVisible();
  expect(
    f.wrapper.get('.record-detail-layout-operation').element.getBoundingClientRect().bottom,
  ).toBeLessThanOrEqual(768);
  f.failNextSave();
  await save.click();
  await expect
    .element(page.getByRole('textbox', { name: '结算说明', exact: true }))
    .toHaveValue('已登记付款凭证');
  f.allowSave();
  await save.click();
  await expect
    .element(page.getByRole('heading', { name: '办理业务任务', exact: true }))
    .not.toBeInTheDocument();
  expect(f.record).toMatchObject({ costCenter: '教学', settled: true, settlementNote: '已登记付款凭证' });
  expect(f.writes.at(-1)?.body).toEqual(
    expect.objectContaining({ version: 3, values: { settled: true, settlementNote: '已登记付款凭证' } }),
  );
});
