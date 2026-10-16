import { expect, it } from 'vitest';
import { resolveWebActionResult } from '@/web-core/actionResult';

it('retains safe message parameters for translation alongside the machine code and default text', () => {
  const result = resolveWebActionResult({
    message: { code: 'record.saved', text: '已保存', type: 'SUCCESS', messageArgs: { count: 2 } },
  });
  expect(result).toMatchObject({ messageCode: 'record.saved', message: '已保存', messageArgs: { count: 2 } });
  expect(resolveWebActionResult({ message: { text: 'done', messageArgs: [] } }).messageArgs).toBeUndefined();
});

it('retains machine message facts without default text and uses the caller display fallback', () => {
  for (const text of [undefined, '', '   ']) {
    expect(
      resolveWebActionResult(
        { message: { code: 'record.saved', text, type: 'SUCCESS', messageArgs: { count: 2 } } },
        { fallbackMessage: 'Saved' },
      ),
    ).toMatchObject({
      message: 'Saved',
      messageCode: 'record.saved',
      messageType: 'SUCCESS',
      messageArgs: { count: 2 },
    });
  }
});
