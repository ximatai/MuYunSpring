import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { createHttpClient, createModuleContext, withHttpHeaders } from '@muyun/web-core';
import {
  optionFieldState,
  resolveRecordFormFields,
  settleOptionFieldItems,
} from '@muyun/platform-components';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import {
  createRecordFormAssistantCapabilities,
  recordFormAssistantFacts,
} from '@/dynamic-page-runtime/recordFormAssistantCapabilities';
import type { RecordFormDraftAccess } from '@/dynamic-page-runtime/recordFormDraftAccess';
import type { CurrentUser } from '@muyun/web-contracts';

declare const __PLATFORM_SERVICE_ENABLED__: boolean;
declare const __PLATFORM_BUSINESS_TOKEN__: string;

// Requires a tenant user in the disposable school-demo service, not a platform superuser.
it.skipIf(!__PLATFORM_SERVICE_ENABLED__ || !__PLATFORM_BUSINESS_TOKEN__)(
  'shares real authorized dictionary candidates and withdraws them after a denied tenant switch',
  async () => {
    const http = createHttpClient({ baseUrl: '/api', token: __PLATFORM_BUSINESS_TOKEN__ });
    const user = await http.request<CurrentUser>({ path: '/iam.auth/context' });
    expect(user.system).toBe(false);
    const context = createModuleContext({ moduleAlias: 'education.exam', runtimeAccess: 'VIEW', http });
    const runtime = await context.runtime.ready;
    const descriptor = resolveRecordFormFields(runtime.uiDescriptor, 'exam_participant').get(
      'attendanceStatus',
    );
    expect(descriptor?.option?.inlineItems?.length ?? 0).toBe(0);
    expect(descriptor?.option?.binding.sourceType).toBe('dictionary');
    const fields = new Map([['attendanceStatus', descriptor!]]);
    let activeContext = context;
    const record: Record<string, unknown> = {};
    const form: RecordFormDraftAccess = {
      editorMode: 'create',
      editingRecord: record,
      formFields: fields,
      referencePickerConfigs: {},
      contextRevision: () => String(activeContext === context),
      optionState: (name) => optionFieldState(activeContext, name, 'exam_participant'),
      updateDraftFields: (changes) => {
        changes.forEach(({ fieldName, value }) => {
          record[fieldName] = value;
        });
      },
      updateDraftReference: () => {
        throw new Error('Not a reference scenario');
      },
    };
    const wrapper = mount(RecordFormFields, {
      attachTo: document.body,
      props: {
        fields,
        record,
        mode: 'create',
        optionContext: activeContext,
        optionEntityAlias: 'exam_participant',
      },
    });
    try {
      await expect
        .poll(() => optionFieldState(activeContext, 'attendanceStatus', 'exam_participant').status)
        .toBe('ready');
      await settleOptionFieldItems(
        activeContext,
        ['attendanceStatus'],
        new AbortController().signal,
        'exam_participant',
      );
      const facts = recordFormAssistantFacts(form);
      expect(facts.fields[0].options).toEqual(
        expect.arrayContaining([
          { value: 'ATTENDED', label: '已参加' },
          { value: 'ABSENT', label: '缺考' },
        ]),
      );
      const patch = createRecordFormAssistantCapabilities(form)().find(
        (capability) => capability.descriptor.code === 'form.patch-draft',
      )!;
      const execution = {
        signal: new AbortController().signal,
        isCurrent: () => true,
        commitInternalState: <T>(apply: () => T) => apply(),
        applyEffect: <T>(apply: () => T) => apply(),
      };
      await patch.execute(
        patch.parseInput({ changes: [{ fieldName: 'attendanceStatus', value: 'ATTENDED' }] }),
        execution,
      );
      expect(record.attendanceStatus).toBe('ATTENDED');
      activeContext = {
        ...context,
        http: withHttpHeaders(http, { 'X-MuYun-Tenant-Id': 'not-the-current-tenant' }),
      };
      await wrapper.setProps({ optionContext: activeContext });
      await expect
        .poll(() => optionFieldState(activeContext, 'attendanceStatus', 'exam_participant').status)
        .toBe('error');
      expect(recordFormAssistantFacts(form).fields[0]).toMatchObject({
        options: [],
        optionLoadStatus: 'error',
        assistantWritable: false,
      });
      await expect(
        patch.execute(
          patch.parseInput({ changes: [{ fieldName: 'attendanceStatus', value: 'ABSENT' }] }),
          execution,
        ),
      ).rejects.toThrow();
      expect(record.attendanceStatus).toBe('ATTENDED');
    } finally {
      wrapper.unmount();
    }
  },
  30000,
);
