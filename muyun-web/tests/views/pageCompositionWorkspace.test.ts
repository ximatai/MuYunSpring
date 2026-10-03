import { describe, expect, it, vi } from 'vitest';
import { createPageCompositionWorkspace } from '@/views/pageCompositionWorkspace';

describe('page composition module selection input', () => {
  it('keeps the wire schema portable and validates module paths before accessing a session', () => {
    const request = vi.fn();
    const workspace = createPageCompositionWorkspace(
      { request },
      () => 'operator',
      () => true,
    );
    const select = workspace
      .capabilities()
      .find((item) => item.descriptor.code === 'configuration.select-page-module')!;
    const schema = JSON.parse(JSON.stringify(select.descriptor.inputSchema));
    // Repeated-group patterns caused the real compatible provider to return empty length/zero-usage responses.
    expect(schema).toMatchObject({
      type: 'object',
      additionalProperties: false,
      required: ['moduleAlias'],
      properties: { moduleAlias: { type: 'string', maxLength: 128 } },
    });
    expect(schema.properties.moduleAlias).not.toHaveProperty('pattern');
    for (const moduleAlias of ['catalog.product', 'service.ticket.note'])
      expect(select.parseInput({ moduleAlias })).toBe(moduleAlias);
    for (const moduleAlias of [
      '',
      'catalog',
      'catalog..product',
      'catalog.product.',
      'catalog/product',
      'catalog.1product',
      'catalog.product\\child',
      'a.' + 'b'.repeat(127),
    ])
      expect(() => select.parseInput({ moduleAlias })).toThrow('请提供真实模块标识');
    expect(() => select.parseInput({ moduleAlias: 'catalog.product', path: '/other' })).toThrow(
      '请提供真实模块标识',
    );
    expect(request).not.toHaveBeenCalled();
    workspace.dispose();
  });
});
