import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../../src/views/BusinessLogRetentionControl.vue'),
  'utf8',
);

it('embeds retention as a permission-aware control instead of a standalone page', () => {
  expect(source).toContain("moduleAlias: 'platform.business_log_retention'");
  expect(source).toContain("runtimeAccess: 'VIEW'");
  expect(source).toContain('<UiActionButton v-if="canView" @click="openPanel">留存设置</UiActionButton>');
  expect(source).toContain('visibleTypes.has(policy.eventType)');
});

it('prevents a retention draft from changing the destructive cleanup contract', () => {
  expect(source).toContain('if (!persisted || isDirty(policy) || purgingType.value) return;');
  expect(source).toContain('${persisted.retentionDays} 天');
  expect(source).toContain(':disabled="!validDays(policy.retentionDays) || isDirty(policy)"');
  expect(source).toContain('请先保存当前策略，再执行清理');
});

it('reports an already-running cleanup as a non-successful informational outcome', () => {
  expect(source).toContain("if (run.result.status === 'ALREADY_RUNNING')");
  expect(source).toContain("showInfoMessage('已有日志清理任务正在运行，本次未执行。')");
});
