import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import type { RecordFormFieldDescriptor } from '@/platform-components/recordFormFieldModel';
import type { ModuleContext } from '@muyun/web-core';
import '@/styles.css';

it.each([1004, 390])(
  'discloses numbering guidance without expanding the %s-pixel form or changing drafts',
  async (width) => {
    await page.viewport(width, 804);
    const request = vi.fn(async () => [
      {
        fieldName: 'documentNo',
        mode: null,
        trigger: 'SAVE',
        readOnly: true,
        configured: false,
      },
    ]);
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'documentNo',
        {
          fieldRef: { fieldName: 'documentNo' },
          label: '申请单号',
          businessCode: { primary: true, trigger: 'SAVE' },
        },
      ],
      ['subject', { fieldRef: { fieldName: 'subject' }, label: '申请名称', valueType: 'STRING' }],
    ]);
    const wrapper = mount(RecordFormFields, {
      attachTo: document.body,
      props: {
        fields,
        record: { subject: '尚未保存的申请' },
        mode: 'create',
        optionContext: {
          moduleAlias: 'demo.document',
          http: { request },
        } as unknown as ModuleContext<unknown>,
      },
    });
    const outside = document.createElement('button');
    outside.textContent = '表单外部操作';
    outside.style.cssText = 'position: fixed; bottom: 10px; right: 10px';
    document.body.appendChild(outside);
    const field = wrapper.get('.record-form-field').element;
    const trigger = wrapper.get('button[aria-label="申请单号：编号说明"]').element as HTMLButtonElement;
    try {
      await expect.poll(() => wrapper.text()).toContain('未配置编号规则');
      expect(wrapper.text()).not.toContain('主编码');
      expect(wrapper.text()).not.toContain('请管理员');
      expect(field.getBoundingClientRect().height).toBeLessThan(90);
      const height = field.getBoundingClientRect().height;
      trigger.focus();
      await userEvent.keyboard('{Enter}');
      await expect
        .element(page.getByText('尚未配置有效的编号规则，请管理员在「模块管理 → 业务编码」中完成配置。'))
        .toBeVisible();
      const details = document.querySelector('.record-form-code-details')!;
      expect(details.textContent).toContain('主编码');
      expect(details.textContent).toContain('保存时生成编号');
      expect(details.getBoundingClientRect().right).toBeLessThanOrEqual(width);
      expect(field.getBoundingClientRect().height).toBe(height);
      expect(wrapper.get('input').element.value).toBe('尚未保存的申请');
      expect(wrapper.emitted('update:field')).toBeUndefined();
      expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: false });
      await page.getByRole('button', { name: '表单外部操作', exact: true }).click();
      await expect
        .element(page.getByText('尚未配置有效的编号规则，请管理员在「模块管理 → 业务编码」中完成配置。'))
        .not.toBeVisible();
    } finally {
      wrapper.unmount();
      outside.remove();
    }
  },
);
