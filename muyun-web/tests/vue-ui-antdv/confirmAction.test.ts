import { assert, it } from 'vitest';
import { Modal } from 'ant-design-vue';
import type { VNode } from 'vue';
import { createConfirmAction, matchesRequiredText } from '@/vue-ui-antdv/confirm.ts';

it('required confirmation text enables destructive confirmation only after an exact match', async () => {
  let options!: Parameters<typeof Modal.confirm>[0];
  const updates: Parameters<typeof Modal.confirm>[0][] = [];
  const confirm = createConfirmAction((nextOptions) => {
    options = nextOptions;
    return {
      destroy() {},
      update(update) {
        updates.push(update as Parameters<typeof Modal.confirm>[0]);
      },
    };
  });

  const confirming = confirm({ title: '删除租户', requiredText: 'demo', danger: true });
  assert.equal(options.okButtonProps?.disabled, true);

  const content = (options.content as () => VNode)();
  const input = (content.children as VNode[])[2];
  const updateValue = input.props?.['onUpdate:value'] as (value: string) => void;
  updateValue('demo ');
  updateValue('demo');

  assert.deepEqual(updates, [
    { okButtonProps: { danger: true, disabled: true } },
    { okButtonProps: { danger: true, disabled: false } },
  ]);
  options.onOk?.();
  assert.equal(await confirming, true);
});

it('required confirmation text rejects mismatches and cancellation resolves false', async () => {
  assert.equal(matchesRequiredText('demo', 'demo'), true);
  assert.equal(matchesRequiredText('demo', 'demo '), false);
  assert.equal(matchesRequiredText(undefined, ''), true);

  let options!: Parameters<typeof Modal.confirm>[0];
  const confirm = createConfirmAction((nextOptions) => {
    options = nextOptions;
    return { destroy() {}, update() {} };
  });
  const confirming = confirm({ title: '删除租户', requiredText: 'demo' });

  options.onCancel?.();
  assert.equal(await confirming, false);
});

it('uses consumer language for prompts while retaining the exact business-title confirmation guard', async () => {
  const { createLanguageContext } = await import('@/web-core');
  const { nextTick } = await import('vue');
  const language = createLanguageContext({ languageTag: 'en' });
  let options!: Parameters<typeof Modal.confirm>[0];
  const updates: Parameters<typeof Modal.confirm>[0][] = [];
  const confirm = createConfirmAction((value) => {
    options = value;
    return {
      destroy() {},
      update(value) {
        updates.push(value as Parameters<typeof Modal.confirm>[0]);
      },
    };
  });
  const result = confirm({ title: 'Delete record', requiredText: '原始记录甲', danger: true }, language);
  assert.equal(options.okText, 'Confirm');
  assert.equal(options.cancelText, 'Cancel');
  const content = (options.content as () => VNode)();
  const children = content.children as VNode[];
  assert.equal(children[1].children, 'Type “原始记录甲” to confirm this action.');
  const updateValue = children[2].props?.['onUpdate:value'] as (value: string) => void;
  updateValue('原始记录甲 ');
  assert.equal(updates.at(-1)?.okButtonProps?.disabled, true);
  language.setLanguage('zh-CN');
  await nextTick();
  assert.deepEqual(updates.at(-1), { okText: '确认', cancelText: '取消' });
  updateValue('原始记录甲');
  assert.equal(updates.at(-1)?.okButtonProps?.disabled, false);
  options.onOk?.();
  assert.equal(await result, true);
  options.afterClose?.();
  const count = updates.length;
  language.setLanguage('en');
  await nextTick();
  assert.equal(updates.length, count);
});
