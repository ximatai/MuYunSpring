import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { UiThemeProvider } from '@muyun/vue-ui-antdv';
import { ModuleContextProvider, type ModuleContext, type HttpRequestOptions } from '@muyun/web-core';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
import type { CurrentUser } from '@muyun/web-contracts';
import CodeConfigurationView from '@/views/CodeConfigurationView.vue';
import { newCodeRule, newCodeSegment, type CodeTarget } from '@/views/codeConfiguration';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

it.each([1004, 600])(
  'keeps code operations visible and mapping controls readable at viewport %s',
  async (width) => {
    await page.viewport(width, 804);
    const target: CodeTarget = {
      moduleAlias: 'demo.purchase',
      entityAlias: 'purchase',
      title: '采购申请',
      source: 'STATIC',
      fields: [{ fieldName: 'documentNo', title: '申请单号', role: 'PRIMARY', trigger: 'SAVE' }],
      sources: [],
    };
    const rule = { ...newCodeRule(target, target.fields[0]!), id: 'rule' };
    rule.segments = [
      {
        ...newCodeSegment('VALUE_MAPPING'),
        mappings: [{ sourceValue: 'a', targetValue: 'A', defaultMapping: false, enabled: true }],
      },
      newCodeSegment('SEQUENCE'),
    ];
    const context = {
      moduleAlias: 'platform.code_rule',
      can: () => true,
      http: {
        async request(options: HttpRequestOptions) {
          if (options.path.includes('/targets')) return [target];
          if (options.path.includes('/rules?')) return [rule];
          return undefined;
        },
      },
    } as unknown as ModuleContext<unknown>;
    const wrapper = mount(
      defineComponent({
        setup() {
          providePageLayout('workspace');
          provideCurrentUserContext(ref({ tenantId: 'tenant-a', system: false } as CurrentUser));
          return () =>
            h('div', { style: 'height:700px' }, [
              h(UiThemeProvider, {}, () =>
                h(ModuleContextProvider, { context }, () =>
                  h(CodeConfigurationView, { moduleAlias: 'demo.purchase', embedded: true }),
                ),
              ),
            ]);
        },
      }),
      { attachTo: document.body },
    );
    try {
      await flushPromises();
      const save = page.getByRole('button', { name: '保存规则', exact: true });
      await expect.element(save).toBeVisible();
      const editor = wrapper.get('.code-config__editor').element.getBoundingClientRect();
      const directoryField = wrapper
        .get('.code-config__directory-fields > label')
        .element.getBoundingClientRect();
      expect(directoryField.height).toBeLessThan(70);
      const saveButton = wrapper
        .findAll('button')
        .find((button) => button.text().replace(/\s+/g, '') === '保存规则')!.element;
      const before = saveButton.getBoundingClientRect();
      const content = wrapper.get('.code-config__editor .record-detail-layout-content').element;
      expect(content.scrollHeight).toBeGreaterThan(content.clientHeight);
      content.scrollTop = content.scrollHeight;
      await expect.element(save).toBeVisible();
      expect(saveButton.getBoundingClientRect().top).toBe(before.top);
      expect(before.right).toBeLessThanOrEqual(editor.right);
      const mapping = wrapper.get('.code-format__mapping-row').element;
      const mappingBounds = mapping.getBoundingClientRect();
      for (const control of mapping.querySelectorAll('input,button,label')) {
        const bounds = control.getBoundingClientRect();
        expect(bounds.left).toBeGreaterThanOrEqual(mappingBounds.left - 1);
        expect(bounds.right).toBeLessThanOrEqual(mappingBounds.right + 1);
      }
      const label = wrapper.get('.code-format__mapping-default').element;
      const text = label.lastElementChild!.getBoundingClientRect();
      expect(text.width).toBeGreaterThan(40);
      expect(text.height).toBeLessThan(30);
      const mark = label.firstElementChild!.getBoundingClientRect();
      expect(Math.abs(mark.top + mark.height / 2 - text.top - text.height / 2)).toBeLessThan(4);
      for (const checkbox of wrapper.findAll('.code-format__basis')) {
        const mark = checkbox.element.firstElementChild!.getBoundingClientRect();
        const text = checkbox.element.lastElementChild!.getBoundingClientRect();
        expect(Math.abs(mark.top + mark.height / 2 - text.top - text.height / 2)).toBeLessThan(4);
      }
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width + 1);
    } finally {
      wrapper.unmount();
    }
  },
);
