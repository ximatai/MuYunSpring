import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref, type Ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { commands, page, userEvent } from 'vitest/browser';
import type { WebBusinessNotification } from '@muyun/web-contracts';
import BusinessNotificationPanel from '@/platform-components/BusinessNotificationPanel.vue';
import RecordDetailDrawer from '@/platform-components/RecordDetailDrawer.vue';
import {
  UiModal,
  UiThemeProvider,
  UiSidePanelHost,
  useUiBlockingOverlayState,
  confirmAction,
} from '@/vue-ui-antdv';
import '@/styles.css';

function notice(id: string, extra: Partial<WebBusinessNotification> = {}): WebBusinessNotification {
  return {
    id,
    code: 'demo.reminder',
    title: `提醒 ${id}`,
    content: '保留现有的单卡片正文与业务操作。',
    dismissible: true,
    actions: [
      {
        kind: 'navigate',
        key: 'view',
        label: `查看 ${id}`,
        moduleAlias: 'demo.order',
        dismissOnSuccess: false,
      },
    ],
    ...extra,
  };
}
function host(notifications: Ref<WebBusinessNotification[]>, executeAction = vi.fn()) {
  return defineComponent({
    setup: () => () =>
      h(UiThemeProvider, { scope: 'global' }, () =>
        h('main', { class: 'notification-test-main', style: 'height:100dvh;min-width:0' }, [
          h('input', { 'aria-label': '业务草稿' }),
          h('button', '业务操作'),
          h(BusinessNotificationPanel, { notifications: notifications.value, executeAction }),
        ]),
      ),
  });
}

async function settlePresentation() {
  await expect.poll(() => document.querySelector('.business-notification-deck--moving')).toBeNull();
}

it.each([false, true])(
  'deals the same top card between layouts without fading (reduced motion=%s)',
  async (reduce) => {
    await commands.treeReducedMotion(reduce);
    const wrapper = mount(host(ref([notice('one'), notice('two')])), {
      attachTo: document.body,
      global: { stubs: { transition: false, transitionGroup: false } },
    });
    try {
      const bell = wrapper.get('.business-notification-bell');
      const originalTop = wrapper.get('.business-notification-card').element;
      await bell.trigger('click');
      if (!reduce) {
        await expect
          .poll(() => Boolean(document.querySelector('.business-notification-deck--moving')))
          .toBe(true);
        const animations = [...document.querySelectorAll<HTMLElement>('[data-notification-id]')].flatMap(
          (card) => card.getAnimations(),
        );
        expect(animations.length).toBeGreaterThan(0);
        for (const animation of animations) {
          const frames = (animation.effect as KeyframeEffect).getKeyframes();
          expect(frames[0]!.transform).not.toBe(frames.at(-1)!.transform);
          expect(frames.every((frame) => frame.opacity === undefined)).toBe(true);
        }
      }
      await settlePresentation();
      expect(wrapper.findAll('.business-notification-current .business-notification-card')).toHaveLength(2);
      expect(wrapper.get('.business-notification-card').element).toBe(originalTop);
      for (const card of wrapper.findAll('.business-notification-card')) {
        expect(getComputedStyle(card.element, '::before').opacity).toBe('0');
      }
      await bell.trigger('click');
      await settlePresentation();
      const card = wrapper.get('.business-notification-card').element;
      expect(card).toBe(originalTop);
      expect(getComputedStyle(card, '::before').opacity).toBe('0');
      expect(getComputedStyle(card).animationName).toBe('none');
      // A quick reversal must finish with a single, usable presentation.
      await bell.trigger('click');
      await bell.trigger('click');
      await settlePresentation();
      expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
      expect(bell.attributes('aria-expanded')).toBe('false');
    } finally {
      wrapper.unmount();
      await commands.treeReducedMotion(false);
    }
  },
);

