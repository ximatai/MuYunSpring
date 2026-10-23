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
      const status = wrapper.get('.record-form-policy-status').element;
      expect(status.textContent).toContain('请管理员');
      await expect.element(page.elementLocator(status)).toBeVisible();
      expect(field.getBoundingClientRect().height).toBeLessThan(90);
      const height = field.getBoundingClientRect().height;
      const triggerBox = trigger.getBoundingClientRect();
      const fieldBox = field.getBoundingClientRect();
      expect(triggerBox.top).toBeCloseTo(fieldBox.top, 0);
      expect(triggerBox.left).toBeGreaterThanOrEqual(fieldBox.left);
      expect(triggerBox.right).toBeLessThanOrEqual(fieldBox.right);

      trigger.focus();
      await userEvent.keyboard('{Enter}');
      await expect.poll(() => document.querySelector('.record-form-code-details')).not.toBeNull();
      const details = document.querySelector('.record-form-code-details')!;
      await expect.element(page.elementLocator(details)).toBeVisible();
      expect(details.textContent).toContain('尚未配置有效的编号规则');
      expect(details.textContent).toContain('主编码');
      expect(details.textContent).toContain('保存时生成编号');
      expect(details.getBoundingClientRect().right).toBeLessThanOrEqual(width);
      expect(field.getBoundingClientRect().height).toBe(height);
      expect(wrapper.get('input').element.value).toBe('尚未保存的申请');
      expect(wrapper.emitted('update:field')).toBeUndefined();
      expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toMatchObject({ valid: false });
      await page.getByRole('button', { name: '表单外部操作', exact: true }).click();
      await expect.element(page.elementLocator(details)).not.toBeVisible();
      await expect.element(page.elementLocator(status)).toBeVisible();
    } finally {
      wrapper.unmount();
      outside.remove();
    }
  },
);

it.each(['MANUAL', 'AUTO_WITH_MANUAL_EDIT'])(
  'keeps editable %s code labels associated with the input',
  async (mode) => {
    const request = vi.fn(async () => [
      { fieldName: 'documentNo', mode, trigger: 'SAVE', readOnly: false, configured: true },
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
    ]);
    const wrapper = mount(RecordFormFields, {
      attachTo: document.body,
      props: {
        fields,
        record: { documentNo: 'MANUAL-01' },
        mode: 'create',
        optionContext: {
          moduleAlias: 'demo.document',
          http: { request },
        } as unknown as ModuleContext<unknown>,
      },
    });
    try {
      const input = page.getByRole('textbox', { name: '申请单号', exact: true });
      await expect.element(input).toBeVisible();
      const trigger = wrapper.get('button[aria-label="申请单号：编号说明"]').element;
      expect(trigger.closest('label')).toBeNull();
      await page.getByText('申请单号', { exact: true }).click();
      expect(document.activeElement).toBe(wrapper.get('input').element);
      await expect.element(input).toHaveValue('MANUAL-01');
      await page.elementLocator(trigger).click();
      await expect
        .element(
          page.getByText(mode === 'MANUAL' ? '请手工填写业务编号' : '留空时自动编号，也可手工填写', {
            exact: true,
          }),
        )
        .toBeVisible();
      expect(document.activeElement).toBe(trigger);
      expect(wrapper.emitted('update:field')).toBeUndefined();
      await page.elementLocator(trigger).click();
      await wrapper.setProps({ showLabels: false });
      const inputBox = wrapper.get('input').element.getBoundingClientRect();
      expect(trigger.getBoundingClientRect().left).toBeGreaterThanOrEqual(inputBox.right);
    } finally {
      wrapper.unmount();
    }
  },
);
