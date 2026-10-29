import { expect, it } from 'vitest';
import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { createAssistantSurfaceRegistry, createLanguageContext } from '@muyun/web-core';
import { useCodeConfigurationSession } from '@/views/useCodeConfigurationSession';
import { createCodeConfigurationAssistantSurface } from '@/views/codeConfigurationAssistantSurface';
import { newCodeRule, newCodeSegment, type CodeTarget } from '@/views/codeConfiguration';

const target: CodeTarget = {
  moduleAlias: 'demo.order',
  entityAlias: 'order',
  title: '订单',
  source: 'STATIC',
  fields: [{ fieldName: 'documentNo', title: '单号', role: 'PRIMARY', trigger: 'SAVE' }],
  sources: [{ fieldName: 'department', title: '部门', type: 'STRING' }],
};
function harness() {
  let session!: ReturnType<typeof useCodeConfigurationSession>;
  const wrapper = mount(
    defineComponent({
      setup() {
        session = useCodeConfigurationSession(createLanguageContext({ languageTag: 'zh-CN' }));
        return () => h('div');
      },
    }),
  );
  session.reset(newCodeRule(target, target.fields[0]!));
  let editable = true;
  let finishTrial!: (value: { value: string; segments: [] }) => void;
  const registry = createAssistantSurfaceRegistry();
  registry.activate('code');
  registry.register({
    pageInstanceKey: 'code',
    executionScopeKey: () => 'tenant',
    contextRevision: () => JSON.stringify([session.draft.value, session.recoveryRequired.value, editable]),
    interactionRevision: () => JSON.stringify(session.draft.value),
    surface: createCodeConfigurationAssistantSurface(
      {
        state: () => ({
          ready: true,
          editable,
          dirty: session.dirty.value,
          busy: false,
          recoveryRequired: session.recoveryRequired.value,
          draft: session.draft.value,
          target,
        }),
        prepare: (patch, current) => session.prepare(patch, target, current),
        trial: async (_signal, current) => {
          const result = await new Promise<{ value: string; segments: [] }>((resolve) => {
            finishTrial = resolve;
          });
          if (!current()) throw new Error('stale');
          return result;
        },
      },
      async () => {
        throw new Error('No model call expected');
      },
    ),
  });
  const invoke = (code: string, input: unknown = {}) =>
    registry.invoke({ id: 'call', code, input }, registry.snapshot()!.token);
  return {
    wrapper,
    session,
    registry,
    invoke,
    setEditable: (value: boolean) => {
      editable = value;
    },
    finishTrial: (value: string) => finishTrial({ value, segments: [] }),
  };
}
it('shares human edits and atomic assistant changes while rejecting identity overrides and stale commits', async () => {
  const page = harness();
  try {
    page.session.draft.value!.title = '人工规则';
    const described = await page.invoke('code-rule.describe-draft');
    expect(JSON.stringify(described)).toContain('人工规则');
    await page.invoke('code-rule.revise-draft', { title: '协作规则', allowRecycle: true });
    expect(page.session.draft.value!.title).toBe('协作规则');
    expect(page.session.dirty.value).toBe(true);
    const before = JSON.stringify(page.session.draft.value);
    await expect(page.invoke('code-rule.revise-draft', { moduleAlias: 'other.module' })).rejects.toThrow();
    await expect(page.invoke('code-rule.revise-draft', { mode: ['AUTO'] })).rejects.toThrow();
    await expect(
      page.invoke('code-rule.revise-draft', { segments: [{ ...newCodeSegment(), id: 'forged' }] }),
    ).rejects.toThrow();
    await expect(
      page.invoke('code-rule.revise-draft', {
        segments: [{ ...newCodeSegment('FIELD_VALUE'), sourceRef: 'password' }],
      }),
    ).rejects.toThrow();
    expect(JSON.stringify(page.session.draft.value)).toBe(before);
    const commit = page.session.prepare({ title: 'late' }, target, () => true);
    page.session.draft.value!.title = '人工继续';
    expect(commit).toThrow();
    page.setEditable(false);
    expect(page.registry.snapshot()!.capabilities.map((item) => item.code)).not.toContain(
      'code-rule.revise-draft',
    );
    page.session.recoveryRequired.value = true;
    expect(page.registry.snapshot()!.capabilities.map((item) => item.code)).not.toContain(
      'code-rule.preview-draft',
    );
  } finally {
    page.wrapper.unmount();
  }
});
it('redacts unavailable source expressions while preserving them during unrelated edits', async () => {
  const page = harness();
  try {
    page.session.draft.value!.segments = [{ ...newCodeSegment('FORMULA'), formulaExpr: '{password}' }];
    expect(JSON.stringify(await page.invoke('code-rule.describe-draft'))).not.toContain('password');
    await page.invoke('code-rule.revise-draft', { title: '只改名称' });
    expect(page.session.draft.value!.segments[0]!.formulaExpr).toBe('{password}');
  } finally {
    page.wrapper.unmount();
  }
});
it('rejects late preview results after the shared draft changes', async () => {
  const page = harness();
  try {
    const preview = page.invoke('code-rule.preview-draft');
    const rejected = expect(preview).rejects.toThrow();
    await Promise.resolve();
    await Promise.resolve();
    page.session.draft.value!.title = 'newer';
    page.finishTrial('NO-0001');
    await rejected;
  } finally {
    page.wrapper.unmount();
  }
});
