import { expect, it } from 'vitest';
import { generatedMetadataAlias } from '@/views/metadataNaming';

it('generates legal metadata aliases from Chinese and mixed titles', () => {
  expect(generatedMetadataAlias('参考学生')).toBe('can_kao_xue_sheng');
  expect(generatedMetadataAlias('测试2')).toBe('ce_shi2');
  expect(generatedMetadataAlias('2026 Students')).toBe('entity_2026_students');
  expect(generatedMetadataAlias('')).toBe('');
  expect(generatedMetadataAlias('学生'.repeat(100), 53)).toHaveLength(53);
});