it('fades only the lower cards in the second half of collapse while retaining the front card', async () => {
  const wrapper = mount(host(ref([notice('one'), notice('two'), notice('three')])), {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  try {
    const bell = wrapper.get('.business-notification-bell');
    await bell.trigger('click');
    await settlePresentation();
    await bell.trigger('click');
    const top = wrapper.get('.business-notification-deck-card--top').element;
    const bodies = wrapper.findAll('.business-notification-deck-card').map((card) => card.element);
    for (const body of bodies.filter((body) => body !== top)) {
      const fade = body
        .getAnimations()
        .find((animation) =>
          (animation.effect as KeyframeEffect).getKeyframes().some((frame) => frame.opacity !== undefined),
        );
      expect(fade).toBeDefined();
      fade!.pause();
      const timing = fade!.effect!.getTiming();
      fade!.currentTime = Number(timing.delay) + Number(timing.duration) * 0.25;
      expect(getComputedStyle(body).opacity).toBe('1');
      fade!.currentTime = Number(timing.delay) + Number(timing.duration) * 0.75;
      expect(Number(getComputedStyle(body).opacity)).toBeGreaterThan(0);
      expect(Number(getComputedStyle(body).opacity)).toBeLessThan(1);
      expect(getComputedStyle(top).opacity).toBe('1');
      fade!.finish();
    }
    await settlePresentation();
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
    expect(wrapper.get('.business-notification-deck-card--top').element).toBe(top);
    expect(getComputedStyle(top).opacity).toBe('1');
  } finally {
    wrapper.unmount();
  }
});

it('preserves the current crop and position when a scrolled collapse reverses midway', async () => {
  await page.viewport(1280, 700);
  const wrapper = mount(host(ref(Array.from({ length: 10 }, (_, index) => notice(String(index))))), {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  try {
    const bell = wrapper.get('.business-notification-bell');
    await bell.trigger('click');
    await settlePresentation();
    const list = wrapper.get<HTMLElement>('.business-notification-current').element;
    list.scrollTop = list.scrollHeight;
    await bell.trigger('click');
    const top = wrapper.get<HTMLElement>('.business-notification-deck-card--top').element;
    const returnMotion = top.getAnimations()[0]!;
    returnMotion.pause();
    const timing = returnMotion.effect!.getTiming();
    returnMotion.currentTime = Number(timing.delay) + Number(timing.duration) / 2;
    const clip = getComputedStyle(top).clipPath;
    const position = top.getBoundingClientRect();
    await bell.trigger('click');
    const reverse = top
      .getAnimations()
      .find((animation) => (animation.effect as KeyframeEffect).getKeyframes()[0]!.transform !== undefined)!;
    const first = (reverse.effect as KeyframeEffect).getKeyframes()[0]!;
    expect(first.clipPath).toBe(clip);
    reverse.pause();
    reverse.currentTime = 0;
    expect(Math.abs(top.getBoundingClientRect().top - position.top)).toBeLessThan(0.1);
    expect(Math.abs(top.getBoundingClientRect().left - position.left)).toBeLessThan(0.1);
    reverse.finish();
    await settlePresentation();
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(10);
  } finally {
    wrapper.unmount();
  }
});

it.each(['resize', 'preference', 'withdrawal', 'unmount'] as const)(
  'cancels in-flight motion and cleans effects on %s',
  async (change) => {
    const notifications = ref([notice('one'), notice('two')]);
    const wrapper = mount(host(notifications), {
      attachTo: document.body,
      global: { stubs: { transition: false, transitionGroup: false } },
    });
    try {
      await wrapper.get('.business-notification-bell').trigger('click');
      const deck = wrapper.get<HTMLElement>('.business-notification-current').element;
      expect(deck.getAnimations({ subtree: true }).length).toBeGreaterThan(0);
      if (change === 'resize') window.dispatchEvent(new Event('resize'));
      if (change === 'preference') await commands.treeReducedMotion(true);
      if (change === 'withdrawal') notifications.value = [];
      if (change === 'unmount') wrapper.unmount();
      await settlePresentation();
      expect(deck.getAnimations({ subtree: true })).toHaveLength(0);
      if (change === 'resize' || change === 'preference') {
        expect(wrapper.get('.business-notification-bell').attributes('aria-expanded')).toBe('true');
      }
    } finally {
      if (change !== 'unmount') wrapper.unmount();
      await commands.treeReducedMotion(false);
    }
  },
);

it('stacks newer cards at the upper left with edge-only layers and a single bell entry', async () => {
  await page.viewport(1280, 900);
  const notifications = ref([notice('old'), notice('middle'), notice('latest')]);
  const wrapper = mount(host(notifications), {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  try {
    const card = wrapper.get<HTMLElement>('.business-notification-card').element;
    await expect
      .poll(() =>
        card.getAnimations({ subtree: true }).every((animation) => animation.playState === 'finished'),
      )
      .toBe(true);
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
    expect(wrapper.get('.business-notification-card h2').text()).toBe('提醒 latest');
    const surface = card.getBoundingClientRect();
    const edges = wrapper.findAll('.business-notification-stack-edge');
    expect(edges).toHaveLength(2);
    for (const edge of edges) {
      expect(edge.element.getBoundingClientRect().left).toBeGreaterThan(surface.left);
      expect(edge.element.getBoundingClientRect().top).toBeGreaterThan(surface.top);
      expect(edge.text()).toBe('');
    }
    await page.getByRole('button', { name: '查看 latest', exact: true }).hover();
    expect(wrapper.find('.business-notification-controls').exists()).toBe(false);
    await page.screenshot({ path: '../../test-results/current-reminders-stack.png' });
    await page.getByRole('button', { name: '本次提醒（3 条）', exact: true }).click();
    await settlePresentation();
    await expect.element(page.getByRole('region', { name: '本次提醒（3 条）' })).toBeVisible();
    expect(wrapper.find('.business-notification-stack').exists()).toBe(false);
    expect(wrapper.findAll('.business-notification-current .business-notification-card')).toHaveLength(3);
  } finally {
    wrapper.unmount();
  }
});

it('dismisses cards from this page session and labels the remaining list as temporary without reflowing business content', async () => {
  await page.viewport(1280, 900);
  const notifications = ref([notice('one'), notice('two')]);
  const wrapper = mount(host(notifications), {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  try {
    await page.getByRole('textbox', { name: '业务草稿' }).fill('未保存的业务草稿');
    const input = wrapper.get('input').element;
    const main = wrapper.get('.notification-test-main').element.getBoundingClientRect().toJSON();
    await page.getByRole('button', { name: '查看 two', exact: true }).hover();
    await page.getByRole('button', { name: '关闭提醒', exact: true }).click();
    expect(wrapper.findComponent(BusinessNotificationPanel).emitted('dismiss')).toEqual([['two']]);
    const bell = wrapper.get<HTMLElement>('.business-notification-bell').element;
    expect(getComputedStyle(bell.parentElement!).position).toBe('fixed');
    expect(bell.getBoundingClientRect().bottom).toBeGreaterThan(840);
    await page.getByRole('button', { name: '本次提醒（1 条）', exact: true }).click();
    await settlePresentation();
    const panel = wrapper.get('.business-notification-current').element.getBoundingClientRect();
    expect(panel.right).toBeLessThanOrEqual(1281);
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
    expect(wrapper.get('.business-notification-bell').attributes('title')).toContain('刷新或退出后清空');
    expect(wrapper.get('.notification-test-main').element.getBoundingClientRect().toJSON()).toEqual(main);
    expect(wrapper.get('input').element).toBe(input);
    expect((input as HTMLInputElement).value).toBe('未保存的业务草稿');
    await page.screenshot({ path: '../../test-results/session-reminders-list.png' });
    await page.getByRole('button', { name: /本次提醒（\d+ 条）/, exact: true }).click();
    await settlePresentation();
    expect(document.activeElement).toBe(bell);
    await page.getByRole('button', { name: '查看 one', exact: true }).hover();
    await page.getByRole('button', { name: '关闭提醒', exact: true }).click();
    expect(wrapper.find('.business-notification-bell').exists()).toBe(false);
    notifications.value = [];
    await wrapper.vm.$nextTick();
    notifications.value = [notice('one'), notice('two')];
    await expect.poll(() => wrapper.findAll('.business-notification-card').length).toBe(0);
  } finally {
    wrapper.unmount();
  }
});

it.each([1280, 520])(
  'offers keyboard and direct bell entry at %spx and restores unclosed cards after collapse',
  async (width) => {
    await page.viewport(width, 850);
    const wrapper = mount(host(ref([notice('one'), notice('two')])), {
      attachTo: document.body,
      global: { stubs: { transition: false, transitionGroup: false } },
    });
    try {
      wrapper.get<HTMLElement>('.business-notification-bell').element.focus();
      expect(document.activeElement?.getAttribute('aria-label')).toBe('本次提醒（2 条）');
      await userEvent.keyboard('{Enter}');
      await settlePresentation();
      await expect.poll(() => wrapper.find('.business-notification-current').exists()).toBe(true);
      const panel = wrapper.get('.business-notification-current').element.getBoundingClientRect();
      expect(panel.left).toBeGreaterThanOrEqual(0);
      expect(panel.right).toBeLessThanOrEqual(width + 1);
      await page.getByRole('button', { name: /本次提醒（\d+ 条）/, exact: true }).click();
      await settlePresentation();
      await expect.element(page.getByRole('button', { name: '查看 two', exact: true })).toBeVisible();
      await page.getByRole('button', { name: '本次提醒（2 条）', exact: true }).click();
      await settlePresentation();
      expect(wrapper.find('.business-notification-stack').exists()).toBe(false);
      await userEvent.keyboard('{Escape}');
      await settlePresentation();
      await expect.poll(() => wrapper.find('.business-notification-current').exists()).toBe(false);
    } finally {
      wrapper.unmount();
    }
  },
);

it.each([1280, 520])(
  'expands many independent cards without a frame and keeps them scrollable at %spx',
  async (width) => {
    await page.viewport(width, 700);
    const wrapper = mount(host(ref(Array.from({ length: 10 }, (_, index) => notice(String(index))))), {
      attachTo: document.body,
      global: { stubs: { transition: false, transitionGroup: false } },
    });
    try {
      await page.getByRole('button', { name: '本次提醒（10 条）', exact: true }).click();
      await settlePresentation();
      const list = wrapper.get<HTMLElement>('.business-notification-current').element;
      const bounds = list.getBoundingClientRect();
      const style = getComputedStyle(list);
      expect(style.backgroundColor).toBe('rgba(0, 0, 0, 0)');
      expect(style.borderTopWidth).toBe('0px');
      expect(style.boxShadow).toBe('none');
      for (const card of wrapper.findAll('.business-notification-card')) {
        expect(getComputedStyle(card.element).boxShadow).toBe('none');
      }
      expect(wrapper.find('.business-notification-current-header').exists()).toBe(false);
      expect(wrapper.findAll('.business-notification-card')).toHaveLength(10);
      expect(list.scrollHeight).toBeGreaterThan(list.clientHeight);
      expect(bounds.top).toBeGreaterThanOrEqual(0);
      expect(bounds.right).toBeLessThanOrEqual(width);
      const bell = wrapper.get('.business-notification-bell').element.getBoundingClientRect();
      expect(bounds.bottom).toBeLessThan(bell.top);
      await page.getByRole('button', { name: '查看 0', exact: true }).click();
      expect(list.scrollTop).toBeGreaterThan(0);
      await page.getByRole('button', { name: '本次提醒（10 条）', exact: true }).click();
      await settlePresentation();
      expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
    } finally {
      wrapper.unmount();
    }
  },
);

it.each([
  [1280, false],
  [1280, true],
  [520, false],
  [520, true],
] as const)(
  'returns ten reminders directly to the final three-layer footprint at %spx (scrolled=%s)',
  async (width, scrolled) => {
    await page.viewport(width, 700);
    const wrapper = mount(host(ref(Array.from({ length: 10 }, (_, index) => notice(String(index))))), {
      attachTo: document.body,
      global: { stubs: { transition: false, transitionGroup: false } },
    });
    try {
      const bell = wrapper.get('.business-notification-bell');
      await bell.trigger('click');
      await settlePresentation();
      const list = wrapper.get<HTMLElement>('.business-notification-current').element;
      if (scrolled) list.scrollTop = list.scrollHeight;
      await bell.trigger('click');
      const slots = [
        ...(wrapper.element as HTMLElement).querySelectorAll<HTMLElement>('[data-notification-id]'),
      ];
      const animations = slots.flatMap((slot) => slot.getAnimations());
      expect(animations.length).toBeGreaterThan(0);
      for (const animation of animations) {
        const effect = animation.effect as KeyframeEffect;
        const transform = effect.getKeyframes().at(-1)!.transform;
        if (transform !== undefined) {
          const target = new DOMMatrix(String(transform));
          expect(target.e).toBeLessThanOrEqual(20);
          expect(target.f).toBeLessThanOrEqual(20);
        }
        animation.pause();
        const timing = effect.getTiming();
        animation.currentTime = Number(timing.delay) + Number(timing.duration) - 0.01;
      }
      const footprint = () => {
        const visible = slots.filter(
          (slot) => !slot.hidden && getComputedStyle(slot).visibility !== 'hidden',
        );
        return {
          right: Math.max(...visible.map((slot) => slot.getBoundingClientRect().right)),
          bottom: Math.max(...visible.map((slot) => slot.getBoundingClientRect().bottom)),
        };
      };
      const landing = footprint();
      for (const animation of animations) animation.finish();
      await settlePresentation();
      const final = footprint();
      expect(Math.abs(landing.right - final.right)).toBeLessThan(1);
      expect(Math.abs(landing.bottom - final.bottom)).toBeLessThan(1);
      expect(slots.filter((slot) => !slot.hidden)).toHaveLength(3);
      expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
      expect(wrapper.get('.business-notification-badge').text()).toBe('10');
      await bell.trigger('click');
      await settlePresentation();
      expect(wrapper.findAll('.business-notification-card')).toHaveLength(10);
    } finally {
      wrapper.unmount();
    }
  },
);

it('finishes collapse on the same front surface, destination edges and shadows as the settled stack', async () => {
  await page.viewport(1280, 700);
  const wrapper = mount(host(ref(Array.from({ length: 10 }, (_, index) => notice(String(index))))), {
    attachTo: document.body,
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  try {
    const bell = wrapper.get('.business-notification-bell');
    await bell.trigger('click');
    await settlePresentation();
    await bell.trigger('click');
    const front = wrapper.get('.business-notification-deck-card--top .business-notification-card').element;
    const edges = wrapper.findAll('.business-notification-stack-edge').map((edge) => edge.element);
    const deck = wrapper.get('.business-notification-stack').element;
    const animations = deck.getAnimations({ subtree: true });
    expect(animations.length).toBeGreaterThan(0);
    // Read the rendered final animation frame before Vue performs any cleanup.
    for (const animation of animations) animation.finish();
    const appearance = () => ({
      frontShadow: getComputedStyle(front).boxShadow,
      edges: edges.map((edge) => {
        const style = getComputedStyle(edge);
        return [style.opacity, style.boxShadow, style.backgroundColor, style.borderColor];
      }),
    });
    const lastFrame = appearance();
    expect(lastFrame.edges.every(([opacity]) => opacity === '1')).toBe(true);
    await settlePresentation();
    expect(appearance()).toEqual(lastFrame);
    expect(wrapper.get('.business-notification-deck-card--top .business-notification-card').element).toBe(
      front,
    );
    expect(wrapper.findAll('.business-notification-stack-edge').map((edge) => edge.element)).toEqual(edges);
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
  } finally {
    wrapper.unmount();
  }
});

it('defers ordinary reminders during drawer and modal handling without losing pending reminders or drafts', async () => {
  await page.viewport(1280, 900);
  const drawer = ref(false),
    modal = ref(false);
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(UiThemeProvider, { scope: 'global' }, () => [
          h('div', { style: 'position:relative;height:100vh' }, [
            h(RecordDetailDrawer, { open: drawer.value, title: '业务详情', renderMode: 'inline' }, () =>
              h('input', { 'aria-label': '详情草稿' }),
            ),
          ]),
          h(
            UiModal,
            { open: modal.value, title: '业务确认', onConfirm: () => (modal.value = false) },
            () => '确认业务操作',
          ),
          h(BusinessNotificationPanel, { notifications: [notice('ordinary')], executeAction: vi.fn() }),
        ]),
    }),
    { attachTo: document.body, global: { stubs: { transition: false, transitionGroup: false } } },
  );
  try {
    await expect.element(page.getByRole('button', { name: '查看 ordinary' })).toBeVisible();
    drawer.value = true;
    await page.getByRole('textbox', { name: '详情草稿' }).fill('保留草稿');
    await expect
      .poll(() => wrapper.get('.business-notification-panel').element.hasAttribute('hidden'))
      .toBe(true);
    modal.value = true;
    drawer.value = false;
    await expect.element(page.getByRole('dialog', { name: '业务确认' })).toBeVisible();
    expect(wrapper.get('.business-notification-panel').element.hasAttribute('hidden')).toBe(true);
    modal.value = false;
    await expect.element(page.getByRole('button', { name: '查看 ordinary' })).toBeVisible();
    drawer.value = true;
    await expect.element(page.getByRole('textbox', { name: '详情草稿' })).toBeVisible();
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('保留草稿');
    drawer.value = false;
    await expect.element(page.getByRole('button', { name: '查看 ordinary' })).toBeVisible();
  } finally {
    wrapper.unmount();
  }
});

it('keeps the latest persistent reminder actionable above dialogs and retires completed business facts without closing drafts', async () => {
  await page.viewport(1280, 900);
  const modal = ref(true);
  const execute = vi.fn();
  const persistent = (id: string) =>
    notice(id, {
      displayPolicy: 'persistent',
      actions: [
        {
          kind: 'record',
          key: 'accept',
          label: `接听 ${id}`,
          moduleAlias: 'demo.call',
          recordId: id,
          actionCode: 'accept',
          dismissOnSuccess: true,
        },
      ],
    });
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(UiThemeProvider, { scope: 'global' }, () => [
          h(UiModal, { open: modal.value, title: '正在编辑', onCancel: () => (modal.value = false) }, () =>
            h('input', { 'aria-label': '编辑草稿', value: '保留未保存内容' }),
          ),
          h(BusinessNotificationPanel, {
            notifications: [persistent('one'), persistent('two')],
            executeAction: async () => {
              if (!(await confirmAction({ title: '确认接听？', content: '确认处理', layer: 'notification' })))
                return false;
              execute();
              return true;
            },
          }),
        ]),
    }),
    { attachTo: document.body, global: { stubs: { transition: false, transitionGroup: false } } },
  );
  try {
    await page.getByRole('button', { name: '本次提醒（2 条）', exact: true }).click();
    await settlePresentation();
    await expect.element(page.getByRole('button', { name: '接听 one', exact: true })).toBeVisible();
    for (const id of ['one', 'two']) {
      await page.getByRole('button', { name: `接听 ${id}`, exact: true }).click();
      await page
        .getByRole('button', { name: /确.*认/ })
        .last()
        .click();
      await expect.poll(() => execute.mock.calls.length).toBe(id === 'one' ? 1 : 2);
      await expect.poll(() => document.querySelector('.ant-modal-confirm')).toBeNull();
    }
    await expect.poll(() => document.querySelector('.business-notification-bell')).toBeNull();
    expect(modal.value).toBe(true);
    expect(document.querySelector<HTMLInputElement>('input[aria-label="编辑草稿"]')!.value).toBe(
      '保留未保存内容',
    );
  } finally {
    document.querySelector<HTMLButtonElement>('.ant-modal-confirm .ant-btn-default')?.click();
    wrapper.unmount();
  }
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
  await expect.element(page.getByText('办理中到达的待办', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '本次提醒（2 条）', exact: true }).click();
  await settlePresentation();
  await expect.element(page.getByText('办理中到达的待办', { exact: true })).toBeVisible();
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
