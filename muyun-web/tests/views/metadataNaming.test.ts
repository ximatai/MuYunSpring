import { expect, it } from 'vitest';
import { generatedMetadataAlias, generatedBusinessFieldName, physicalNameOf } from '@/views/metadataNaming';

it('generates legal metadata aliases from Chinese and mixed titles', () => {
  expect(generatedMetadataAlias('参考学生')).toBe('can_kao_xue_sheng');
  expect(generatedMetadataAlias('测试2')).toBe('ce_shi2');
  expect(generatedMetadataAlias('2026 Students')).toBe('entity_2026_students');
  expect(generatedMetadataAlias('')).toBe('');
  expect(generatedMetadataAlias('学生'.repeat(100), 53)).toHaveLength(53);
});

it.each([
  ['BASIC', '任务名称', 'renWuMingCheng', 'ren_wu_ming_cheng'],
  ['DICTIONARY', '性别', 'dictXingBie', 'dict_xing_bie'],
  ['MODULE_REFERENCE', '负责人', 'refFuZeRenId', 'ref_fu_ze_ren_id'],
  ['MODULE_REFERENCE', '审核人', 'refShenHeRenId', 'ref_shen_he_ren_id'],
] as const)('generates business-role names for %s', (kind, title, name, column) => {
  expect(generatedBusinessFieldName(title, kind)).toBe(name);
  expect(physicalNameOf(name)).toBe(column);
  expect(generatedBusinessFieldName('', kind)).toBe('');
});
