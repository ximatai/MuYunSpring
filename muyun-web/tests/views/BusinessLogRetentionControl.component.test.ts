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
  expect(source).toContain('<UiActionButton v-if="canView" @click="openPanel">自动清理</UiActionButton>');
  expect(source).toContain('title="自动清理"');
  expect(source).toContain('width="standard"');
  expect(source).toContain('visibleTypes.has(policy.eventType)');
});

it('presents each retention policy as a card with distinct header, content, and save regions', () => {
  expect(source).toContain('class="business-log-retention-control__automatic-cleanup"');
  expect(source).toContain('class="business-log-retention-control__retention-row"');
  expect(source).toMatch(/business-log-retention-control__retention-row[\s\S]*?<UiInput[\s\S]*?立即清理/);
  expect(source).toMatch(/<footer>[\s\S]*?>\s*保存\s*<\/UiActionButton>/);
});

it('runs one-off cleanup with the current draft cutoff without requiring a policy save', () => {
  expect(source).toContain('if (!validDays(policy.retentionDays) || operationPending.value) return;');
  expect(source).toContain('${policy.retentionDays} 天');
  expect(source).toContain('本次清理不会保存对保留天数的修改');
  expect(source).toContain('client.purge(policy.eventType, policy.retentionDays)');
  expect(source).toContain(':disabled="operationPending || !validDays(policy.retentionDays)"');
  expect(source).not.toContain('请先保存当前策略，再执行清理');
});

it('locks every policy card and the drawer while one retention operation is in flight', () => {
  expect(source).toContain(
    'const operationPending = computed(() => savingType.value !== undefined || purgingType.value !== undefined)',
  );
  expect(source).toContain("showInfoMessage('自动清理操作正在执行，请稍候。')");
  expect(source).toContain(':disabled="!canConfigure || operationPending"');
  expect(source).toContain(
    ':disabled="operationPending || !validDays(policy.retentionDays) || !isDirty(policy)"',
  );
  expect(source).toContain(':disabled="operationPending || !validDays(policy.retentionDays)"');
});

it('reports an already-running cleanup as a non-successful informational outcome', () => {
  expect(source).toContain("if (run.result.status === 'ALREADY_RUNNING')");
  expect(source).toContain("showInfoMessage('已有日志清理任务正在运行，本次未执行。')");
});
