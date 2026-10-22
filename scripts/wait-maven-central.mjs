import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { setTimeout } from "node:timers/promises";

/** Poll an existing deployment only. A retry must never create another release event. */
export async function waitForPublication({
  deploymentId,
  token,
  password,
  timeoutMs = 15 * 60 * 1000,
  fetchStatus = fetch,
  sleep = setTimeout,
  now = Date.now,
  log = console.log,
}) {
  if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(deploymentId))
    throw new Error("Missing or invalid Maven Central deployment ID");
  if (!token || !password)
    throw new Error(
      "Maven Central status check requires publishing credentials",
    );
  const authorization = `Bearer ${Buffer.from(`${token}:${password}`).toString("base64")}`;
  const deadline = now() + timeoutMs;
  let lastState = "unknown";
  while (now() < deadline) {
    let response;
    try {
      response = await fetchStatus(
        `https://central.sonatype.com/api/v1/publisher/status?id=${deploymentId}`,
        {
          method: "POST",
          headers: { Authorization: authorization },
          signal: AbortSignal.timeout(
            Math.max(1, Math.min(30000, deadline - now())),
          ),
        },
      );
    } catch {
      // A temporary read failure is safe to retry; uploading is not part of this client.
      log(
        "Maven Central status temporarily unavailable; retrying the same deployment",
      );
    }
    if (response) {
      if (response.status === 429 || response.status >= 500) {
        log(
          `Maven Central status returned HTTP ${response.status}; retrying the same deployment`,
        );
      } else {
        if (!response.ok)
          throw new Error(
            `Maven Central status rejected: HTTP ${response.status}`,
          );
        const status = await response.json();
        if (status.deploymentId !== deploymentId)
          throw new Error("Maven Central deployment identity mismatch");
        lastState = status.deploymentState;
        log(`Maven Central deployment ${deploymentId}: ${lastState}`);
        if (lastState === "PUBLISHED") return;
        if (
          !["PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"].includes(
            lastState,
          )
        )
          throw new Error(
            `Maven Central deployment did not publish: ${lastState}`,
          );
      }
    }
    await sleep(Math.max(0, Math.min(10000, deadline - now())));
  }
  throw new Error(
    `Timed out waiting for deployment ${deploymentId} (last state: ${lastState}). Check the Portal before retrying; do not re-upload.`,
  );
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  const deploymentId = readFileSync(process.argv[2], "utf8").trim();
  await waitForPublication({
    deploymentId,
    token: process.env.SONATYPE_TOKEN,
    password: process.env.SONATYPE_PASSWORD,
  });
}
