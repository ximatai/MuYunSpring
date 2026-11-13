import { appendFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const passed = (item) => item?.status === "completed" && item.conclusion === "success";

export async function findReusableBackendCi(getJson, repository, sha) {
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository) || !/^[a-f0-9]{40}$/.test(sha)) {
    return null;
  }

  try {
    const repo = `repos/${repository}`;
    const runs = await getJson(
      `${repo}/actions/workflows/ci.yml/runs?branch=main&event=push&head_sha=${sha}&per_page=1`,
    );
    const run = runs.workflow_runs?.[0];
    if (
      !passed(run) ||
      run.event !== "push" ||
      run.head_branch !== "main" ||
      run.head_sha !== sha ||
      run.head_repository?.full_name !== repository ||
      !Number.isSafeInteger(run.id) || run.id < 1 ||
      !Number.isSafeInteger(run.run_attempt) || run.run_attempt < 1
    ) {
      return null;
    }

    const result = await getJson(
      `${repo}/actions/runs/${run.id}/attempts/${run.run_attempt}/jobs?per_page=100`,
    );
    if (!Array.isArray(result.jobs) || result.total_count !== result.jobs.length) {
      return null;
    }
    const jobs = result.jobs.filter((job) => job?.name === "Backend Build & Test (Java 21)");
    const [backend] = jobs;
    if (
      jobs.length !== 1 ||
      !passed(backend) ||
      backend.run_id !== run.id ||
      backend.head_sha !== sha ||
      !Array.isArray(backend.steps)
    ) {
      return null;
    }
    const tests = backend.steps.filter((step) => step?.name === "Run tests");
    return tests.length === 1 && passed(tests[0]) ? run : null;
  } catch {
    return null;
  }
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const { GITHUB_API_URL, GITHUB_REPOSITORY, GITHUB_OUTPUT, GH_TOKEN } = process.env;
  const signal = AbortSignal.timeout(8000);
  const getJson = async (path) => {
    const response = await fetch(`${GITHUB_API_URL}/${path}`, {
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${GH_TOKEN}`,
      },
      signal,
    });
    if (!response.ok) throw new Error(`GitHub API returned HTTP ${response.status}`);
    return response.json();
  };
  const run = await findReusableBackendCi(getJson, GITHUB_REPOSITORY, process.argv[2]);
  if (run) {
    appendFileSync(GITHUB_OUTPUT, "reused=true\n");
    console.log(`Reusing backend verifyAll from main CI run ${run.id}, attempt ${run.run_attempt}.`);
  } else {
    console.log("No matching successful main CI backend verification. Running verifyAll for this tag.");
  }
}
