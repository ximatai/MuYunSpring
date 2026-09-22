import test from "node:test";
import assert from "node:assert/strict";
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  assertAlignedVersionState,
  consumerPackageVersion,
  currentYearDevelopmentVersion,
  nextDevelopmentVersion,
  readVersionState,
  releaseVersionForTag,
  writeAlignedVersionState,
  yearSegment,
} from "./version-lib.mjs";

test("同一年发布后递增流水号", () => {
  assert.equal(nextDevelopmentVersion("0.26.14", 2026), "0.26.15-SNAPSHOT");
});

test("跨年后从新年份的 1 开始", () => {
  assert.equal(nextDevelopmentVersion("0.26.14", 2027), "0.27.1-SNAPSHOT");
  assert.equal(
    currentYearDevelopmentVersion("0.26.15-SNAPSHOT", 2027),
    "0.27.1-SNAPSHOT",
  );
  assert.equal(nextDevelopmentVersion("0.99.8", 2100), "0.00.1-SNAPSHOT");
  assert.throws(() => nextDevelopmentVersion("0.27.1", 2026));
});

test("发布 Tag 必须匹配开发版本和上海时区年份", () => {
  assert.equal(
    releaseVersionForTag("0.26.14-SNAPSHOT", "v0.26.14", 2026),
    "0.26.14",
  );
  assert.throws(() =>
    releaseVersionForTag("0.26.14-SNAPSHOT", "v0.26.15", 2026),
  );
  assert.throws(() =>
    releaseVersionForTag("0.26.14-SNAPSHOT", "v0.26.14", 2027),
  );
});

test("消费者包在开发态保留快照，发布态使用对应正式版本", () => {
  assert.equal(consumerPackageVersion("0.26.14-SNAPSHOT"), "0.26.14-SNAPSHOT");
  assert.equal(
    consumerPackageVersion("0.26.14-SNAPSHOT", "0.26.14"),
    "0.26.14",
  );
  assert.throws(() => consumerPackageVersion("0.26.14-SNAPSHOT", "0.26.15"));
});

test("年份段固定为两位", () => {
  assert.equal(yearSegment(2026), "26");
  assert.equal(yearSegment(2101), "01");
});

test("版本写入会同时对齐三份声明", () => {
  const root = mkdtempSync(join(tmpdir(), "muyun-version-"));
  try {
    mkdirSync(join(root, "muyun-web"));
    writeFileSync(
      join(root, "gradle.properties"),
      "key=value\nmuyunVersion=0.26.14-SNAPSHOT\n",
    );
    writeFileSync(
      join(root, "muyun-web", "package.json"),
      '{\n  "name": "fixture",\n  "version": "0.26.14-SNAPSHOT"\n}\n',
    );
    writeFileSync(
      join(root, "muyun-web", "package-lock.json"),
      '{\n  "name": "fixture",\n  "version": "0.26.14-SNAPSHOT",\n  "packages": {\n    "": {\n      "version": "0.26.14-SNAPSHOT"\n    }\n  }\n}\n',
    );

    const state = readVersionState(root);
    writeAlignedVersionState(state, "0.26.15-SNAPSHOT");
    assert.equal(
      assertAlignedVersionState(readVersionState(root)),
      "0.26.15-SNAPSHOT",
    );
    assert.match(
      readFileSync(join(root, "gradle.properties"), "utf8"),
      /^key=value$/m,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
