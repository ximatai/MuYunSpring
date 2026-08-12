import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const workbenchRoot = resolve(import.meta.dirname, '../../src/platform-workbench');

describe('workbench theme color contract', () => {
  it('keeps visual colors behind MuYun semantic tokens', () => {
    const sources = sourceFilesOf(workbenchRoot);
    const directHexColors = sources.flatMap((file) =>
      [...readFileSync(file, 'utf8').matchAll(/#[0-9a-f]{3,8}\b/gi)].map((match) => `${file}:${match[0]}`),
    );

    expect(directHexColors).toEqual([]);
  });
});

function sourceFilesOf(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      return sourceFilesOf(path);
    }
    return /\.(vue|ts|css)$/.test(entry.name) ? [path] : [];
  });
}
