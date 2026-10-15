import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
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
  language: LanguageContext = platformLanguageContext,
): UiTreeNode {
  return {
    title: field.title,
    secondary: [
      field.fieldName,
      field.platformReadOnly ? language.t('platform.screen.metadataSourceTree.read-only', '只读') : '',
      field.referenceModuleAlias
        ? language.t('platform.screen.metadataEditorSession.module-reference', '模块引用')
        : '',
    ]
      .filter(Boolean)
      .join(' · '),
    isLeaf: !field.expandable,
    ...options,
  };
}

export function metadataSourceRoot(
  title: string,
  children: UiTreeNode[],
  language: LanguageContext = platformLanguageContext,
): UiTreeNode {
  return {
    key: 'metadata:root',
    title,
    secondary: language.t('platform.screen.MetadataGovernanceSurface.main-metadata', '主元数据'),
    children,
  };
}
