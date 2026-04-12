#!/usr/bin/env node

import { createWriteStream, existsSync, mkdirSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { pipeline } from 'node:stream/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * Resolve the cache directory, respecting XDG_CACHE_HOME.
 *
 * @returns {string} Absolute path to the cache directory for this package.
 */
function resolveCacheDir() {
  const xdgCache = process.env.XDG_CACHE_HOME;
  const base = xdgCache ?? join(homedir(), '.cache');
  return join(base, 'jvm-heap-dump-mcp');
}

/**
 * Read the package version from this package's package.json.
 *
 * @returns {Promise<string>} The version string, e.g. "0.1.0".
 */
async function readVersion() {
  const pkgPath = join(__dirname, 'package.json');
  const raw = await readFile(pkgPath, 'utf8');
  const pkg = JSON.parse(raw);
  return pkg.version;
}

/**
 * Build the GitHub Releases download URL for the shadow JAR.
 *
 * @param {string} version - Package version, e.g. "0.1.0".
 * @returns {string} Full HTTPS URL to the JAR asset.
 */
function jarUrl(version) {
  return `https://github.com/Djaler/jvm-heap-dump-mcp/releases/download/v${version}/jvm-heap-dump-mcp-${version}-all.jar`;
}

/**
 * Download a URL to a local file path, printing progress to stderr.
 *
 * @param {string} url - Remote URL to download.
 * @param {string} dest - Destination file path.
 * @returns {Promise<void>}
 */
async function downloadFile(url, dest) {
  const response = await fetch(url, { redirect: 'follow' });

  if (!response.ok) {
    throw new Error(`Download failed: HTTP ${response.status} ${response.statusText} — ${url}`);
  }

  const contentLength = response.headers.get('content-length');
  const total = contentLength ? parseInt(contentLength, 10) : null;
  let downloaded = 0;
  let lastReported = -1;

  const reportProgress = (bytes) => {
    downloaded += bytes;
    if (total) {
      const pct = Math.floor((downloaded / total) * 100);
      if (pct !== lastReported && pct % 5 === 0) {
        lastReported = pct;
        process.stderr.write(`\r  Downloading... ${pct}% (${(downloaded / 1024 / 1024).toFixed(1)} MB / ${(total / 1024 / 1024).toFixed(1)} MB)`);
      }
    } else {
      const mb = (downloaded / 1024 / 1024).toFixed(1);
      if (Math.floor(downloaded / (512 * 1024)) !== lastReported) {
        lastReported = Math.floor(downloaded / (512 * 1024));
        process.stderr.write(`\r  Downloading... ${mb} MB`);
      }
    }
  };

  const out = createWriteStream(dest);

  // Wrap the response body to intercept chunk sizes for progress reporting.
  const { Readable } = await import('node:stream');
  const readable = Readable.fromWeb
    ? Readable.fromWeb(response.body)
    : response.body;

  const trackingStream = new (await import('node:stream')).Transform({
    transform(chunk, _encoding, callback) {
      reportProgress(chunk.length);
      callback(null, chunk);
    },
  });

  await pipeline(readable, trackingStream, out);
  process.stderr.write('\n');
}

/**
 * Ensure the JAR for the given version is present in the cache dir.
 * Downloads it if missing.
 *
 * @param {string} version - Package version.
 * @returns {Promise<string>} Absolute path to the cached JAR file.
 */
async function ensureJar(version) {
  const cacheDir = resolveCacheDir();
  mkdirSync(cacheDir, { recursive: true });

  const jarName = `jvm-heap-dump-mcp-${version}-all.jar`;
  const jarPath = join(cacheDir, jarName);

  if (existsSync(jarPath)) {
    return jarPath;
  }

  const url = jarUrl(version);
  process.stderr.write(`jvm-heap-dump-mcp: JAR not found in cache.\n`);
  process.stderr.write(`  Version : ${version}\n`);
  process.stderr.write(`  Cache   : ${cacheDir}\n`);
  process.stderr.write(`  Source  : ${url}\n`);

  try {
    await downloadFile(url, jarPath);
  } catch (err) {
    // Remove incomplete file on failure.
    try {
      const { unlinkSync } = await import('node:fs');
      unlinkSync(jarPath);
    } catch {
      // Ignore cleanup errors.
    }
    process.stderr.write(`\njvm-heap-dump-mcp: Download error — ${err.message}\n`);
    process.exit(1);
  }

  process.stderr.write(`jvm-heap-dump-mcp: JAR downloaded successfully.\n`);
  return jarPath;
}

/**
 * Verify that `java` is available on PATH and is version >= 21.
 * Exits with a descriptive error if either check fails.
 *
 * @returns {Promise<void>}
 */
async function checkJava() {
  const { execFile } = await import('node:child_process');
  const { promisify } = await import('node:util');
  const execFileAsync = promisify(execFile);

  let output;
  try {
    // java -version writes to stderr.
    const result = await execFileAsync('java', ['-version'], { encoding: 'utf8' });
    output = result.stderr || result.stdout;
  } catch (err) {
    if (err.code === 'ENOENT') {
      process.stderr.write('jvm-heap-dump-mcp: "java" not found in PATH.\n');
      process.stderr.write('  Please install JDK 21 or later: https://adoptium.net\n');
      process.exit(1);
    } else {
      output = (err.stderr ?? '') + (err.stdout ?? '');
      if (!output) {
        process.stderr.write(`jvm-heap-dump-mcp: Failed to run java -version: ${err.message}\n`);
        process.exit(1);
      }
    }
  }

  if (!output) return;

  // Output format examples:
  //   openjdk version "21.0.3" 2024-04-16
  //   java version "1.8.0_391"
  const match = output.match(/version\s+"(\d+)(?:\.(\d+))?/);
  if (!match) {
    process.stderr.write(`jvm-heap-dump-mcp: Could not parse java version from: ${output.trim()}\n`);
    process.exit(1);
  }

  const major = parseInt(match[1], 10);
  // Legacy versioning: "1.8" means Java 8.
  const effectiveMajor = major === 1 ? parseInt(match[2] ?? '0', 10) : major;

  if (effectiveMajor < 21) {
    process.stderr.write(`jvm-heap-dump-mcp: Java 21+ is required, but found Java ${effectiveMajor}.\n`);
    process.stderr.write('  Please upgrade: https://adoptium.net\n');
    process.exit(1);
  }
}

/**
 * Spawn the MCP server JAR, passing through all stdio.
 * Forwards SIGINT and SIGTERM to the child process.
 * Exits with the child's exit code.
 *
 * @param {string} jarPath - Absolute path to the shadow JAR.
 * @returns {Promise<void>}
 */
async function runServer(jarPath) {
  const javaOpts = process.env.JAVA_OPTS ? process.env.JAVA_OPTS.split(/\s+/) : ['-Xmx4g'];
  const child = spawn('java', [...javaOpts, '-jar', jarPath], {
    stdio: 'inherit',
  });

  const forward = (signal) => {
    if (!child.killed) {
      child.kill(signal);
    }
  };

  process.on('SIGINT', () => forward('SIGINT'));
  process.on('SIGTERM', () => forward('SIGTERM'));

  await new Promise((resolve) => {
    child.on('exit', (code, signal) => {
      if (signal) {
        // Replicate signal-based exit.
        process.kill(process.pid, signal);
      } else {
        process.exitCode = code ?? 0;
      }
      resolve();
    });
  });
}

async function main() {
  const version = await readVersion();
  await checkJava();
  const jarPath = await ensureJar(version);
  await runServer(jarPath);
}

main().catch((err) => {
  process.stderr.write(`jvm-heap-dump-mcp: Unexpected error — ${err.message}\n`);
  process.exit(1);
});
