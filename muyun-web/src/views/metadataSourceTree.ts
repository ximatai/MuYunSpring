import type { UiTreeNode } from '@muyun/vue-ui-antdv';

/** Shared metadata presentation; consumers supply actions and capability restrictions. */
export function metadataSourceFieldNode(
  field: {
    title: string;
    fieldName: string;
    platformReadOnly?: boolean;
    referenceModuleAlias?: string;
    expandable?: boolean;
  },
  options: Pick<UiTreeNode, 'key'> & Partial<UiTreeNode>,
): UiTreeNode {
  return {
    title: field.title,
    secondary: [
      field.fieldName,
      field.platformReadOnly ? '只读' : '',
      field.referenceModuleAlias ? '模块引用' : '',
    ]
      .filter(Boolean)
      .join(' · '),
    isLeaf: !field.expandable,
    ...options,
  };
}

export function metadataSourceRoot(title: string, children: UiTreeNode[]): UiTreeNode {
  return { key: 'metadata:root', title, secondary: '主元数据', children };
}
