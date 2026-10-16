import { expect, it, vi } from 'vitest';
import { createLanguageContext, createModuleContext, type HttpClient } from '@/web-core';

function descriptor(text: string, version = 'v1') {
  return {
    moduleAlias: 'test.order',
    capabilities: [],
    actions: [{ actionCode: 'update', authorized: true }],
    uiDescriptor: {
      moduleAlias: 'test.order',
      defaultEditor: { viewKind: 'FORM', fields: [{ fieldRef: { fieldName: 'title' }, label: '名称' }] },
    },
    textBindings: {
      '/uiDescriptor/defaultEditor/fields/0/label': {
        source: {
          sourceKind: 'CONFIG',
          sourceId: 'config/ui/test.order/title',
          sourceVersion: version,
          originalText: '名称',
        },
        languages: { en: { text, requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false } },
      },
    },
  };
}

it('refreshes translations without replacing execution facts and rejects obsolete and different-source responses', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const pending: ((value: unknown) => void)[] = [];
  const request = vi.fn(async () =>
    request.mock.calls.length === 1
      ? descriptor('Name')
      : new Promise<unknown>((resolve) => pending.push(resolve)),
  );
  const context = createModuleContext({
    moduleAlias: 'test.order',
    language,
    http: { request } as unknown as HttpClient,
  });
  await context.runtime.ready;
  const facts = context.runtime.snapshot()?.uiDescriptor;
  const old = context.runtime.refreshTexts!();
  await new Promise<void>((resolve) => setImmediate(resolve));
  const latest = context.runtime.refreshTexts!();
  await new Promise<void>((resolve) => setImmediate(resolve));
  pending[1]({
    ...descriptor('Updated name'),
    textBindings: {
      '/uiDescriptor/defaultEditor/fields/1/label': Object.values(descriptor('Updated name').textBindings)[0],
    },
    actions: [],
    uiDescriptor: { moduleAlias: 'different', defaultEditor: { fields: [] } },
  });
  await latest;
  pending[0](descriptor('Obsolete name'));
  await old;
  expect(context.runtime.presentationSnapshot!()?.uiDescriptor?.defaultEditor?.fields[0].label).toBe(
    'Updated name',
  );
  expect(context.runtime.snapshot()?.uiDescriptor).toBe(facts);
  expect(context.can('update')).toBe(true);
  const changedSource = context.runtime.refreshTexts!();
  await new Promise<void>((resolve) => setImmediate(resolve));
  pending[2](descriptor('Wrong original version', 'v2'));
  await changedSource;
  expect(context.runtime.presentationSnapshot!()?.uiDescriptor?.defaultEditor?.fields[0].label).toBe(
    'Updated name',
  );
  expect(context.runtime.snapshot()?.uiDescriptor?.defaultEditor?.fields[0].label).toBe('名称');
});

it('switches retained availability reason resources without refetching or changing action facts', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const decision = {
    actionCode: 'update',
    available: false,
    reason: '平台托管应用不可编辑',
    reasonText: {
      source: {
        sourceKind: 'CODE',
        sourceId: 'platform.managed-application.edit-unavailable',
        sourceVersion: 'v1',
        originalText: '平台托管应用不可编辑',
      },
      languages: {
        en: {
          text: 'Platform-managed applications cannot be edited',
          requestedTag: 'en',
          resourceTag: 'en',
          missing: false,
          fallback: false,
        },
        'zh-CN': {
          text: '平台托管应用不可编辑',
          requestedTag: 'zh-CN',
          resourceTag: 'zh-CN',
          missing: false,
          fallback: false,
        },
      },
    },
  };
  const request = vi.fn(async (options: { path: string }) =>
    options.path === '/test.order/actions/record'
      ? { recordId: 'record', actions: [decision] }
      : {
          moduleAlias: 'test.order',
          capabilities: [],
          actions: [{ actionCode: 'update', authorized: true }],
        },
  );
  const context = createModuleContext({
    moduleAlias: 'test.order',
    language,
    http: { request } as unknown as HttpClient,
  });
  await context.runtime.ready;
  await context.recordActions('record');
  expect(context.action('update', 'record')?.reason).toBe('Platform-managed applications cannot be edited');
  language.setLanguage('zh-CN');
  expect(context.action('update', 'record')?.reason).toBe('平台托管应用不可编辑');
  language.setLanguage('en');
  expect(context.can('update', 'record')).toBe(false);
  expect(context.recordActionsSnapshot('record')?.actions[0]).toEqual(decision);
  expect(request).toHaveBeenCalledTimes(2);
});
