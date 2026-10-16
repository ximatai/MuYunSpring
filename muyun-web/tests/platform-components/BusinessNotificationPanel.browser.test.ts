import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import BusinessNotificationPanel from '@/platform-components/BusinessNotificationPanel.vue';
import RecordDetailDrawer from '@/platform-components/RecordDetailDrawer.vue';
import { UiButton, UiModal, UiSidePanelHost, useUiBlockingOverlayState } from '@/vue-ui-antdv';
import '@/styles.css';

it('keeps an overlapping reminder from intercepting a scoped drawer footer and restores it after closing', async () => {
  const open = ref(false);
  const approve = vi.fn(() => (open.value = false));
  const executeAction = vi.fn();
  const Harness = defineComponent({
    setup: () => () =>
      h('div', [
        h('div', { style: 'position:relative;isolation:isolate;height:100vh' }, [
          h(
            RecordDetailDrawer,
            { open: open.value, title: '审批办理', renderMode: 'inline' },
            {
              default: () => h('p', '待办内容'),
              operation: () =>
                h('div', { style: 'display:flex;justify-content:flex-end' }, [
                  h(UiButton, { onClick: approve }, () => '确认通过'),
                ]),
            },
          ),
        ]),
        h(BusinessNotificationPanel, {
          notifications: [
            {
              id: 'workflow-reminder',
              code: 'workflow.task',
              title: '有新的审批或业务待办',
              content: '请查看工作台中的待办事项。',
              dismissible: true,
              actions: [
                {
                  kind: 'navigate',
                  key: 'workbench',
                  label: '查看待办',
                  moduleAlias: 'iam.workflow_workbench',
                  dismissOnSuccess: false,
                },
              ],
            },
          ],
          executeAction,
        }),
      ]),
  });
  const wrapper = mount(Harness, {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  await page.getByRole('button', { name: '查看待办', exact: true }).click();
  expect(executeAction).toHaveBeenCalledTimes(1);
  const reminder = wrapper.get('.business-notification-card').element.getBoundingClientRect();
  open.value = true;
  await expect.element(page.getByRole('button', { name: '确认通过', exact: true })).toBeVisible();
  await expect
    .poll(() => getComputedStyle(wrapper.get('.business-notification-panel').element).display)
    .toBe('none');
  await expect
    .element(page.getByRole('button', { name: '查看待办', exact: true, includeHidden: true }))
    .not.toBeVisible();
  await expect
    .poll(() => wrapper.get('.ant-drawer-content-wrapper').element.getBoundingClientRect().right)
    .toBeLessThanOrEqual(document.documentElement.clientWidth + 1);
  const button = wrapper.get('.record-detail-layout-operation button').element;
  const bounds = button.getBoundingClientRect();
  const x = bounds.left + bounds.width / 2;
  const y = bounds.top + bounds.height / 2;
  expect(x).toBeGreaterThan(reminder.left);
  expect(x).toBeLessThan(reminder.right);
  expect(y).toBeGreaterThan(reminder.top);
  expect(y).toBeLessThan(reminder.bottom);
  expect(button.contains(document.elementFromPoint(x, y))).toBe(true);
  await page.getByRole('button', { name: '确认通过', exact: true }).click();
  expect(approve).toHaveBeenCalledTimes(1);
  expect(executeAction).toHaveBeenCalledTimes(1);
  await page.getByRole('button', { name: '查看待办', exact: true }).click();
  expect(executeAction).toHaveBeenCalledTimes(2);
  expect(wrapper.find('.business-notification-panel').exists()).toBe(true);
  await page.getByRole('button', { name: '查看待办', exact: true }).hover();
  const close = wrapper.get('.business-notification-close').element;
  await expect.poll(() => getComputedStyle(close).pointerEvents).toBe('auto');
  const closeBounds = close.getBoundingClientRect();
  expect(
    close.contains(
      document.elementFromPoint(
        closeBounds.left + closeBounds.width / 2,
        closeBounds.top + closeBounds.height / 2,
      ),
    ),
  ).toBe(true);
  await page.getByRole('button', { name: '关闭提醒', exact: true }).click();
  expect(wrapper.findComponent(BusinessNotificationPanel).emitted('dismiss')).toEqual([
    ['workflow-reminder'],
  ]);
});

it('ignores an open drawer in a hidden consumer host and retains reminders arriving during foreground handling', async () => {
  const hostVisible = ref(false);
  const notifications = ref([
    {
      id: 'first',
      code: 'workflow.task',
      title: '首条待办',
      content: '提醒正文',
      dismissible: true,
      actions: [],
    },
  ]);
  const Harness = defineComponent({
    setup: () => () =>
      h('div', [
        h('div', { style: hostVisible.value ? 'height:100vh;position:relative' : 'display:none' }, [
          h(RecordDetailDrawer, { open: true, title: '隐藏宿主中的办理', renderMode: 'inline' }),
        ]),
        h(BusinessNotificationPanel, { notifications: notifications.value, executeAction: () => undefined }),
      ]),
  });
  const wrapper = mount(Harness, {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  await expect.poll(() => wrapper.find('.ant-drawer-open').exists()).toBe(true);
  await expect.element(page.getByText('首条待办', { exact: true })).toBeVisible();
  hostVisible.value = true;
  await expect
    .poll(() => wrapper.get('.business-notification-panel').element.hasAttribute('hidden'))
    .toBe(true);
  notifications.value.push({
    id: 'second',
    code: 'workflow.task',
    title: '办理中到达的待办',
    content: '提醒正文',
    dismissible: true,
    actions: [],
  });
  await expect.poll(() => wrapper.findAll('.business-notification-card').length).toBe(1);
  hostVisible.value = false;
  await expect.element(page.getByText('首条待办', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '查看全部（还有 1 条）', exact: true }).click();
  await expect.element(page.getByText('办理中到达的待办', { exact: true })).toBeVisible();
});

it('defers all reminder controls while a standard modal is open and restores the retained queue on confirmation', async () => {
  const open = ref(false);
  const executeAction = vi.fn();
  const Harness = defineComponent({
    setup: () => () =>
      h('div', [
        h(
          UiModal,
          {
            open: open.value,
            title: '审批确认',
            confirmText: '确认办理',
            onConfirm: () => (open.value = false),
          },
          () => '确认当前办理结果',
        ),
        h(BusinessNotificationPanel, {
          notifications: [
            {
              id: 'modal-reminder',
              code: 'workflow.task',
              title: '新的待办提醒',
              content: '提醒正文',
              dismissible: true,
              actions: [
                {
                  kind: 'navigate',
                  key: 'workbench',
                  label: '查看提醒事项',
                  moduleAlias: 'iam.workflow_workbench',
                  dismissOnSuccess: false,
                },
              ],
            },
          ],
          executeAction,
        }),
      ]),
  });
  const wrapper = mount(Harness, {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  await page.getByRole('button', { name: '查看提醒事项', exact: true }).click();
  open.value = true;
  await expect
    .poll(() => wrapper.get('.business-notification-panel').element.hasAttribute('hidden'))
    .toBe(true);
  await expect
    .element(page.getByRole('button', { name: '查看提醒事项', exact: true, includeHidden: true }))
    .not.toBeVisible();
  await page.getByRole('button', { name: '确认办理', exact: true }).click();
  await page.getByRole('button', { name: '查看提醒事项', exact: true }).click();
  expect(executeAction).toHaveBeenCalledTimes(2);
  expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
});

it('tracks portal drawer hosts and restores reminders only after the final overlay closes, including empty queues and unmount', async () => {
  const hostVisible = ref(false);
  const drawerOpen = ref(false);
  const modalOpen = ref(false);
  const executeAction = vi.fn();
  const notifications = ref([
    {
      id: 'portal',
      code: 'workflow.task',
      title: '保留的提醒',
      content: '正文',
      dismissible: true,
      actions: [
        {
          kind: 'navigate' as const,
          key: 'todo',
          label: '办理提醒',
          moduleAlias: 'iam.workflow_workbench',
          dismissOnSuccess: false,
        },
      ],
    },
  ]);
  const state = useUiBlockingOverlayState();
  const Harness = defineComponent({
    setup: () => () =>
      h('div', [
        h('div', { style: hostVisible.value ? 'height:100vh' : 'display:none' }, [
          h(UiSidePanelHost, {}, () =>
            h(RecordDetailDrawer, { open: drawerOpen.value, title: 'Portal办理' }, () => '办理内容'),
          ),
        ]),
        h(
          UiModal,
          {
            open: modalOpen.value,
            title: '叠加确认',
            confirmText: '结束确认',
            onConfirm: () => (modalOpen.value = false),
          },
          () => '确认内容',
        ),
        h(BusinessNotificationPanel, { notifications: notifications.value, executeAction }),
      ]),
  });
  const wrapper = mount(Harness, {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  await nextTick();
  drawerOpen.value = true;
  await expect.poll(() => wrapper.find('.ui-side-panel-host .ant-drawer-open').exists()).toBe(true);
  await expect.element(page.getByRole('button', { name: '办理提醒', exact: true })).toBeVisible();
  hostVisible.value = true;
  await expect.poll(() => state.value).toBe(true);
  await expect
    .poll(() => wrapper.get('.business-notification-panel').element.hasAttribute('hidden'))
    .toBe(true);
  modalOpen.value = true;
  await expect.element(page.getByRole('button', { name: '结束确认', exact: true })).toBeVisible();
  drawerOpen.value = false;
  await expect.poll(() => wrapper.find('.ant-drawer-open').exists()).toBe(false);
  expect(state.value).toBe(true);
  expect(wrapper.get('.business-notification-panel').element.hasAttribute('hidden')).toBe(true);
  notifications.value = [];
  await expect.poll(() => wrapper.find('.business-notification-panel').exists()).toBe(false);
  notifications.value = [
    {
      id: 'next',
      code: 'workflow.task',
      title: '新到提醒',
      content: '正文',
      dismissible: true,
      actions: [
        {
          kind: 'navigate',
          key: 'todo',
          label: '办理提醒',
          moduleAlias: 'iam.workflow_workbench',
          dismissOnSuccess: false,
        },
      ],
    },
  ];
  await expect
    .poll(() => wrapper.get('.business-notification-panel').element.hasAttribute('hidden'))
    .toBe(true);
  await page.getByRole('button', { name: '结束确认', exact: true }).click();
  await page.getByRole('button', { name: '办理提醒', exact: true }).click();
  expect(executeAction).toHaveBeenCalledTimes(1);
  expect(state.value).toBe(false);
  drawerOpen.value = true;
  await expect.poll(() => state.value).toBe(true);
  wrapper.unmount();
  expect(state.value).toBe(false);
});

it('keeps a compact collapsed queue from intercepting business controls and preserves that choice through new arrivals and overlays', async () => {
  const approve = vi.fn();
  const executeAction = vi.fn();
  const modalOpen = ref(false);
  const notifications = ref([
    {
      id: 'required',
      code: 'todo',
      title: '必要待办',
      content: '完整提醒正文',
      dismissible: false,
      actions: [
        {
          kind: 'navigate' as const,
          key: 'todo',
          label: '查看必要待办',
          moduleAlias: 'iam.workflow_workbench',
          dismissOnSuccess: false,
        },
      ],
    },
    {
      id: 'optional',
      code: 'todo',
      title: '普通待办',
      content: '完整提醒正文',
      dismissible: true,
      actions: [],
    },
  ]);
  const Harness = defineComponent({
    setup: () => () =>
      h('div', [
        h(
          UiButton,
          {
            class: 'underlying-approval-button',
            style: 'position:fixed;right:84px;bottom:88px',
            onClick: approve,
          },
          () => '办理当前业务',
        ),
        h(
          UiModal,
          {
            open: modalOpen.value,
            title: '业务确认',
            confirmText: '完成业务确认',
            onConfirm: () => (modalOpen.value = false),
          },
          () => '确认正文',
        ),
        h(BusinessNotificationPanel, { notifications: notifications.value, executeAction }),
      ]),
  });
  const wrapper = mount(Harness, {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  const panel = wrapper.findComponent(BusinessNotificationPanel);
  await expect.element(page.getByRole('button', { name: '查看必要待办', exact: true })).toBeVisible();
  expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
  const underlying = wrapper.get('.underlying-approval-button').element;
  const bounds = underlying.getBoundingClientRect();
  const x = bounds.left + bounds.width / 2,
    y = bounds.top + bounds.height / 2;
  const reminder = wrapper.get('.business-notification-card').element.getBoundingClientRect();
  expect(x).toBeGreaterThan(reminder.left);
  expect(x).toBeLessThan(reminder.right);
  expect(y).toBeGreaterThan(reminder.top);
  expect(y).toBeLessThan(reminder.bottom);
  await page.getByRole('button', { name: '收起提醒', exact: true }).click();
  expect(wrapper.findAll('.business-notification-card')).toHaveLength(0);
  expect(underlying.contains(document.elementFromPoint(x, y))).toBe(true);
  await page.getByRole('button', { name: '办理当前业务', exact: true }).click();
  expect(approve).toHaveBeenCalledTimes(1);
  expect(executeAction).not.toHaveBeenCalled();
  notifications.value.push({
    id: 'new',
    code: 'todo',
    title: '新到待办',
    content: '完整提醒正文',
    dismissible: true,
    actions: [],
  });
  await expect.element(page.getByRole('button', { name: '业务提醒（3 条）', exact: true })).toBeVisible();
  expect(wrapper.findAll('.business-notification-card')).toHaveLength(0);
  expect(panel.emitted('dismiss')).toBeUndefined();
  await page.getByRole('button', { name: '业务提醒（3 条）', exact: true }).click();
  expect(wrapper.findAll('.business-notification-card')).toHaveLength(3);
  await expect.element(page.getByText('必要待办', { exact: true })).toBeVisible();
  await expect.element(page.getByText('新到待办', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '查看必要待办', exact: true }).click();
  expect(executeAction).toHaveBeenCalledTimes(1);
  await page.getByRole('button', { name: '收起提醒', exact: true }).click();
  modalOpen.value = true;
  await expect.element(page.getByRole('button', { name: '完成业务确认', exact: true })).toBeVisible();
  await expect
    .poll(() => wrapper.get('.business-notification-panel').element.hasAttribute('hidden'))
    .toBe(true);
  await expect
    .element(page.getByRole('button', { name: '业务提醒（3 条）', exact: true, includeHidden: true }))
    .not.toBeVisible();
  await page.getByRole('button', { name: '完成业务确认', exact: true }).click();
  await expect.element(page.getByRole('button', { name: '业务提醒（3 条）', exact: true })).toBeVisible();
  expect(wrapper.findAll('.business-notification-card')).toHaveLength(0);
  await page.getByRole('button', { name: '办理当前业务', exact: true }).click();
  expect(approve).toHaveBeenCalledTimes(2);
});
