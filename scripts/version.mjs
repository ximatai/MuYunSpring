#!/usr/bin/env node
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";
import {
  assertAlignedVersionState,
  currentYearDevelopmentVersion,
  nextDevelopmentVersion,
  readVersionState,
  releaseVersionForTag,
  writeAlignedVersionState,
} from "./version-lib.mjs";

const repositoryRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const [command, argument] = process.argv.slice(2);
const state = readVersionState(repositoryRoot);
const developmentVersion = assertAlignedVersionState(state);

switch (command) {
  case "check":
    console.log(`开发版本已对齐：${developmentVersion}`);
    break;
  case "verify-release": {
    if (!argument) throw new Error("verify-release 需要 vX.Y.Z Tag。");
    const releaseVersion = releaseVersionForTag(developmentVersion, argument);
    console.log(`发布版本校验通过：${releaseVersion}`);
    break;
  }
  case "advance": {
    if (!argument) throw new Error("advance 需要刚发布的 X.Y.Z 版本。");
    const expectedReleasedDevelopmentVersion = `${argument}-SNAPSHOT`;
    const nextVersion = nextDevelopmentVersion(argument);
    if (developmentVersion === nextVersion) {
      console.log(`开发版本已经推进：${nextVersion}`);
      break;
    }
    if (developmentVersion !== expectedReleasedDevelopmentVersion) {
      throw new Error(
        `拒绝覆盖开发版本 '${developmentVersion}'：期望刚发布的 '${expectedReleasedDevelopmentVersion}' 或已推进的 '${nextVersion}'。`,
      );
    }
    writeAlignedVersionState(state, nextVersion);
    console.log(`开发版本已推进：${developmentVersion} -> ${nextVersion}`);
    break;
  }
  case "ensure-current-year": {
    const nextVersion = currentYearDevelopmentVersion(developmentVersion);
    if (nextVersion === developmentVersion) {
      console.log(`开发版本年份已经是当前年份：${developmentVersion}`);
      break;
    }
    writeAlignedVersionState(state, nextVersion);
    console.log(`跨年开发版本已推进：${developmentVersion} -> ${nextVersion}`);
    break;
  }
  default:
    throw new Error(
      "用法：node scripts/version.mjs <check|verify-release|advance|ensure-current-year> [version]",
    );
}
