import { expect, it } from 'vitest';
import { boundedAssistantConversationHistory } from '@/web-core/assistantRuntime';
import type { AssistantConversationMessage } from '@muyun/web-contracts';

it('retains the user request when platform facts fill the recent history window', () => {
  const request = { role: 'user' as const, text: '先登记新客户，再准备两行订单，核对后分别保存。' };
  const history: AssistantConversationMessage[] = [
    request,
    ...Array.from({ length: 14 }, (_, index) => ({ role: 'assistant' as const, text: `平台事实 ${index}` })),
  ];
  const bounded = boundedAssistantConversationHistory(history);
  expect(bounded[0]).toEqual(request);
  expect(bounded.at(-1)?.text).toBe('平台事实 13');
  expect(bounded).toHaveLength(12);
});

it('keeps recent corrections in order without increasing message or character limits', () => {
  const history: AssistantConversationMessage[] = [
    { role: 'user', text: '已被后续要求替代的更早任务' },
    { role: 'user', text: '原始目标：' + '甲'.repeat(5_000) },
    { role: 'user', text: '先只核对，暂不保存：' + '乙'.repeat(5_000) },
    { role: 'user', text: '放弃订单，保留客户：' + '丙'.repeat(5_000) },
    ...Array.from({ length: 14 }, (_, index) => ({
      role: 'assistant' as const,
      text: `${index}:` + '丁'.repeat(5_000),
    })),
  ];
  const bounded = boundedAssistantConversationHistory(history);
  expect(bounded.filter(({ role }) => role === 'user').map(({ text }) => text.slice(0, 7))).toEqual([
    '原始目标：甲甲',
    '先只核对，暂不',
    '放弃订单，保留',
  ]);
  expect(bounded.at(-1)?.text).toBe(history.at(-1)!.text.slice(0, 4_000));
  expect(bounded.length).toBeLessThanOrEqual(12);
  expect(bounded.every(({ text }) => text.length <= 4_000)).toBe(true);
  expect(bounded.reduce((total, { text }) => total + text.length, 0)).toBeLessThanOrEqual(16_000);
});
