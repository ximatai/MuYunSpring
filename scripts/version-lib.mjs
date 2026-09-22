import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const DEVELOPMENT_VERSION_PATTERN =
  /^(\d+)\.(\d{2})\.([1-9]\d*)-SNAPSHOT$/;
export const RELEASE_VERSION_PATTERN = /^(\d+)\.(\d{2})\.([1-9]\d*)$/;

export function currentYearNumber(now = new Date()) {
  return Number(
    new Intl.DateTimeFormat("en-US", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
    }).format(now),
  );
}

export function yearSegment(year = currentYearNumber()) {
  return String(year % 100).padStart(2, "0");
}

export function parseDevelopmentVersion(version) {
  const match = DEVELOPMENT_VERSION_PATTERN.exec(version);
  if (!match) {
    throw new Error(
      `开发版本 '${version}' 必须使用 X.Y.Z-SNAPSHOT 格式，其中 Y 是两位年份，Z 从 1 开始。`,
    );
  }
  return {
    major: match[1],
    year: match[2],
    sequence: Number(match[3]),
    version,
  };
}

export function parseReleaseVersion(version) {
  const match = RELEASE_VERSION_PATTERN.exec(version);
  if (!match) {
    throw new Error(
      `正式版本 '${version}' 必须使用 X.Y.Z 格式，其中 Y 是两位年份，Z 从 1 开始。`,
    );
  }
  return {
    major: match[1],
    year: match[2],
    sequence: Number(match[3]),
    version,
  };
}

export function readVersionState(repositoryRoot) {
  const gradlePath = join(repositoryRoot, "gradle.properties");
  const packagePath = join(repositoryRoot, "muyun-web", "package.json");
  const lockPath = join(repositoryRoot, "muyun-web", "package-lock.json");
  const gradleProperties = readFileSync(gradlePath, "utf8");
  const match = gradleProperties.match(/^muyunVersion=(.+)$/m);
  if (!match) throw new Error("gradle.properties 缺少 muyunVersion。");

  const packageJson = JSON.parse(readFileSync(packagePath, "utf8"));
  const packageLock = JSON.parse(readFileSync(lockPath, "utf8"));
  return {
    paths: { gradlePath, packagePath, lockPath },
    sources: { gradleProperties, packageJson, packageLock },
    gradle: match[1].trim(),
    package: packageJson.version,
    lock: packageLock.version,
    lockRoot: packageLock.packages?.[""]?.version,
  };
}

export function assertAlignedVersionState(state) {
  parseDevelopmentVersion(state.gradle);
  const versions = [state.gradle, state.package, state.lock, state.lockRoot];
  if (versions.some((version) => version !== state.gradle)) {
    throw new Error(
      `开发版本未对齐：gradle=${state.gradle}, package=${state.package}, lock=${state.lock}, lockRoot=${state.lockRoot}。`,
    );
  }
  return state.gradle;
}

export function releaseVersionForTag(
  developmentVersion,
  tag,
  year = currentYearNumber(),
) {
  const development = parseDevelopmentVersion(developmentVersion);
  const expectedYear = yearSegment(year);
  if (development.year !== expectedYear) {
    throw new Error(
      `开发版本年份 ${development.year} 与上海时区当前年份 ${expectedYear} 不一致；请先执行跨年版本推进。`,
    );
  }
  const expectedTag = `v${developmentVersion.replace(/-SNAPSHOT$/, "")}`;
  if (tag !== expectedTag) {
    throw new Error(
      `发布 Tag '${tag}' 与开发版本 '${developmentVersion}' 不一致，期望 '${expectedTag}'。`,
    );
  }
  return expectedTag.slice(1);
}

export function nextDevelopmentVersion(
  releasedVersion,
  year = currentYearNumber(),
) {
  const released = parseReleaseVersion(releasedVersion);
  const currentYear = yearSegment(year);
  const age = (Number(currentYear) - Number(released.year) + 100) % 100;
  if (age > 50) {
    throw new Error(
      `正式版本年份 ${released.year} 晚于上海时区当前年份 ${currentYear}。`,
    );
  }
  if (age > 0) return `${released.major}.${currentYear}.1-SNAPSHOT`;
  return `${released.major}.${released.year}.${released.sequence + 1}-SNAPSHOT`;
}

export function currentYearDevelopmentVersion(
  developmentVersion,
  year = currentYearNumber(),
) {
  const development = parseDevelopmentVersion(developmentVersion);
  const currentYear = yearSegment(year);
  const age = (Number(currentYear) - Number(development.year) + 100) % 100;
  if (age > 50) {
    throw new Error(
      `开发版本年份 ${development.year} 晚于上海时区当前年份 ${currentYear}。`,
    );
  }
  if (age === 0) return developmentVersion;
  return `${development.major}.${currentYear}.1-SNAPSHOT`;
}

export function writeAlignedVersionState(state, version) {
  parseDevelopmentVersion(version);
  const { paths, sources } = state;
  const gradleProperties = sources.gradleProperties.replace(
    /^muyunVersion=.+$/m,
    `muyunVersion=${version}`,
  );
  sources.packageJson.version = version;
  sources.packageLock.version = version;
  if (!sources.packageLock.packages?.[""]) {
    throw new Error('package-lock.json 缺少 packages[""].version。');
  }
  sources.packageLock.packages[""].version = version;
  writeFileSync(paths.gradlePath, gradleProperties);
  writeFileSync(
    paths.packagePath,
    `${JSON.stringify(sources.packageJson, null, 2)}\n`,
  );
  writeFileSync(
    paths.lockPath,
    `${JSON.stringify(sources.packageLock, null, 2)}\n`,
  );
}

export function consumerPackageVersion(developmentVersion, releaseVersion) {
  parseDevelopmentVersion(developmentVersion);
  if (!releaseVersion) return developmentVersion;
  const release = parseReleaseVersion(releaseVersion).version;
  const expected = developmentVersion.replace(/-SNAPSHOT$/, "");
  if (release !== expected) {
    throw new Error(
      `发布版本 '${release}' 与开发版本 '${developmentVersion}' 不一致，期望 '${expected}'。`,
    );
  }
  return release;
}
