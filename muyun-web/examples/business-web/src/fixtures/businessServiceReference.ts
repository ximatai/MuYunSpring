import type { ReferencePickerProvider, ReferencePickerTreeNode } from '@ximatai/muyun-web-app';

const tree: ReferencePickerTreeNode[] = [
  {
    record: { id: 'service-delivery', title: '交付服务' },
    children: [
      { record: { id: 'service-install', title: '现场安装' } },
      { record: { id: 'service-training', title: '操作培训' } },
    ],
  },
  {
    record: { id: 'service-maintenance', title: '运维服务' },
    children: [
      { record: { id: 'service-inspection', title: '定期巡检' } },
      { record: { id: 'service-retired', title: '旧版维护套餐', disabled: true } },
    ],
  },
];
const records = tree.flatMap((node) => [node.record, ...(node.children ?? []).map((child) => child.record)]);

/** Local demonstration data; production providers must enforce the source's authorized scope. */
export const businessServiceReferenceProvider: ReferencePickerProvider = {
  identity: {
    targetModuleAlias: 'business.service',
    source: { kind: 'businessPurpose', id: 'customer-contracted-services' },
  },
  async loadTree() {
    return tree;
  },
  async searchPage({ keyword, pageNum, pageSize }) {
    const matches = records.filter((record) => record.title.includes(keyword));
    return {
      records: matches.slice((pageNum - 1) * pageSize, pageNum * pageSize),
      total: matches.length,
    };
  },
  async resolve(ids) {
    return ids.flatMap((id) => records.filter((record) => record.id === id));
  },
};
