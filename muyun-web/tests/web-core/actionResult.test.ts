import { expect, it } from 'vitest';
import { resolveWebActionResult } from '@/web-core/actionResult';

it('retains safe message parameters for translation alongside the machine code and default text', () => {
  const result = resolveWebActionResult({
    message: { code: 'record.saved', text: '已保存', type: 'SUCCESS', messageArgs: { count: 2 } },
  });
  expect(result).toMatchObject({ messageCode: 'record.saved', message: '已保存', messageArgs: { count: 2 } });
  expect(resolveWebActionResult({ message: { text: 'done', messageArgs: [] } }).messageArgs).toBeUndefined();
});
