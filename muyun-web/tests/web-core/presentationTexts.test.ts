import { computed, reactive } from 'vue';
import { expect, it } from 'vitest';
import { createLanguageContext } from '@/web-core/language';
import { originalPresentation, translatedPresentation } from '@/web-core/presentationTexts';
import type { TextPresentation } from '@/web-contracts';

const label: TextPresentation = {
  source: {
    sourceKind: 'CODE',
    sourceId: 'test.form.name',
    sourceVersion: 'version-1',
    originalText: '姓名',
  },
  languages: { en: { text: 'Name', requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false } },
};

it('switches display language while source metadata and business drafts remain intact', () => {
  const context = createLanguageContext();
  const original = originalPresentation({
    uiDescriptor: { fields: [{ fieldName: 'name', label: 'Name' }] },
    record: { name: '张三' },
    textBindings: { '/uiDescriptor/fields/0/label': label },
  });
  const display = computed(() => translatedPresentation(reactive(original), context));
  expect(display.value.uiDescriptor.fields[0]?.label).toBe('姓名');
  original.record.name = '未保存内容';
  context.setLanguage('en');
  expect(display.value.uiDescriptor.fields[0]?.label).toBe('Name');
  expect(display.value.record.name).toBe('未保存内容');
  expect(original.uiDescriptor.fields[0]?.label).toBe('姓名');
  expect(original.textBindings['/uiDescriptor/fields/0/label'].source.originalText).toBe('姓名');
  expect(display.value.uiDescriptor.fields[0]?.fieldName).toBe('name');
});

it('rejects addresses of protocol identities, business values, new properties and prototypes', () => {
  const context = createLanguageContext({ languageTag: 'en' });
  const document = {
    uiDescriptor: { fields: [{ fieldName: 'name', label: '姓名' }] },
    record: { title: '业务原值' },
    textBindings: {
      '/uiDescriptor/fields/0/fieldName': label,
      '/record/title': label,
      '/uiDescriptor/newTitle': label,
      '/uiDescriptor/__proto__/title': label,
    },
  };
  const projected = translatedPresentation(document, context);
  expect(projected.uiDescriptor.fields[0]?.fieldName).toBe('name');
  expect(projected.record.title).toBe('业务原值');
  expect(Object.hasOwn(projected.uiDescriptor, 'newTitle')).toBe(false);
  expect(Object.hasOwn(Object.prototype, 'title')).toBe(false);
});
