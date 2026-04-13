#!/usr/bin/env node

// jvm-heap-dump-mcp npm wrapper.
//
// Two modes of operation:
//
//  1. Fast path (warm cache) — JAR already in ~/.cache. Spawn Java immediately
//     and pipe stdio, effectively acting as a transparent launcher.
//
//  2. Pending mode (cold start / new version) — answer the MCP handshake
//     ourselves with an empty tool list while the JAR downloads in the
//     background. Once the JAR is ready, spawn Java, replay the client's
//     initialize, swallow Java's response, then send notifications/tools/
//     list_changed so the client re-fetches the real tool list. From that
//     point on we are a transparent bidirectional proxy.
//
// This ensures the MCP client sees a successful `initialize` within
// milliseconds regardless of network speed — no more "Failed to reconnect"
// timeouts on first install / version upgrade.

import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createWriteStream, existsSync, mkdirSync, renameSync, statSync, unlinkSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pipeline } from 'node:stream/promises';
import { Readable, Transform } from 'node:stream';

const __dirname = dirname(fileURLToPath(import.meta.url));
const execFileAsync = promisify(execFile);

const PENDING_TOOL_CALL_ERROR =
  'jvm-heap-dump-mcp is still initializing (downloading ~28 MB JAR). Please retry in a few seconds.';

/**
 * Read the package version from this package's package.json.
 *
 * @returns {Promise<string>}
 */
async function readVersion() {
  const raw = await readFile(join(__dirname, 'package.json'), 'utf8');
  return JSON.parse(raw).version;
}

/**
 * Resolve the cache directory, respecting XDG_CACHE_HOME.
 *
 * @returns {string}
 */
function resolveCacheDir() {
  const xdgCache = process.env.XDG_CACHE_HOME;
  const base = xdgCache ?? join(homedir(), '.cache');
  return join(base, 'jvm-heap-dump-mcp');
}

/**
 * Build the GitHub Releases download URL for the shadow JAR.
 *
 * @param {string} version
 * @returns {string}
 */
function jarUrl(version) {
  return `https://github.com/Djaler/jvm-heap-dump-mcp/releases/download/v${version}/jvm-heap-dump-mcp-${version}-all.jar`;
}

/**
 * Verify `java` is on PATH and is version >= 21. Exits with a descriptive
 * error otherwise.
 *
 * @returns {Promise<void>}
 */
