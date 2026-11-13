import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { appendFileSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const passed = (item) =>
  item?.status === "completed" && item.conclusion === "success";
const positiveId = (value) => Number.isSafeInteger(value) && value > 0;

/** Recover a verified tag whose Maven publication failed before npm was attempted. */
export async function planRecovery(getJson, repository, runId) {
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository) || !positiveId(runId))
    throw new Error("Invalid repository or source run ID");
  const prefix = `repos/${repository}`;
  const run = await getJson(`${prefix}/actions/runs/${runId}`);
  if (
    run.id !== runId ||
    run.status !== "completed" ||
    run.conclusion !== "failure" ||
    run.event !== "push" ||
    run.path !== ".github/workflows/release.yml" ||
    run.head_repository?.full_name !== repository ||
    !/^v\d+\.\d+\.\d+$/.test(run.head_branch) ||
    !/^[a-f0-9]{40}$/.test(run.head_sha) ||
    !positiveId(run.run_attempt)
  )
    throw new Error(
      "Source must be a completed failed release of this repository",
    );
  const tag = run.head_branch;
  const commit = await getJson(`${prefix}/commits/${tag}`);
  if (commit.sha !== run.head_sha)
    throw new Error("Release tag no longer matches source run");
  const result = await getJson(
    `${prefix}/actions/runs/${runId}/attempts/${run.run_attempt}/jobs?per_page=100`,
  );
  if (!Array.isArray(result.jobs) || result.total_count !== result.jobs.length)
    throw new Error("Incomplete source job list");
  const job = (name) => {
    const matches = result.jobs.filter((item) => item.name === name);
    if (
      matches.length !== 1 ||
      matches[0].run_id !== runId ||
      matches[0].head_sha !== run.head_sha
    )
      throw new Error(`Missing or ambiguous source job: ${name}`);
    return matches[0];
  };
  if (
    !passed(job("Verify backend and Maven consumer")) ||
    !passed(job("Verify npm package consumer"))
  )
    throw new Error("Source release verification did not pass");
  const maven = job("Publish to Maven Central");
  const npm = job("Publish to npm");
  if (
    maven.status !== "completed" ||
    maven.conclusion !== "failure" ||
    npm.conclusion !== "skipped"
  )
    throw new Error(
      "Recovery requires a failed Maven job and an unattempted npm job",
    );
  const artifacts = await getJson(
    `${prefix}/actions/runs/${runId}/artifacts?per_page=100`,
  );
  if (
    !Array.isArray(artifacts.artifacts) ||
    artifacts.total_count !== artifacts.artifacts.length
  )
    throw new Error("Incomplete source artifact list");
  const artifact = (name) => {
    const matches = artifacts.artifacts.filter((item) => item.name === name);
    if (
      matches.length !== 1 ||
      matches[0].expired ||
      !positiveId(matches[0].id) ||
      matches[0].workflow_run?.id !== runId ||
      matches[0].workflow_run?.head_sha !== run.head_sha
    )
      throw new Error(`Missing, expired or mismatched artifact: ${name}`);
    return String(matches[0].id);
  };
  return {
    "release-tag": tag,
    "maven-artifact-id": artifact(
      `maven-publication-${tag}-attempt-${run.run_attempt}`,
    ),
    "npm-artifact-id": artifact(
      `npm-package-${tag}-attempt-${run.run_attempt}`,
    ),
  };
}

/** Reconcile an existing receipt before any npm publish; never retry a failed write. */
export async function publishVerifiedNpm({
  tag,
  tarball,
  fetchRequest = fetch,
  publish,
}) {
  if (!/^v\d+\.\d+\.\d+$/.test(tag)) throw new Error("Invalid release tag");
  const version = tag.slice(1);
  const response = await fetchRequest(
    `https://registry.npmjs.org/@ximatai%2Fmuyun-web-app/${version}`,
    {
      signal: AbortSignal.timeout(30000),
    },
  );
  if (response.ok) {
    const existing = await response.json();
    const integrity = `sha512-${createHash("sha512").update(readFileSync(tarball)).digest("base64")}`;
    if (
      existing.name !== "@ximatai/muyun-web-app" ||
      existing.version !== version ||
      existing.dist?.integrity !== integrity
    )
      throw new Error("Existing npm version differs from the verified tarball");
    return "already-published";
  }
  if (response.status !== 404)
    throw new Error(`npm lookup rejected: HTTP ${response.status}`);
  await publish(tarball);
  return "published";
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  if (process.argv[2] === "plan") {
    const getJson = async (path) =>
      JSON.parse(execFileSync("gh", ["api", path], { encoding: "utf8" }));
    const plan = await planRecovery(
      getJson,
      process.env.GITHUB_REPOSITORY,
      Number(process.argv[3]),
    );
    appendFileSync(
      process.env.GITHUB_OUTPUT,
      Object.entries(plan)
        .map(([key, value]) => `${key}=${value}\n`)
        .join(""),
    );
    console.log(`Recovering verified release ${plan["release-tag"]}`);
  } else if (process.argv[2] === "publish-npm") {
    const result = await publishVerifiedNpm({
      tag: process.env.RELEASE_TAG,
      tarball: process.argv[3],
      publish: (tarball) =>
        execFileSync(
          "npm",
          [
            "publish",
            tarball,
            "--access",
            "public",
            "--registry=https://registry.npmjs.org/",
          ],
          { stdio: "inherit" },
        ),
    });
    console.log(`npm recovery: ${result}`);
  } else throw new Error("Expected plan or publish-npm");
}
