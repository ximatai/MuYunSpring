import type {
  ReferencePickerCandidate,
  ReferencePickerNavigationAxis,
  ReferencePickerProvider,
} from '@ximatai/muyun-web-app';

export type DirectoryAuthorizationScope = 'all' | 'east';

interface DirectoryCandidate extends ReferencePickerCandidate {
  projections: { directory: string; category: string };
}

const records: DirectoryCandidate[] = ['east', 'west'].flatMap((directory) =>
  Array.from({ length: 24 }, (_, index) => ({
    id: `${directory}-${index + 1}`,
    title: `${directory === 'east' ? '华东' : '西部'}服务 ${index + 1}`,
    projections: { directory, category: index % 2 === 0 ? 'delivery' : 'maintenance' },
  })),
);
const retired: DirectoryCandidate = {
  id: 'retired-service',
  title: '历史服务套餐',
  subtitle: '可回显，已停止接受新引用',
  disabled: true,
  projections: { directory: 'east', category: 'maintenance' },
};

/**
 * Deterministic UI sample only. A production business endpoint must enforce the source's
 * authorization and historical-display scope on the server, including navigation values.
 */
export function createBusinessDirectoryReferenceProvider(
  authorizationScope: DirectoryAuthorizationScope,
): ReferencePickerProvider {
  const visible = records.filter(
    (candidate) => authorizationScope === 'all' || candidate.projections.directory === 'east',
  );
  const navigation: ReferencePickerNavigationAxis[] = [
    {
      id: 'directory',
      title: '服务目录',
      items: [
        { id: 'east', title: '华东目录' },
        ...(authorizationScope === 'all' ? [{ id: 'west', title: '西部目录' }] : []),
      ],
    },
    {
      id: 'category',
      title: '服务分类',
      dependsOn: ['directory'],
      items: [
        { id: 'delivery', title: '交付' },
        { id: 'maintenance', title: '运维' },
      ],
    },
  ];
  return {
    identity: {
      targetModuleAlias: 'business.service',
      source: { kind: 'businessPurpose', id: 'cross-directory-contract-services' },
      authorizationScope,
    },
    async searchPage({ keyword, pageNum, pageSize, scope }) {
      const matches = visible.filter(
        (candidate) =>
          candidate.title.includes(keyword.trim()) &&
          scope.selections.every(
            ({ axisId, itemId }) =>
              (axisId === 'directory' || axisId === 'category') && candidate.projections[axisId] === itemId,
          ),
      );
      return {
        records: matches.slice((pageNum - 1) * pageSize, pageNum * pageSize),
        total: matches.length,
        navigation,
      };
    },
    async resolve(ids) {
      // Historical display is independent of a search page. Hidden/deleted IDs are omitted.
      return ids.flatMap((id) => {
        const candidate = [...visible, retired].find((item) => item.id === id);
        return candidate ? [candidate] : [];
      });
    },
  };
}