async function checkJava() {
  let output;
  try {
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

  // Examples:
  //   openjdk version "21.0.3" 2024-04-16
  //   java version "1.8.0_391"
  const match = output.match(/version\s+"(\d+)(?:\.(\d+))?/);
  if (!match) {
    process.stderr.write(`jvm-heap-dump-mcp: Could not parse java version from: ${output.trim()}\n`);
    process.exit(1);
  }
  const major = parseInt(match[1], 10);
  const effective = major === 1 ? parseInt(match[2] ?? '0', 10) : major;
  if (effective < 21) {
    process.stderr.write(`jvm-heap-dump-mcp: Java 21+ is required, but found Java ${effective}.\n`);
    process.stderr.write('  Please upgrade: https://adoptium.net\n');
    process.exit(1);
  }
}

/**
 * Download url → dest with atomic rename via .downloading suffix.
 * Supports resume via HTTP Range when startOffset > 0.
 *
 * On error, the .downloading file is KEPT so a subsequent run can resume
 * from wherever it left off.
 *
 * @param {string} url
 * @param {string} dest
 * @param {object} [opts]
 * @param {number} [opts.startOffset=0]
 * @param {NodeJS.WriteStream} [opts.logStream]
 * @returns {Promise<void>}
 */
async function downloadFile(url, dest, opts = {}) {
  const startOffset = opts.startOffset ?? 0;
  const logStream = opts.logStream ?? process.stderr;
  const tmp = dest + '.downloading';

  const headers = startOffset > 0 ? { Range: `bytes=${startOffset}-` } : {};
  const response = await fetch(url, { redirect: 'follow', headers });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status} ${response.statusText}`);
  }
  if (!response.body) {
    throw new Error('Empty response body');
  }

  // If we asked for a range but the server returned 200, it didn't honor
  // the Range header. Fall back to a full download (overwrite).
  const serverHonoredRange = startOffset > 0 && response.status === 206;
  const contentLength = parseInt(response.headers.get('content-length') ?? '0', 10);
  const total = serverHonoredRange ? startOffset + contentLength : contentLength;
  const initialBytes = serverHonoredRange ? startOffset : 0;

  if (startOffset > 0 && !serverHonoredRange) {
    logStream.write('jvm-heap-dump-mcp: server ignored Range request, restarting from zero.\n');
  }

  let downloaded = initialBytes;
  let lastPct = total > 0 ? Math.floor((initialBytes / total) * 100) : -1;

  const tracker = new Transform({
    transform(chunk, _enc, cb) {
      downloaded += chunk.length;
      if (total) {
        const pct = Math.floor((downloaded / total) * 100);
        if (pct !== lastPct && pct % 10 === 0) {
          lastPct = pct;
          logStream.write(
            `jvm-heap-dump-mcp: downloading JAR... ${pct}% (${(downloaded / 1024 / 1024).toFixed(1)} MB / ${(total / 1024 / 1024).toFixed(1)} MB)\n`
          );
        }
      }
      cb(null, chunk);
    },
  });

  const body = Readable.fromWeb(response.body);
  const out = createWriteStream(tmp, serverHonoredRange ? { flags: 'a' } : undefined);
  await pipeline(body, tracker, out);
  renameSync(tmp, dest);
  logStream.write('jvm-heap-dump-mcp: JAR downloaded successfully.\n');
}

/**
 * Ensure JAR for the given version is in the cache. Downloads if missing,
 * resumes from a partial .downloading file if present.
 *
 * @param {string} version
 * @param {object} [opts]
 * @param {NodeJS.WriteStream} [opts.logStream]
 * @returns {Promise<string>} path to the cached JAR
 */
async function ensureJar(version, opts = {}) {
  const logStream = opts.logStream ?? process.stderr;
  const cacheDir = resolveCacheDir();
  mkdirSync(cacheDir, { recursive: true });
  const jarPath = join(cacheDir, `jvm-heap-dump-mcp-${version}-all.jar`);
  if (existsSync(jarPath)) return jarPath;

  const tmpPath = jarPath + '.downloading';
  const url = jarUrl(version);
  let startOffset = 0;

  if (existsSync(tmpPath)) {
    const partialSize = statSync(tmpPath).size;
    // HEAD the final URL to learn the total size. If HEAD fails, we'll just
    // attempt a full download (overwriting the partial).
    let totalSize = 0;
    try {
      const head = await fetch(url, { method: 'HEAD', redirect: 'follow' });
      if (head.ok) {
        totalSize = parseInt(head.headers.get('content-length') ?? '0', 10);
      }
    } catch (err) {
      logStream.write(`jvm-heap-dump-mcp: HEAD probe failed (${err.message}), will retry full download.\n`);
    }

    if (totalSize > 0 && partialSize === totalSize) {
      // Fully downloaded but never renamed (e.g. killed between pipeline end and rename).
      renameSync(tmpPath, jarPath);
      return jarPath;
    }
    if (totalSize > 0 && partialSize > totalSize) {
      // Partial file is larger than the published JAR — corrupted. Discard.
      logStream.write(`jvm-heap-dump-mcp: partial file size (${partialSize}) exceeds expected (${totalSize}), discarding.\n`);
      unlinkSync(tmpPath);
    } else if (partialSize > 0) {
      logStream.write(
        `jvm-heap-dump-mcp: resuming download from ${(partialSize / 1024 / 1024).toFixed(1)} MB\n`
      );
      startOffset = partialSize;
    }
  }

  logStream.write(
    startOffset > 0
      ? `jvm-heap-dump-mcp: resuming fetch of ${url}\n`
      : `jvm-heap-dump-mcp: cache miss, fetching ${url}\n`
  );
  await downloadFile(url, jarPath, { startOffset, logStream });
  return jarPath;
}

// ===================================================================
// Fast path: JAR already in cache — spawn Java and pipe stdio.
// ===================================================================

/**
 * Spawn java -jar and forward stdio transparently. Used when the JAR is
 * already in cache.
 *
 * @param {string} jarPath
 * @returns {Promise<never>}
 */
async function runDirect(jarPath) {
  const javaOpts = process.env.JAVA_OPTS ? process.env.JAVA_OPTS.split(/\s+/) : ['-Xmx4g'];
  const child = spawn('java', [...javaOpts, '-jar', jarPath], { stdio: 'inherit' });

  const forward = (signal) => {
    if (!child.killed) child.kill(signal);
  };
  process.on('SIGINT', () => forward('SIGINT'));
  process.on('SIGTERM', () => forward('SIGTERM'));

  await new Promise(() => {
    child.on('exit', (code, signal) => {
      if (signal) process.kill(process.pid, signal);
      else process.exit(code ?? 0);
    });
  });
}

// ===================================================================
// Pending mode: answer MCP handshake ourselves while JAR downloads.
// ===================================================================

/**
 * @typedef {object} PendingState
 * @property {string} mode          "PENDING" | "READY"
 * @property {string} stdinBuffer
 * @property {string|number|null} initializeId
 * @property {object|null} initializeParams
 * @property {boolean} clientSentInitialized
 * @property {import('node:child_process').ChildProcessWithoutNullStreams|null} java
 * @property {string} version
 * @property {boolean} downloadFailed
 */

/**
 * Write a JSON-RPC message to the client via stdout.
 *
 * @param {object} msg
 */
function writeToClient(msg) {
  process.stdout.write(JSON.stringify(msg) + '\n');
}

/**
 * Handle a single JSON-RPC message from the client while we are still in
 * pending mode (Java process not yet spawned).
 *
 * @param {object} msg
 * @param {PendingState} state
 */
function handlePending(msg, state) {
  const { id, method, params } = msg;
  const isRequest = id !== undefined && id !== null;

  switch (method) {
    case 'initialize':
      if (!isRequest) return;
      state.initializeId = id;
      state.initializeParams = params;
      writeToClient({
        jsonrpc: '2.0',
        id,
        result: {
          protocolVersion: params?.protocolVersion ?? '2024-11-05',
          capabilities: { tools: { listChanged: true } },
          serverInfo: { name: 'jvm-heap-dump-mcp', version: state.version },
        },
      });
      return;

    case 'notifications/initialized':
      state.clientSentInitialized = true;
      return;

    case 'ping':
      if (isRequest) writeToClient({ jsonrpc: '2.0', id, result: {} });
      return;

    case 'tools/list':
      if (isRequest) writeToClient({ jsonrpc: '2.0', id, result: { tools: [] } });
      return;

    // Some clients probe for these capabilities after initialize. Return empty
    // lists so the client doesn't log "method not found" noise; our real server
    // doesn't support them either.
    case 'resources/list':
      if (isRequest) writeToClient({ jsonrpc: '2.0', id, result: { resources: [] } });
      return;
    case 'prompts/list':
      if (isRequest) writeToClient({ jsonrpc: '2.0', id, result: { prompts: [] } });
      return;

    case 'tools/call':
      if (isRequest) {
        const message = state.downloadFailed
          ? 'jvm-heap-dump-mcp: JAR download failed. Check network/proxy and clear ~/.cache/jvm-heap-dump-mcp, then restart your MCP client.'
          : PENDING_TOOL_CALL_ERROR;
        writeToClient({
          jsonrpc: '2.0',
          id,
          error: { code: -32000, message },
        });
      }
      return;

    default:
      if (isRequest) {
        writeToClient({
          jsonrpc: '2.0',
          id,
          error: { code: -32601, message: `Method not found: ${method}` },
        });
      }
      // unknown notifications are ignored
  }
}

/**
 * Process accumulated stdin data as newline-delimited JSON-RPC messages.
 * Called after each chunk arrives (pending mode).
 *
 * @param {PendingState} state
 */
function processStdinBuffer(state) {
  while (true) {
    const idx = state.stdinBuffer.indexOf('\n');
    if (idx < 0) break;
    const line = state.stdinBuffer.slice(0, idx);
    state.stdinBuffer = state.stdinBuffer.slice(idx + 1);
    if (line.trim() === '') continue;

    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      writeToClient({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } });
      continue;
    }
    handlePending(msg, state);
  }
}

/**
 * Transition from pending to passthrough mode: spawn Java, replay the
 * cached initialize request, swallow Java's response, then become a
 * transparent bidirectional proxy.
 *
 * @param {string} jarPath
 * @param {PendingState} state
 */
async function transitionToPassthrough(jarPath, state) {
  const javaOpts = process.env.JAVA_OPTS ? process.env.JAVA_OPTS.split(/\s+/) : ['-Xmx4g'];
  const java = spawn('java', [...javaOpts, '-jar', jarPath], {
    stdio: ['pipe', 'pipe', 'inherit'],
  });
  state.java = java;

  // Consume Java's stdout line by line until we find the initialize response
  // matching our cached id. Swallow that one, forward everything else.
  let javaBuffer = '';
  const initializeAck = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error('Java MCP server did not respond to initialize within 60 seconds'));
    }, 60_000);

    const onData = (chunk) => {
      javaBuffer += chunk.toString('utf8');
      while (true) {
        const idx = javaBuffer.indexOf('\n');
        if (idx < 0) break;
        const line = javaBuffer.slice(0, idx);
        javaBuffer = javaBuffer.slice(idx + 1);
        if (line.trim() === '') continue;

        let msg;
        try {
          msg = JSON.parse(line);
        } catch {
          // Forward malformed line as-is — shouldn't happen from our server
          process.stdout.write(line + '\n');
          continue;
        }

        if (msg.id === state.initializeId) {
          // Swallow: client already has our own initialize response
          clearTimeout(timer);
          java.stdout.off('data', onData);
          // Flush any already-buffered remainder to client
          if (javaBuffer.length > 0) {
            process.stdout.write(javaBuffer);
            javaBuffer = '';
          }
          // From here on, Java output flows directly to client
          java.stdout.pipe(process.stdout, { end: false });
          resolve();
          return;
        }

        // Any unrelated message from Java (shouldn't happen before init response)
        // goes straight to the client.
        process.stdout.write(line + '\n');
      }
    };
    java.stdout.on('data', onData);
    java.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
    // If Java exits before responding to initialize (e.g. JAR is corrupted,
    // JVM crashes at startup), fail fast instead of waiting for the 60s timer.
    java.once('exit', (code, signal) => {
      clearTimeout(timer);
      reject(new Error(`Java exited before initialize completed (code=${code}, signal=${signal})`));
    });
  });

  // Replay the initialize request we captured from the client.
  java.stdin.write(JSON.stringify({
    jsonrpc: '2.0',
    id: state.initializeId,
    method: 'initialize',
    params: state.initializeParams ?? {},
  }) + '\n');

  await initializeAck;

  // Flip the switch BEFORE any further awaits/writes — any stdin 'data'
  // events arriving from now on will route directly to java.stdin instead
  // of being handled in pending mode.
  state.mode = 'READY';

  // Flush any queued (line-buffered) stdin bytes to java first, so they
  // arrive before the notifications/initialized replay.
  if (state.stdinBuffer.length > 0) {
    java.stdin.write(state.stdinBuffer);
    state.stdinBuffer = '';
  }

  // If the client already sent the initialized notification, replay it now.
  if (state.clientSentInitialized) {
    java.stdin.write(JSON.stringify({
      jsonrpc: '2.0',
      method: 'notifications/initialized',
    }) + '\n');
  }

  // Tell the client the real tool list is now available.
  writeToClient({ jsonrpc: '2.0', method: 'notifications/tools/list_changed' });

  // When Java exits we exit with the same code/signal.
  java.on('exit', (code, signal) => {
    if (signal) process.kill(process.pid, signal);
    else process.exit(code ?? 0);
  });

  // Forward shutdown signals.
  process.on('SIGINT', () => { if (!java.killed) java.kill('SIGINT'); });
  process.on('SIGTERM', () => { if (!java.killed) java.kill('SIGTERM'); });
}

/**
 * Run in pending mode: answer MCP handshake ourselves, download the JAR
 * in the background, then transition to passthrough.
 *
 * @param {string} version
 */
async function runPending(version) {
  /** @type {PendingState} */
  const state = {
    mode: 'PENDING',
    stdinBuffer: '',
    initializeId: null,
    initializeParams: null,
    clientSentInitialized: false,
    java: null,
    version,
    downloadFailed: false,
  };

  process.stdin.on('data', (chunk) => {
    if (state.mode === 'READY' && state.java) {
      state.java.stdin.write(chunk);
      return;
    }
    state.stdinBuffer += chunk.toString('utf8');
    processStdinBuffer(state);
  });

  let jarPath;
  try {
    jarPath = await ensureJar(version);
  } catch (err) {
    process.stderr.write(`jvm-heap-dump-mcp: download failed — ${err.message}\n`);
    state.downloadFailed = true;
    // Give the client a moment to drain any in-flight requests (which will
    // get the updated error message) before exiting non-zero so the client
    // knows the server is dead and won't try to reuse it.
    setTimeout(() => process.exit(1), 2000);
    return;
  }

  await transitionToPassthrough(jarPath, state);
}

// ===================================================================
// Entry point
// ===================================================================

/**
 * Handle `npx jvm-heap-dump-mcp --prepare`: synchronously download the JAR
 * with progress on stdout, then exit. Intended to be run once by the user
 * to warm the cache before starting the MCP client.
 *
 * @param {string} version
 */
async function runPrepare(version) {
  const jarPath = join(resolveCacheDir(), `jvm-heap-dump-mcp-${version}-all.jar`);
  if (existsSync(jarPath)) {
    process.stdout.write(`JAR already cached at ${jarPath}\n`);
    return;
  }
  process.stdout.write(`Downloading jvm-heap-dump-mcp v${version} JAR (~28 MB)...\n`);
  try {
    await ensureJar(version, { logStream: process.stdout });
  } catch (err) {
    process.stderr.write(`\njvm-heap-dump-mcp: download failed — ${err.message}\n`);
    process.stderr.write(
      'Partial download preserved — re-run the same command to resume from where it stopped.\n'
    );
    process.exit(1);
  }
  process.stdout.write(
    `\nDone. JAR cached at ${jarPath}.\nThe MCP server will now start instantly on subsequent launches.\n`
  );
}

async function main() {
  const arg = process.argv[2];
  const isPrepare = arg === '--prepare' || arg === 'prepare';

  await checkJava();
  const version = await readVersion();

  if (isPrepare) {
    await runPrepare(version);
    return;
  }

  const jarPath = join(resolveCacheDir(), `jvm-heap-dump-mcp-${version}-all.jar`);

  if (existsSync(jarPath)) {
    // Fast path: JAR already in cache. Spawn Java directly as a transparent
    // launcher — no pending mode needed, no list_changed notification.
    await runDirect(jarPath);
    return;
  }

  // Cold start: pending mode + background download + transition.
  await runPending(version);
}

main().catch((err) => {
  process.stderr.write(`jvm-heap-dump-mcp: fatal error — ${err.message}\n`);
  if (err.stack) process.stderr.write(err.stack + '\n');
  process.exit(1);
});
