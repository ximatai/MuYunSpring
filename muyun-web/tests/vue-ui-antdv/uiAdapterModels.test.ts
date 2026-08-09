import { assert, it } from 'vitest';
import { resolveUiDataTableScroll } from '@/vue-ui-antdv/dataTableModel.ts';

it('data table enables horizontal scrolling independently from fixed columns', () => {
  assert.deepEqual(resolveUiDataTableScroll({ horizontal: true, fillHeight: false, hasFixedColumn: false }), {
    x: 'max-content',
  });
  assert.deepEqual(resolveUiDataTableScroll({ horizontal: false, fillHeight: false, hasFixedColumn: true }), {
    x: 'max-content',
  });
  assert.equal(
    resolveUiDataTableScroll({ horizontal: false, fillHeight: false, hasFixedColumn: false }),
    undefined,
  );
});

it('data table combines height filling with horizontal scrolling', () => {
  assert.deepEqual(resolveUiDataTableScroll({ horizontal: true, fillHeight: true, hasFixedColumn: false }), {
    x: 'max-content',
    y: '100%',
  });
});
