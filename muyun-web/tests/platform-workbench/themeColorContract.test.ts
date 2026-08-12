import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const themedRoots = ['platform-workbench', 'dynamic-page-runtime', 'platform-components', 'views'].map(
  (directory) => resolve(import.meta.dirname, `../../src/${directory}`),
);

describe('application theme color contract', () => {
  it('keeps application visual colors behind MuYun semantic tokens', () => {
    const sources = themedRoots.flatMap(sourceFilesOf);
    const directHexColors = sources.flatMap((file) =>
      [...styleSourceOf(file).matchAll(/#[0-9a-f]{3,8}\b/gi)].map((match) => `${file}:${match[0]}`),
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

function styleSourceOf(file: string): string {
  const source = readFileSync(file, 'utf8');
  if (!file.endsWith('.vue')) return source;
  return [...source.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((match) => match[1]).join('\n');
}
