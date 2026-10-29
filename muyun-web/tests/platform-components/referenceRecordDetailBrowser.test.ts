import { expect, it } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { createLanguageContext, withHttpHeaders, type HttpClient, type HttpRequestOptions } from '@/web-core';
import { createReferenceRecordDetailBrowser } from '@/platform-components/referenceRecordDetailBrowser';

it('updates reference detail labels in its own language while preserving record values', async () => {
  const language = createLanguageContext();
  const http = {
    request: async ({ path }: { path: string }) =>
      path.endsWith('context')
        ? {
            moduleAlias: 'demo.target',
            capabilities: [],
            actions: [],
            uiDescriptor: {
              moduleAlias: 'demo.target',
              defaultEditor: {
                viewKind: 'FORM',
                fields: [{ fieldRef: { fieldName: 'title' }, label: '名称' }],
              },
            },
            textBindings: {
              '/uiDescriptor/defaultEditor/fields/0/label': {
                source: {
                  sourceKind: 'CODE',
                  sourceId: 'target.title',
                  sourceVersion: 'v1',
                  originalText: '名称',
                },
                languages: {
                  en: {
                    text: 'Name',
                    requestedTag: 'en',
                    resourceTag: 'en',
                    missing: false,
                    fallback: false,
                  },
                },
              },
            },
          }
        : { id: 'record-1', title: '原始名称' },
  } as HttpClient;
  const browser = createReferenceRecordDetailBrowser(http, language);
  browser.open('demo.target', 'record-1');
  await flushPromises();
  expect(browser.fields.value.get('title')?.label).toBe('名称');
  language.setLanguage('en');
  expect(browser.fields.value.get('title')?.label).toBe('Name');
  expect(browser.active.value?.record?.title).toBe('原始名称');
  expect(browser.activeContext.value?.runtime.snapshot()?.uiDescriptor?.defaultEditor?.fields[0]?.label).toBe(
    '名称',
  );
});

it('binds independent record context to each target request while retaining the tenant session', async () => {
  const requests: HttpRequestOptions[] = [];
  const transport = {
    request: async (options: HttpRequestOptions) => {
      requests.push(options);
      return options.path.endsWith('context')
        ? { moduleAlias: options.path.split('/')[2], capabilities: [], actions: [] }
        : { id: options.path.split('/').at(-1), title: '目标' };
    },
  } as HttpClient;
  const browser = createReferenceRecordDetailBrowser(
    withHttpHeaders(transport, { 'X-MuYun-Tenant-Id': 'tenant-a' }),
  );
  for (const [moduleAlias, recordId] of [
    ['demo.target', 'record-1'],
    ['demo.target', 'record-2'],
    ['demo.other', 'record-3'],
  ]) {
    browser.open(moduleAlias!, recordId!);
    await flushPromises();
    const request = requests.at(-1)!;
    expect(request.path).toBe(`/${moduleAlias}/view/${recordId}`);
    expect(request.headers).toMatchObject({
      'X-MuYun-Tenant-Id': 'tenant-a',
      'X-MuYun-Record-Context': JSON.stringify({ moduleAlias, recordId }),
    });
  }
  expect(
    requests
      .filter((request) => request.path.endsWith('context'))
      .every((request) => request.headers?.['X-MuYun-Record-Context'] === undefined),
  ).toBe(true);
  const nested = createReferenceRecordDetailBrowser(browser.activeContext.value!.http);
  nested.open('demo.other', 'nested-record');
  await flushPromises();
  expect(requests.at(-1)?.headers?.['X-MuYun-Record-Context']).toBe(
    JSON.stringify({ moduleAlias: 'demo.other', recordId: 'nested-record' }),
  );
  expect(browser.active.value?.recordId).toBe('record-3');
  nested.dispose();
  browser.dispose();
});

