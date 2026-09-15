import type {
  ReferencePickerCandidate,
  ReferencePickerPageRequest,
  ReferencePickerProvider,
  ReferencePickerSourceIdentity,
} from '@ximatai/muyun-web-app';

export interface BusinessContactReference extends ReferencePickerCandidate {
  projections: {
    company: string;
    region: string;
  };
}

const contactRows: readonly (readonly [string, string, string, string])[] = [
  ['contact-001', '陈思远', '星云软件', '华东'],
  ['contact-002', '周予安', '云帆智造', '华东'],
  ['contact-003', '林知夏', '远航医疗', '华南'],
  ['contact-004', '宋嘉树', '北辰物流', '华北'],
  ['contact-005', '许清欢', '新岭零售', '华中'],
  ['contact-006', '高明远', '海岳能源', '华东'],
  ['contact-007', '叶书言', '智联教育', '华南'],
  ['contact-008', '方雨桐', '启程咨询', '西南'],
  ['contact-009', '顾闻洲', '万象传媒', '华东'],
  ['contact-010', '沈星河', '青禾农业', '华中'],
  ['contact-011', '陆之行', '山海文旅', '西南'],
  ['contact-012', '唐若川', '卓越汽车', '华北'],
  ['contact-013', '白露', '凌云通信', '华东'],
  ['contact-014', '程向北', '元启科技', '华东'],
  ['contact-015', '夏微澜', '松风家居', '华南'],
  ['contact-016', '裴承宇', '博川金融', '华北'],
  ['contact-017', '吴悠然', '嘉木食品', '华中'],
  ['contact-018', '郑景行', '澄明环保', '华东'],
  ['contact-019', '杜云舟', '云栖酒店', '西南'],
  ['contact-020', '江晚晴', '安澜保险', '华南'],
  ['contact-021', '钟致远', '华彩服饰', '华东'],
  ['contact-022', '罗以宁', '瀚海工程', '华北'],
  ['contact-023', '魏清嘉', '禾谷供应链', '华中'],
  ['contact-024', '苏南乔', '光年设计', '华东'],
  ['contact-025', '冯昭华', '朗月生物', '华南'],
  ['contact-026', '何见山', '微澜数据', '西南'],
  ['contact-027', '谢知行', '正源电子', '华东'],
  ['contact-028', '彭书仪', '蓝湾贸易', '华南'],
] as const;

const candidates: readonly BusinessContactReference[] = contactRows.map(([id, title, company, region]) => ({
  id,
  title,
  subtitle: `${company} · ${region}`,
  projections: { company, region },
}));

/**
 * Example-only deterministic data source. A real business application obtains
 * this data from its own authorized server endpoint; it is not a permission API.
 */
export function createBusinessContactReferenceProvider(
  source: ReferencePickerSourceIdentity['source'],
): ReferencePickerProvider<BusinessContactReference> {
  return {
    identity: {
      targetModuleAlias: 'business-contact',
      source,
      authorizationScope: 'example-fixture-v1',
    },
    async searchPage({ keyword, pageNum, pageSize }: ReferencePickerPageRequest) {
      const normalizedKeyword = keyword.trim().toLocaleLowerCase('zh-CN');
      const matching = normalizedKeyword
        ? candidates.filter((candidate) =>
            [candidate.title, candidate.subtitle, candidate.projections.company, candidate.projections.region]
              .join(' ')
              .toLocaleLowerCase('zh-CN')
              .includes(normalizedKeyword),
          )
        : candidates;
      const offset = Math.max(0, pageNum - 1) * pageSize;
      return { records: matching.slice(offset, offset + pageSize), total: matching.length };
    },
    async resolve(ids) {
      const requested = new Set(ids);
      return candidates.filter((candidate) => requested.has(candidate.id));
    },
  };
}
