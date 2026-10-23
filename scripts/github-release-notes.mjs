import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** Release notes come from the tagged changelog, never from generated commit summaries. */
export function extractReleaseNotes(changelog, tag) {
  const version = /^v(\d+\.\d+\.\d+)$/.exec(tag)?.[1];
  if (!version) throw new Error("Expected a vX.Y.Z release tag");
  const heading = new RegExp(
    `^## ${version.replaceAll(".", "\\.")} - \\d{4}-\\d{2}-\\d{2}(?:\\n|$)`,
  );
  const sections = [];
  let lines = [];
  let fence;
  for (const line of changelog.replaceAll("\r\n", "\n").split("\n")) {
    if (!fence && line.startsWith("## ")) {
      sections.push(lines.join("\n"));
      lines = [];
    }
    lines.push(line);
    const marker = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
    if (!marker) continue;
    if (!fence) fence = marker[1];
    else if (
      marker[1][0] === fence[0] &&
      marker[1].length >= fence.length &&
      !marker[2].trim()
    )
      fence = undefined;
  }
  sections.push(lines.join("\n"));
  const matches = sections.filter((section) => heading.test(section));
  if (matches.length !== 1)
    throw new Error(`Expected exactly one changelog section for ${tag}`);
  const notes = matches[0].trim();
  if (!notes.replace(/^#{1,6} .*$/gm, "").trim())
    throw new Error(`Empty release notes for ${tag}`);
  return `${notes}\n`;
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  process.stdout.write(
    extractReleaseNotes(readFileSync(0, "utf8"), process.argv[2]),
  );
}
