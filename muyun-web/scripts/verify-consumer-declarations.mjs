import { readdirSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const webRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const declarationsRoot =
  process.argv[2] ?? join(webRoot, '..', 'build', 'consumer-npm', 'staging', 'web-app', 'dist', 'types');
const privateAliases = /@muyun\//;
const violations = collectFiles(declarationsRoot)
  .filter((file) => file.endsWith('.d.ts'))
  .filter((file) => privateAliases.test(readFileSync(file, 'utf8')))
  .map((file) => relative(webRoot, file));

if (violations.length > 0) {
  console.error('Published consumer declarations must not reference private @muyun/* aliases:');
  for (const violation of violations) {
    console.error(`- ${violation}`);
  }
  process.exit(1);
}

console.log('Consumer declaration boundary check passed.');

function collectFiles(directory) {
  return readdirSync(directory).flatMap((name) => {
    const file = join(directory, name);
    return statSync(file).isDirectory() ? collectFiles(file) : [file];
  });
}
