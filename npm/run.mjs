#!/usr/bin/env node

import { readdirSync } from 'node:fs';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const execFileAsync = promisify(execFile);

/**
 * Locate the bundled shadow JAR inside the npm package (npm/bin/).
 *
 * @returns {string} Absolute path to the JAR file.
 */
function resolveBundledJar() {
  const libDir = join(__dirname, 'lib');
  let entries;
  try {
    entries = readdirSync(libDir);
  } catch (err) {
    process.stderr.write(`jvm-heap-dump-mcp: bundled JAR directory not found at ${libDir}.\n`);
    process.stderr.write('  This is a packaging bug — please reinstall or report at https://github.com/Djaler/jvm-heap-dump-mcp/issues\n');
    process.exit(1);
  }
  const jar = entries.find((f) => f.startsWith('jvm-heap-dump-mcp-') && f.endsWith('-all.jar'));
  if (!jar) {
    process.stderr.write(`jvm-heap-dump-mcp: no JAR file found in ${libDir}.\n`);
    process.stderr.write(`  Contents: ${entries.join(', ')}\n`);
    process.exit(1);
  }
  return join(libDir, jar);
}

/**
 * Verify that `java` is available on PATH and is version >= 21.
 * Exits with a descriptive error if either check fails.
 *
 * @returns {Promise<void>}
 */
async function checkJava() {
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
    }
    output = (err.stderr ?? '') + (err.stdout ?? '');
    if (!output) {
      process.stderr.write(`jvm-heap-dump-mcp: Failed to run java -version: ${err.message}\n`);
      process.exit(1);
    }
  }

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
  await checkJava();
  const jarPath = resolveBundledJar();
  await runServer(jarPath);
}

main().catch((err) => {
  process.stderr.write(`jvm-heap-dump-mcp: Unexpected error — ${err.message}\n`);
  process.exit(1);
});
