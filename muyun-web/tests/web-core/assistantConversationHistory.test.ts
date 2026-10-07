import { expect, it } from 'vitest';
import { boundedAssistantConversationHistory } from '@/web-core/assistantRuntime';
import type { AssistantConversationMessage } from '@muyun/web-contracts';

it('retains the user request when platform facts fill the recent history window', () => {
  const request = { role: 'user' as const, text: '先登记新客户，再准备两行订单，核对后分别保存。' };
  const history: AssistantConversationMessage[] = [
    request,
    ...Array.from({ length: 14 }, (_, index) => ({ role: 'status' as const, text: `平台事实 ${index}` })),
  ];
  const bounded = boundedAssistantConversationHistory(history);
  expect(bounded[0]).toEqual(request);
  expect(bounded.at(-1)).toEqual({ role: 'status', text: '平台事实 13' });
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
    '已被后续要求替',
    '原始目标：甲甲',
    '先只核对，暂不',
    '放弃订单，保留',
  ]);
  expect(bounded.some(({ text }) => text.startsWith('放弃订单，保留客户'))).toBe(true);
  expect(bounded.length).toBeLessThanOrEqual(12);
  expect(bounded.every(({ text }) => text.length <= 4_000)).toBe(true);
  expect(bounded.reduce((total, { text }) => total + text.length, 0)).toBeLessThanOrEqual(16_000);
});

it('retains the initial complete goal after repeated brief followups and keeps later cancellation authoritative', () => {
  const goal = {
    role: 'user' as const,
    text: '建立完整应用：单个培训日期，允许重复报名，最后核对两门课程和两笔费用。',
  };
  let history: AssistantConversationMessage[] = [goal];
  for (let round = 0; round < 20; round++)
    history = boundedAssistantConversationHistory([
      ...history,
      { role: 'user', text: round === 19 ? '暂不保存，只核对' : '继续' },
      { role: 'assistant', text: `当前未保存候选 ${round}` },
    ]);
  expect(history[0]).toEqual(goal);
  expect(history.filter(({ role }) => role === 'user').at(-1)?.text).toBe('暂不保存，只核对');
  expect(history.length).toBeLessThanOrEqual(12);
  expect(history.reduce((total, { text }) => total + text.length, 0)).toBeLessThanOrEqual(16000);
});
