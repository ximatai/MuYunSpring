import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { planRecovery, publishVerifiedNpm } from "./recover-release.mjs";

function fixture() {
  const repository = "ximatai/MuYunSpring";
  const run = {
    id: 42,
    status: "completed",
    conclusion: "failure",
    event: "push",
    path: ".github/workflows/release.yml",
    head_branch: "v0.26.34",
    head_sha: "a".repeat(40),
    run_attempt: 1,
    head_repository: { full_name: repository },
  };
  const commit = { sha: run.head_sha };
  const jobs = {
    total_count: 4,
    jobs: [
      ["Verify backend and Maven consumer", "success"],
      ["Verify npm package consumer", "success"],
      ["Publish to Maven Central", "failure"],
      ["Publish to npm", "skipped"],
    ].map(([name, conclusion]) => ({
      name,
      conclusion,
      status: "completed",
      run_id: run.id,
      head_sha: run.head_sha,
    })),
  };
  const artifacts = {
    total_count: 2,
    artifacts: ["maven-publication", "npm-package"].map((name, index) => ({
      id: 100 + index,
      name: `${name}-v0.26.34-attempt-1`,
      expired: false,
      workflow_run: { id: run.id, head_sha: run.head_sha },
    })),
  };
  const calls = [];
  const values = [run, commit, jobs, artifacts];
  return {
    run,
    commit,
    jobs,
    artifacts,
    calls,
    lookup: () =>
      planRecovery(
        async (path) => {
          calls.push(path);
          return values[calls.length - 1];
        },
        repository,
        42,
      ),
  };
}

test("plans only artifacts from the verified tag and original failed attempt", async () => {
  const { lookup, calls } = fixture();
  assert.deepEqual(await lookup(), {
    "release-tag": "v0.26.34",
    "maven-artifact-id": "100",
    "npm-artifact-id": "101",
  });
  assert.equal(calls.length, 4);
  assert.match(calls[2], /runs\/42\/attempts\/1\/jobs/);
});

for (const [name, change] of [
  [
    "another repository",
    ({ run }) => {
      run.head_repository.full_name = "other/repo";
    },
  ],
  [
    "another workflow",
    ({ run }) => {
      run.path = ".github/workflows/ci.yml";
    },
  ],
  [
    "a PR run",
    ({ run }) => {
      run.event = "pull_request";
    },
  ],
  [
    "a running release",
    ({ run }) => {
      run.status = "in_progress";
    },
  ],
  [
    "a successful release",
    ({ run }) => {
      run.conclusion = "success";
    },
  ],
  [
    "a non-release branch",
    ({ run }) => {
      run.head_branch = "main";
    },
  ],
  [
    "a moved tag",
    ({ commit }) => {
      commit.sha = "b".repeat(40);
    },
  ],
  [
    "failed verification",
    ({ jobs }) => {
      jobs.jobs[0].conclusion = "failure";
    },
  ],
  [
    "duplicate jobs",
    ({ jobs }) => {
      jobs.jobs.push(jobs.jobs[0]);
      jobs.total_count++;
    },
  ],
  [
    "truncated jobs",
    ({ jobs }) => {
      jobs.total_count++;
    },
  ],
  [
    "another job attempt",
    ({ jobs }) => {
      jobs.jobs[0].head_sha = "b".repeat(40);
    },
  ],
  [
    "an attempted npm write",
    ({ jobs }) => {
      jobs.jobs[3].conclusion = "failure";
    },
  ],
  [
    "missing deployment receipt",
    ({ artifacts }) => {
      artifacts.artifacts.shift();
      artifacts.total_count--;
    },
  ],
  [
    "expired artifact",
    ({ artifacts }) => {
      artifacts.artifacts[0].expired = true;
    },
  ],
  [
    "another artifact commit",
    ({ artifacts }) => {
      artifacts.artifacts[1].workflow_run.head_sha = "b".repeat(40);
    },
  ],
  [
    "another artifact attempt",
    ({ artifacts }) => {
      artifacts.artifacts[1].name = "npm-package-v0.26.34-attempt-2";
    },
  ],
]) {
  test(`refuses recovery with ${name}`, async () => {
    const state = fixture();
    change(state);
    await assert.rejects(state.lookup());
  });
}

test("rejects invalid source identifiers before any API call", async () => {
  await assert.rejects(
    planRecovery(() => assert.fail("unexpected API call"), "owner/repo", NaN),
  );
  await assert.rejects(
    planRecovery(() => assert.fail("unexpected API call"), "../repo", 42),
  );
});

test("publishes once after an explicit 404 and propagates ambiguous writes without retry", async () => {
  const calls = [];
  const options = {
    tag: "v0.26.34",
    tarball: "verified.tgz",
    fetchRequest: async () => ({ ok: false, status: 404 }),
    publish: async (path) => {
      calls.push(path);
    },
  };
  assert.equal(await publishVerifiedNpm(options), "published");
  assert.deepEqual(calls, ["verified.tgz"]);
  options.publish = async () => {
    throw new Error("receipt lost");
  };
  await assert.rejects(publishVerifiedNpm(options), /receipt lost/);
});

test("reconciles the exact existing tarball and rejects different content or lookup failures", async () => {
  const directory = mkdtempSync(join(tmpdir(), "muyun-release-"));
  const tarball = join(directory, "verified.tgz");
  const bytes = Buffer.from("verified package bytes");
  writeFileSync(tarball, bytes);
  try {
    const existing = {
      name: "@ximatai/muyun-web-app",
      version: "0.26.34",
      dist: {
        integrity: `sha512-${createHash("sha512").update(bytes).digest("base64")}`,
      },
    };
    const options = {
      tag: "v0.26.34",
      tarball,
      fetchRequest: async () => ({ ok: true, json: async () => existing }),
      publish: () => assert.fail("must not publish again"),
    };
    assert.equal(await publishVerifiedNpm(options), "already-published");
    existing.dist.integrity = "sha512-other";
    await assert.rejects(publishVerifiedNpm(options), /differs/);
    options.fetchRequest = async () => ({ ok: false, status: 503 });
    await assert.rejects(publishVerifiedNpm(options), /HTTP 503/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
