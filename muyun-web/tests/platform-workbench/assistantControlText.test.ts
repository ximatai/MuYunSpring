import { createHash } from 'node:crypto';
import { expect, it, vi } from 'vitest';
import { createLanguageContext } from '@muyun/web-core';
import {
  archiveControlText,
  codeControlText,
  joinControlText,
  resolveControlText,
  restoreControlText,
} from '@/platform-workbench/assistantControlText';

const boundary = () =>
  codeControlText(
    'platform.screen.useAssistantConversation.pending-content-was-not-submitted-any-earlier-saved-results-are',
    '待确认内容没有提交；如此前已有保存结果，不受本次失败影响。',
  );
it('round trips CODE templates without WebCrypto using the shared UTF-8 SHA-256 contract', async () => {
  const originalCrypto = globalThis.crypto;
  vi.stubGlobal('crypto', undefined);
  try {
    const text = codeControlText(
      'platform.screen.useAssistantConversation.this-identity-has-no-available-model-configuration-ask-an-administrator',
      '当前身份缺少可用的模型配置。请联系管理员检查租户模型连接、平台共享范围及凭据配置。{value1}',
      { value1: boundary() },
    );
    const packet = JSON.parse(JSON.stringify(await archiveControlText(text)));
    expect(packet.source.sourceVersion).toBe(
      'sha256-' + createHash('sha256').update(packet.source.originalText, 'utf8').digest('hex'),
    );
    const restored = await restoreControlText(packet);
    expect(restored).toBeDefined();
    const language = createLanguageContext({ languageTag: 'en' });
    expect(resolveControlText(restored!, language)).toContain('Pending content was not submitted');
    language.setLanguage('zh-CN');
    expect(resolveControlText(restored!, language)).toContain('待确认内容没有提交');
  } finally {
    vi.stubGlobal('crypto', originalCrypto);
  }
});
it('rejects the whole projection when any nested source is unknown, changed or has a tampered version', async () => {
  for (const change of ['unknown', 'original', 'version'] as const) {
    const packet = await archiveControlText(joinControlText(boundary(), boundary()));
    if (!('parts' in packet)) throw new Error('Missing test parts');
    const nested = packet.parts[1];
    if (typeof nested === 'string' || !nested || !('source' in nested)) throw new Error('Missing source');
    if (change === 'unknown')
      nested.source.sourceId = 'platform.screen.useAssistantConversation.future-unknown';
    if (change === 'original') {
      nested.source.originalText = '不同版本的原始模板';
      nested.source.sourceVersion =
        'sha256-' + createHash('sha256').update(nested.source.originalText, 'utf8').digest('hex');
    }
    if (change === 'version') nested.source.sourceVersion = 'sha256-' + '0'.repeat(64);
    expect(await restoreControlText(packet)).toBeUndefined();
  }
});
it('retains business parameters verbatim rather than treating their text as a source identity', async () => {
  const text = codeControlText(
    'platform.screen.useAssistantConversation.value-the-goal-has-not-been-verified-as-complete',
    '{value1}，目标尚未核实完成。',
    { value1: '业务原始名称' },
  );
  const restored = await restoreControlText(await archiveControlText(text));
  expect(resolveControlText(restored!, createLanguageContext({ languageTag: 'en' }))).toBe(
    '业务原始名称. The goal has not been verified as complete. ',
  );
});

it('does not accept an arbitrary plain display replacement without a CODE source', async () => {
  expect(await restoreControlText({ parts: ['Different display'] })).toBeUndefined();
});
