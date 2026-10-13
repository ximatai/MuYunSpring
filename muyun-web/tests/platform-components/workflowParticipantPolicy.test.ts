import { expect, it } from 'vitest';
import {
  hasWorkflowParticipants,
  workflowParticipantRules,
} from '@/platform-components/workflowParticipantPolicy';

it('determines participant presence from policy structure rather than a presentation summary', () => {
  expect(hasWorkflowParticipants('{"rules":[{"type":"INITIATOR_SELF"}]}')).toBe(true);
  expect(hasWorkflowParticipants('user:alice')).toBe(true);
  expect(workflowParticipantRules('{"userIds":["alice"]}')).toEqual([
    { type: 'USER', ids: ['alice'], userIds: ['alice'] },
  ]);
  for (const value of [undefined, '{}', '{"rules":[]}', '尚未配置参与人', '{"rules":[null]}']) {
    expect(hasWorkflowParticipants(value)).toBe(false);
  }
});
