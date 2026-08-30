import { mount, shallowMount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import PageCompositionDescriptorPreview from '@/views/PageCompositionDescriptorPreview.vue';
import type { ResolvedModuleUiDescriptor } from '@/web-contracts/index.ts';

it('uses the standard list cell semantic component for descriptor list and card previews', () => {
  const card = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptor(), mode: 'card' },
  });

  const cardCells = card.findAllComponents({ name: 'RecordQueryListCell' });
  expect(cardCells).toHaveLength(4);
  expect(cardCells[0].props('column')).toMatchObject({ key: 'enabled', type: 'enabledStatus' });
  expect(cardCells[1].props('column')).toMatchObject({ key: 'tags', type: 'tagList' });

  const list = shallowMount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptor(), mode: 'list' },
    global: {
      stubs: {
        UiDataTable: {
          props: ['columns', 'rows'],
          template: `
            <div>
              <template v-for="row in rows" :key="row.id">
                <template v-for="column in columns" :key="column.key">
                  <slot name="cell" :record="row" :column="column" />
                </template>
              </template>
            </div>
          `,
        },
      },
    },
  });

  expect(list.findAllComponents({ name: 'RecordQueryListCell' })).toHaveLength(2);
});

it('keeps list and card keyboard actions aligned with detail inspection', async () => {
  const list = mount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptor(), mode: 'list' },
    global: { stubs: { UiDataTable: tableStub } },
  });
  await list.get('button').trigger('keydown', { key: ' ' });
  expect(list.emitted('configureField')).toEqual([['list', 'enabled']]);

  const card = mount(PageCompositionDescriptorPreview, {
    props: { descriptor: descriptor(), mode: 'card' },
  });
  await card.get('[role="button"]').trigger('keydown', { key: 'Enter' });
  expect(card.emitted('selectField')).toEqual([['list', 'enabled']]);
});

const tableStub = {
  props: ['columns', 'rows'],
  template: `
    <div>
      <template v-for="row in rows" :key="row.id">
        <template v-for="column in columns" :key="column.key">
          <slot name="cell" :record="row" :column="column" />
        </template>
      </template>
    </div>
  `,
};

function descriptor(): ResolvedModuleUiDescriptor {
  return {
    schemaVersion: '1',
    moduleAlias: 'platform.module',
    page: {
      template: 'FLAT_MANAGEMENT',
      list: {
        searchPlaceholder: '搜索',
        fields: {
          viewCode: 'list',
          viewKind: 'LIST',
          fields: [
            { fieldRef: { fieldName: 'enabled' }, label: '启用状态', uiType: 'enabledStatus' },
            { fieldRef: { fieldName: 'tags' }, label: '标签', uiType: 'tagList' },
          ],
        },
      },
      detail: { emptyDescription: '暂无详情', createTitle: '新建' },
      traits: [],
    },
  };
}
