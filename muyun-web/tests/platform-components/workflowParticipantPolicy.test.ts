import { expect, it } from 'vitest';
import {
  hasWorkflowParticipants,
  workflowParticipantRules,
} from '@/platform-components/workflowParticipantPolicy';

it('normalizes server-supported participant forms and default USER types', () => {
  for (const value of [
    'user:alice;bob',
    'alice,bob',
    '{"userIds":["alice","bob"]}',
    '{"rules":[{"ids":["alice","bob"]}]}',
    '["alice",{"userId":"bob"}]',
  ]) {
    expect(hasWorkflowParticipants(value)).toBe(true);
    expect(workflowParticipantRules(value).flatMap((rule) => rule.ids ?? [])).toEqual(['alice', 'bob']);
  }
  expect(workflowParticipantRules('{"type":"department","targetIds":["dept"]}')).toEqual([
    { type: 'DEPT', ids: ['dept'] },
  ]);
  expect(hasWorkflowParticipants('{"rules":[{"type":"INITIATOR_SELF"}]}')).toBe(true);
  expect(hasWorkflowParticipants('尚未配置参与人')).toBe(true); // A legal bare user identifier, not a status message.
  for (const value of [
    undefined,
    '{}',
    '{"rules":[]}',
    'unknown:alice',
    '{"rules":[null]}',
    '{"type":"FIELD"}',
  ]) {
    expect(hasWorkflowParticipants(value)).toBe(false);
  }
});
