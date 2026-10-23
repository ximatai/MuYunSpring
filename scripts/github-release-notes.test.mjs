import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { extractReleaseNotes } from "./github-release-notes.mjs";

const changelog = `# 变更记录

## Unreleased

- 尚未发布

## 0.26.23 - 2026-10-23

### Added

- 公开读取入口。

## 0.26.22 - 2026-10-23

- 关联详情。
`;
const expected = "## 0.26.23 - 2026-10-23\n\n### Added\n\n- 公开读取入口。\n";

test("只提取指定正式版本，保留中文与 Markdown", () => {
  assert.equal(extractReleaseNotes(changelog, "v0.26.23"), expected);
  assert.equal(
    extractReleaseNotes(changelog.replaceAll("\n", "\r\n"), "v0.26.23"),
    expected,
  );
  assert.equal(
    extractReleaseNotes(changelog, "v0.26.22"),
    "## 0.26.22 - 2026-10-23\n\n- 关联详情。\n",
  );
});

test("拒绝非法 tag、缺失、重复或空版本说明", () => {
  for (const tag of ["0.26.23", "v0.26.23-SNAPSHOT", "v0.26.2", "v0.26.24"])
    assert.throws(() => extractReleaseNotes(changelog, tag));
  assert.throws(() =>
    extractReleaseNotes(`${changelog}\n${expected}`, "v0.26.23"),
  );
  assert.throws(() =>
    extractReleaseNotes("## 0.26.23 - 2026-10-23\n\n### Added\n", "v0.26.23"),
  );
});

test("代码块内的标题不截断正文，也不算重复版本", () => {
  for (const fence of ["```", "~~~~"]) {
    const notes = [
      "## 0.26.23 - 2026-10-23",
      "",
      "### Added",
      "",
      `${fence}markdown`,
      "## example",
      "## 0.26.23 - 2026-10-23",
      // A shorter marker cannot close the block.
      fence.slice(1),
      "## still inside",
      fence,
      "- 保留代码块后的说明。",
      "",
    ].join("\n");
    assert.equal(
      extractReleaseNotes(
        `${notes}## 0.26.22 - 2026-10-23\n\n- 旧版说明。\n`,
        "v0.26.23",
      ),
      notes,
    );
  }
});

test("CLI 从 stdin 读取说明，失败时不输出发布正文", () => {
  const script = fileURLToPath(
    new URL("./github-release-notes.mjs", import.meta.url),
  );
  assert.equal(
    execFileSync(process.execPath, [script, "v0.26.23"], {
      input: changelog,
      encoding: "utf8",
    }),
    expected,
  );
  const failed = spawnSync(process.execPath, [script, "v0.26.24"], {
    input: changelog,
    encoding: "utf8",
  });
  assert.notEqual(failed.status, 0);
  assert.equal(failed.stdout, "");
});
