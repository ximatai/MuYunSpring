import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page, commands } from 'vitest/browser';
import { configureModuleContext, type HttpClient, type HttpRequestOptions } from '@/web-core';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import PageCompositionWorkspace from '@/views/PageCompositionWorkspace.vue';
import PageCompositionTree from '@/views/PageCompositionTree.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

const PlacementHost = defineComponent({
  props: { height: { type: Number, required: true } },
  setup(props) {
    providePageLayout('workspace');
    return () =>
      h('div', { style: `height:${props.height}px;margin:10px` }, [
        h(PageCompositionWorkspace, { moduleAlias: 'education.placement' }),
      ]);
  },
});

it.each([1440, 980])(
  'places A into B/C precisely and reorders C without changing field facts at %ipx',
  async (width) => {
    await page.viewport(width, 950);
    const requests: HttpRequestOptions[] = [];
    configureModuleContext({ http: placementHttp(requests) });
    const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 900 } });
    const source = (name: string) => `[data-ui-tree-key="metadata:field:${name}"]`;
    const header = (name: string) => `[data-page-composition-layout-key="list:header:${name}"]`;
    const handle = (key: string) => `[data-composer-drag="${key}"]`;
    const target = (key: string) => `[data-composer-target="${key}"][tabindex]`;
    const model = () => wrapper.findComponent(PageCompositionTree);
    const ready = async () => {
      await expect
        .element(page.elementLocator(wrapper.get(handle('list:header:a')).element))
        .toHaveAttribute('aria-disabled', 'false');
      await expect
        .poll(
          () =>
            wrapper
              .get('[data-testid="page-composer-list-preview"]')
              .element.getAnimations({ subtree: true })
              .filter((animation) => animation.playState === 'running').length,
        )
        .toBe(0);
    };
    try {
      await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
      await expect.poll(() => wrapper.find(header('a')).exists()).toBe(true);
      await ready();
      // A -> C: drop at the left edge of the first column, not the canvas root.
      await commands.treeGesture(source('b'), header('a'), 0.5, 'hold', 0.1);
      await expect.poll(() => wrapper.find('.page-composer-drop-indicator').exists()).toBe(true);
      expect(wrapper.get('.page-composer-drop-indicator').attributes('style')).toContain('width: 3px');
      await commands.treeRelease();
      await expect
        .poll(() =>
          model()
            .props('listFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['b', 'a']);
      await ready();
      // A -> B retains the same before/after contract.
      await commands.treeGesture(source('c'), '[data-ui-tree-key="ui:field:list:a"]', 0.1);
      await expect
        .poll(() =>
          model()
            .props('listFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['b', 'c', 'a']);
      await ready();
      // C -> C from a real handle. Drop to the right of a later column; remove-before-insert matters.
      await commands.treeGesture(handle('list:header:b'), header('a'), 0.5, 'drop', 0.9);
      await expect
        .poll(() =>
          model()
            .props('listFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['c', 'a', 'b']);
      await ready();
      await commands.treeGesture(handle('list:header:c'), header('b'), 0.5, 'escape', 0.9);
      expect(
        model()
          .props('listFields')
          .map((field: { id: string }) => field.id),
      ).toEqual(['c', 'a', 'b']);
      // Blank preview background is not an implicit append target.
      await commands.treeGesture(source('b'), '[data-testid="page-composer-list-preview"]', 0.98);
      expect(
        model()
          .props('listFields')
          .map((field: { id: string }) => field.id),
      ).toEqual(['c', 'a', 'b']);
      await page.getByText('表单', { exact: true }).click();
      await expect
        .element(page.elementLocator(wrapper.get(handle('edit:field:a')).element))
        .toHaveAttribute('aria-disabled', 'false');
      // A -> exact empty group; then C -> root form after an existing field.
      await commands.treeGesture(source('c'), target('edit:container:group:empty'), 0.5);
      await expect
        .poll(() =>
          model()
            .props('formGroups')[1]
            .fields.map((field: { id: string }) => field.id),
        )
        .toEqual(['c']);
      await expect
        .element(page.elementLocator(wrapper.get(handle('edit:field:b')).element))
        .toHaveAttribute('aria-disabled', 'false');
      await commands.treeGesture(
        handle('edit:field:b'),
        '[data-page-composition-layout-key="edit:field:a"]',
        0.9,
      );
      await expect
        .poll(() =>
          model()
            .props('formFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['a', 'b']);
      expect(model().props('formFields')[1].properties).toEqual({ label: '自定义乙', readOnly: true });
      await expect
        .element(page.elementLocator(wrapper.get(handle('edit:group:empty')).element))
        .toHaveAttribute('aria-disabled', 'false');
      await commands.treeGesture(handle('edit:group:empty'), target('edit:group:basic'), 0.1);
      await expect
        .poll(() =>
          model()
            .props('formGroups')
            .map((group: { id: string }) => group.id),
        )
        .toEqual(['empty', 'basic']);
      await expect
        .element(page.elementLocator(wrapper.get(handle('edit:field:a')).element))
        .toHaveAttribute('aria-disabled', 'false');
      // Input values remain editable; dragging uses a dedicated grip.
      await page.getByRole('textbox', { name: '字段甲', exact: true }).fill('示例输入');
      expect(model().props('formFields')[0].fieldName).toBe('a');
      await page.getByRole('button', { name: '保存草稿', exact: true }).click();
      await expect.element(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled();
      await page.getByRole('button', { name: '刷新：页面结构', exact: true }).click();
      await expect
        .poll(() =>
          model()
            .props('listFields')
            .map((field: { id: string }) => field.id),
        )
        .toEqual(['c', 'a', 'b']);
      expect(
        model()
          .props('formGroups')
          .map((group: { id: string }) => group.id),
      ).toEqual(['empty', 'basic']);
      expect(requests.filter((request) => /\/fields\/(insert|update|delete)/.test(request.path))).toEqual([]);
    } finally {
      wrapper.unmount();
    }
  },
);

it('targets child-table columns precisely, rejects another relation and moves whole child sections', async () => {
  await page.viewport(1440, 1200);
  configureModuleContext({ http: placementHttp([], true) });
  const wrapper = mount(PlacementHost, { attachTo: document.body, props: { height: 1120 } });
  const model = () => wrapper.findComponent(PageCompositionTree);
  const ids = () =>
    model()
      .props('formRelations')
      .find((relation: { id: string }) => relation.id === 'child')
      ?.fields.map((field: { id: string }) => field.id);
  const grip = '[data-composer-drag="edit:relation:child:header:x"]';
  const ready = async () => {
    await expect
      .element(page.elementLocator(wrapper.get(grip).element))
      .toHaveAttribute('aria-disabled', 'false');
    await expect
      .poll(
        () =>
          wrapper
            .get('[data-testid="page-composer-edit-preview"]')
            .element.getAnimations({ subtree: true })
            .filter((animation) => animation.playState === 'running').length,
      )
      .toBe(0);
  };
  try {
    await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
    await page.getByText('表单', { exact: true }).click();
    await expect.poll(() => wrapper.find(grip).exists()).toBe(true);
    await ready();
    const switcher = wrapper
      .get('[data-ui-tree-key="metadata:relation:child"]')
      .element.closest('.ant-tree-treenode')!
      .querySelector('.ant-tree-switcher')!;
    await page.elementLocator(switcher).click();
    const source = '[data-ui-tree-key="metadata:relation-field:child:y"]';
    await commands.treeGesture(source, '[data-composer-target="edit:relation:other"]', 0.5, 'hold');
    expect(wrapper.find('.page-composer-drop-indicator--rejected').exists()).toBe(true);
    await commands.treeRelease();
    expect(ids()).toEqual(['x']);
    await commands.treeGesture(
      source,
      '[data-page-composition-layout-key="edit:relation:child:header:x"]',
      0.5,
      'drop',
      0.1,
    );
    await expect.poll(ids).toEqual(['y', 'x']);
    await ready();
    await commands.treeGesture(
      grip,
      '[data-page-composition-layout-key="edit:relation:child:header:y"]',
      0.5,
      'drop',
      0.1,
    );
    await expect.poll(ids).toEqual(['x', 'y']);
    await ready();
    await commands.treeGesture(
      '[data-composer-drag="edit:relation:other"]',
      '[data-composer-target="edit:relation:child"]',
      0.1,
    );
    await expect
      .poll(() =>
        model()
          .props('formRelations')
          .map((relation: { id: string }) => relation.id),
      )
      .toEqual(['other', 'child']);
    await expect.element(page.getByRole('radio', { name: '表单', exact: true })).toBeChecked();
  } finally {
    wrapper.unmount();
  }
});

function placementHttp(requests: HttpRequestOptions[], withRelations = false): HttpClient {
  const fields = ['a', 'b', 'c'].map((id, index) => ({
    id,
    fieldName: id,
    title: ['字段甲', '字段乙', '字段丙'][index],
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
  }));
  const children = ['x', 'y', 'z'].map((id) => ({
    id,
    fieldName: id,
    title: `子字段${id}`,
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
  }));
  let tree = {
    template: 'management',
    templateVersion: 1,
    nodes: [
      { slot: 'list', fields: ['a'] },
      {
        slot: 'form',
        fields: ['a'],
        relations: withRelations
          ? [
              { relation: 'child', title: '子表一', fields: ['x'] },
              { relation: 'other', title: '子表二', fields: ['z'] },
            ]
          : [],
        groups: [
          {
            group: 'basic',
            title: '基本分组',
            fields: [{ field: 'b', props: { label: '自定义乙', readOnly: true } }],
          },
          { group: 'empty', title: '空分组', fields: [] },
        ],
      },
    ],
  };
  const list = (records: unknown[]) => ({ records, pages: 1, totalKnown: true });
  return {
    request: async <T>(request: HttpRequestOptions) => {
      requests.push(request);
      const path = request.path;
      if (path.endsWith('/context')) return { capabilities: [], actions: [] } as T;
      if (path.endsWith('/metadata-relations/query'))
        return list([
          { id: 'main', metadataId: 'main', relationAlias: '主实体', relationRole: 'main' },
          ...(withRelations
            ? ['child', 'other'].map((id, index) => ({
                id,
                metadataId: id,
                parentMetadataId: 'main',
                relationAlias: id,
                title: ['子表一', '子表二'][index],
                relationRole: 'child',
              }))
            : []),
        ]) as T;
      if (path.endsWith('/fields/query'))
        return list(/\/(child|other)\//.test(path) ? children : fields) as T;
      if (path.endsWith('/pages/query'))
        return list([{ id: 'page', alias: 'management', title: '拖拽验收', mainRelationId: 'main' }]) as T;
      if (path.endsWith('/presentation-variants/query'))
        return list([{ id: 'variant', pageId: 'page' }]) as T;
      if (path.endsWith('/revisions/query'))
        return list(
          JSON.stringify(request.body).includes('published')
            ? []
            : [{ id: 'draft', revisionNo: 1, version: 0, uiTreeJson: JSON.stringify(tree) }],
        ) as T;
      if (path.endsWith('/update/draft')) {
        tree = JSON.parse((request.body as { uiTreeJson: string }).uiTreeJson);
        return { record: { id: 'draft', revisionNo: 1, version: 1, uiTreeJson: JSON.stringify(tree) } } as T;
      }
      if (path.endsWith('/preview')) {
        const draft = JSON.parse((request.body as { uiTreeJson: string }).uiTreeJson);
        const project = (entry: string | { field: string; props?: Record<string, unknown> }) => {
          const name = typeof entry === 'string' ? entry : entry.field;
          return {
            fieldRef: { fieldName: name },
            label:
              typeof entry === 'string'
                ? fields.find((field) => field.id === name)?.title
                : entry.props?.label,
            uiType: 'input',
            ...(typeof entry === 'string' ? {} : entry.props),
          };
        };
        const form = draft.nodes.find((node: { slot: string }) => node.slot === 'form');
        return {
          uiDescriptor: {
            schemaVersion: '1',
            detailRelations: (form.relations ?? []).map(
              (relation: { relation: string; title: string; fields: string[] }) => ({
                code: relation.relation,
                title: relation.title,
                listProjection: {
                  fields: relation.fields.map((fieldName) => ({ fieldName, title: `子字段${fieldName}` })),
                },
              }),
            ),
            moduleAlias: 'education.placement',
            page: {
              template: 'FLAT_MANAGEMENT',
              list: {
                fields: {
                  viewCode: 'list',
                  viewKind: 'LIST',
                  fields: draft.nodes
                    .find((node: { slot: string }) => node.slot === 'list')
                    .fields.map(project),
                },
              },
              detail: {
                createTitle: '新建',
                emptyDescription: '无',
                editor: {
                  viewCode: 'editor',
                  viewKind: 'FORM',
                  fields: [
                    ...form.fields,
                    ...(form.groups ?? []).flatMap((group: { fields: unknown[] }) => group.fields),
                  ].map(project),
                  formGroups: (form.groups ?? []).map(
                    (group: { group: string; title: string; fields: Array<string | { field: string }> }) => ({
                      groupCode: group.group,
                      title: group.title,
                      fields: group.fields.map((entry) => ({
                        fieldName: typeof entry === 'string' ? entry : entry.field,
                      })),
                    }),
                  ),
                },
              },
            },
          },
        } as T;
      }
      throw new Error(`Unexpected ${path}`);
    },
  };
}
