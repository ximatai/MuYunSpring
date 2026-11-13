import assert from "node:assert/strict";
import test from "node:test";
import { findReusableBackendCi } from "./reuse-backend-ci.mjs";

const repository = "ximatai/MuYunSpring";
const sha = "a".repeat(40);
const success = { status: "completed", conclusion: "success" };

function fixture() {
  const run = {
    ...success,
    id: 42,
    run_attempt: 2,
    event: "push",
    head_branch: "main",
    head_sha: sha,
    head_repository: { full_name: repository },
  };
  const step = { ...success, name: "Run tests" };
  const job = {
    ...success,
    run_id: run.id,
    head_sha: sha,
    name: "Backend Build & Test (Java 21)",
    steps: [step],
  };
  const jobs = { total_count: 1, jobs: [job] };
  const runs = { workflow_runs: [run] };
  const requests = [];
  const lookup = () => findReusableBackendCi(async (path) => {
    requests.push(path);
    return requests.length === 1 ? runs : jobs;
  }, repository, sha);
  return { run, job, step, jobs, runs, requests, lookup };
}

test("reuses the backend step from the exact successful main CI attempt", async () => {
  const { run, requests, lookup } = fixture();
  assert.equal(await lookup(), run);
  assert.deepEqual(requests, [
    `repos/${repository}/actions/workflows/ci.yml/runs?branch=main&event=push&head_sha=${sha}&per_page=1`,
    `repos/${repository}/actions/runs/42/attempts/2/jobs?per_page=100`,
  ]);
});

for (const [name, change] of [
  ["no matching run", ({ runs }) => { runs.workflow_runs = []; }],
  ["run still in progress", ({ run }) => { run.status = "in_progress"; }],
  ["failed run", ({ run }) => { run.conclusion = "failure"; }],
  ["different commit", ({ run }) => { run.head_sha = "b".repeat(40); }],
  ["PR event", ({ run }) => { run.event = "pull_request"; }],
  ["different branch", ({ run }) => { run.head_branch = "feature"; }],
  ["different repository", ({ run }) => { run.head_repository.full_name = "other/MuYunSpring"; }],
  ["missing attempt", ({ run }) => { delete run.run_attempt; }],
  ["rerun without a backend job", ({ jobs }) => { jobs.jobs = []; jobs.total_count = 0; }],
  ["incomplete job list", ({ jobs }) => { jobs.total_count = 2; }],
  ["backend job from another run", ({ job }) => { job.run_id = 43; }],
  ["failed backend job", ({ job }) => { job.conclusion = "failure"; }],
  ["backend tests skipped", ({ step }) => { step.conclusion = "skipped"; }],
  ["backend tests missing", ({ job }) => { job.steps = []; }],
]) {
  test(`falls back with ${name}`, async () => {
    const state = fixture();
    change(state);
    assert.equal(await state.lookup(), null);
  });
}

test("falls back when the GitHub API is unavailable", async () => {
  const result = await findReusableBackendCi(async () => {
    throw new Error("GitHub API unavailable");
  }, repository, sha);
  assert.equal(result, null);
});
