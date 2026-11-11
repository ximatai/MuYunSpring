import {
  prepareDependencies,
  preparePackage,
  withPreparationLock,
  withStableInputs,
} from './consumer-preparation.mjs';
import { stage } from './verification-stage.mjs';

const options = { fresh: process.argv.includes('--fresh') };
await withPreparationLock(() =>
  stage('consumer preparation', () =>
    withStableInputs(async () => {
      await preparePackage(options);
      await prepareDependencies(options);
    }),
  ),
);