function navigationBrowser() {
  const reads: string[] = [];
  const http: HttpClient = {
    async request(options) {
      reads.push(options.path);
      return (
        options.path.endsWith('context')
          ? { moduleAlias: 'demo.target', actions: [], capabilities: [] }
          : { id: options.path.split('/').at(-1), title: '目标' }
      ) as never;
    },
  };
  return { browser: createReferenceRecordDetailBrowser(http), reads };
}

it('keeps the current record when the owner rejects a switch or close', async () => {
  const { browser, reads } = navigationBrowser();
  await browser.open('demo.target', 'a');
  browser.setBeforeLeave(() => false);
  await browser.open('demo.target', 'b');
  await browser.close();
  expect(browser.active.value?.recordId).toBe('a');
  expect(reads).not.toContain('/demo.target/view/b');
});

it('shares a pending leave confirmation and commits only the latest requested target', async () => {
  const { browser, reads } = navigationBrowser();
  await browser.open('demo.target', 'a');
  let finish!: (allowed: boolean) => void;
  let confirmations = 0;
  browser.setBeforeLeave(() => {
    confirmations++;
    return new Promise<boolean>((resolve) => {
      finish = resolve;
    });
  });
  const first = browser.open('demo.target', 'b');
  const second = browser.open('demo.target', 'c');
  expect(confirmations).toBe(1);
  finish(true);
  await Promise.all([first, second]);
  expect(browser.active.value?.recordId).toBe('c');
  expect(reads).not.toContain('/demo.target/view/b');
});

it('does not replace a record if an operation starts while confirmation is pending', async () => {
  const { browser } = navigationBrowser();
  await browser.open('demo.target', 'a');
  let finish!: (allowed: boolean) => void;
  browser.setBeforeLeave(
    () =>
      new Promise<boolean>((resolve) => {
        finish = resolve;
      }),
  );
  const opening = browser.open('demo.target', 'b');
  browser.setBusy('demo.target', 'a', true);
  finish(true);
  await opening;
  expect(browser.active.value?.recordId).toBe('a');
  expect(browser.active.value?.busy).toBe(true);
});

it('teardown invalidates an approved late navigation and mutations do not clear owner busy state', async () => {
  const { browser } = navigationBrowser();
  await browser.open('demo.target', 'a');
  browser.setBusy('demo.target', 'a', true);
  browser.reportMutation({
    type: 'saved',
    targetModuleAlias: 'demo.target',
    recordId: 'a',
    record: { id: 'a', title: '已更新' },
  });
  expect(browser.active.value?.busy).toBe(true);
  browser.setBusy('demo.target', 'a', false);
  let finish!: (allowed: boolean) => void;
  browser.setBeforeLeave(
    () =>
      new Promise<boolean>((resolve) => {
        finish = resolve;
      }),
  );
  const opening = browser.open('demo.target', 'b');
  browser.dispose();
  finish(true);
  await opening;
  expect(browser.active.value).toBeUndefined();
});

it('returns distinct data, guard and lifecycle outcomes without depending on a Workbench', async () => {
  const browser = createReferenceRecordDetailBrowser({
    request: async ({ path }) =>
      path.endsWith('/view-context')
        ? ({ moduleAlias: 'demo.target', actions: [], capabilities: [] } as never)
        : ({ id: 'one', title: 'One' } as never),
  });
  expect(await browser.open('', 'one')).toEqual({ status: 'rejected', reason: 'invalid-target' });
  expect(await browser.open('demo.target', 'one')).toEqual({ status: 'loaded' });
  browser.setBusy('demo.target', 'one', true);
  expect(await browser.open('demo.target', 'two')).toEqual({ status: 'rejected', reason: 'busy' });
  browser.setBusy('demo.target', 'one', false);
  const releaseLeave = browser.setBeforeLeave(() => false);
  expect(await browser.open('demo.target', 'two')).toEqual({ status: 'rejected', reason: 'leave-blocked' });
  releaseLeave();
  const releasePresentation = browser.setBeforeOpen(() => undefined);
  releasePresentation();
  expect(await browser.open('demo.target', 'two')).toEqual({ status: 'rejected', reason: 'missing-host' });
});
