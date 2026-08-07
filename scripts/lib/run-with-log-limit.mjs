#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

function fail(message) {
  process.stderr.write(`run-with-log-limit: ${message}\n`);
  process.exit(2);
}

function parsePositiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    fail(`${name} must be a positive integer, received: ${value}`);
  }
  return parsed;
}

function parseArguments(argv) {
  const separator = argv.indexOf('--');
  if (separator < 0 || separator === argv.length - 1) {
    fail('usage: run-with-log-limit.mjs [options] -- command [args...]');
  }

  const options = {};
  for (let index = 0; index < separator; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (!name?.startsWith('--') || value === undefined) {
      fail(`invalid option near: ${name ?? '<empty>'}`);
    }
    options[name.slice(2)] = value;
  }

  const maxBytes = options['max-size-bytes']
    ? parsePositiveInteger(options['max-size-bytes'], 'max-size-bytes')
    : parsePositiveInteger(options['max-size-mb'] ?? '10', 'max-size-mb') * 1024 * 1024;

  return {
    cwd: path.resolve(options.cwd ?? process.cwd()),
    stdoutPath: path.resolve(options.stdout ?? fail('missing --stdout')),
    stderrPath: path.resolve(options.stderr ?? fail('missing --stderr')),
    maxBytes,
    maxFiles: parsePositiveInteger(options['max-files'] ?? '3', 'max-files'),
    retentionDays: parsePositiveInteger(options['retention-days'] ?? '14', 'retention-days'),
    command: argv[separator + 1],
    commandArgs: argv.slice(separator + 2),
  };
}

class RotatingLog {
  constructor(filePath, maxBytes, maxFiles, retentionDays) {
    this.filePath = filePath;
    this.maxBytes = maxBytes;
    this.maxFiles = maxFiles;
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    this.prune(retentionDays);
    this.trimToTail(filePath);
    this.size = this.fileSize(filePath);
    if (this.size >= this.maxBytes) {
      this.rotate();
    }
    this.fd = fs.openSync(filePath, 'a');
  }

  fileSize(filePath) {
    try {
      return fs.statSync(filePath).size;
    } catch (error) {
      if (error.code === 'ENOENT') return 0;
      throw error;
    }
  }

  trimToTail(filePath) {
    const size = this.fileSize(filePath);
    if (size <= this.maxBytes) return;

    const tail = Buffer.allocUnsafe(this.maxBytes);
    const fd = fs.openSync(filePath, 'r');
    try {
      let bytesRead = 0;
      while (bytesRead < tail.length) {
        const count = fs.readSync(fd, tail, bytesRead, tail.length - bytesRead, size - tail.length + bytesRead);
        if (count === 0) break;
        bytesRead += count;
      }
      fs.writeFileSync(filePath, tail.subarray(0, bytesRead));
    } finally {
      fs.closeSync(fd);
    }
  }

  prune(retentionDays) {
    const cutoff = Date.now() - retentionDays * 24 * 60 * 60 * 1000;
    const directory = path.dirname(this.filePath);
    const baseName = path.basename(this.filePath);
    for (const entry of fs.readdirSync(directory)) {
      const match = entry.match(new RegExp(`^${escapeRegExp(baseName)}\\.(\\d+)$`));
      if (!match) continue;
      const archivePath = path.join(directory, entry);
      const archiveNumber = Number(match[1]);
      const expired = fs.statSync(archivePath).mtimeMs < cutoff;
      if (expired || archiveNumber >= this.maxFiles) {
        fs.rmSync(archivePath, { force: true });
      } else {
        this.trimToTail(archivePath);
      }
    }
  }

  rotate() {
    if (this.fd !== undefined) {
      fs.closeSync(this.fd);
      this.fd = undefined;
    }

    if (this.maxFiles === 1) {
      fs.writeFileSync(this.filePath, '');
      this.size = 0;
      return;
    }

    fs.rmSync(`${this.filePath}.${this.maxFiles - 1}`, { force: true });
    for (let index = this.maxFiles - 2; index >= 1; index -= 1) {
      const source = `${this.filePath}.${index}`;
      if (fs.existsSync(source)) {
        fs.renameSync(source, `${this.filePath}.${index + 1}`);
      }
    }
    if (fs.existsSync(this.filePath)) {
      fs.renameSync(this.filePath, `${this.filePath}.1`);
    }
    this.size = 0;
  }

  write(chunk) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    let offset = 0;
    while (offset < buffer.length) {
      if (this.size >= this.maxBytes) {
        this.rotate();
        this.fd = fs.openSync(this.filePath, 'a');
      }
      const length = Math.min(this.maxBytes - this.size, buffer.length - offset);
      fs.writeSync(this.fd, buffer, offset, length);
      this.size += length;
      offset += length;
    }
  }

  close() {
    if (this.fd !== undefined) {
      fs.closeSync(this.fd);
      this.fd = undefined;
    }
  }
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const options = parseArguments(process.argv.slice(2));
if (!fs.statSync(options.cwd).isDirectory()) {
  fail(`working directory does not exist: ${options.cwd}`);
}
process.chdir(options.cwd);

const stdoutLog = new RotatingLog(options.stdoutPath, options.maxBytes, options.maxFiles, options.retentionDays);
const stderrLog = new RotatingLog(options.stderrPath, options.maxBytes, options.maxFiles, options.retentionDays);
const child = spawn(options.command, options.commandArgs, {
  cwd: options.cwd,
  env: process.env,
  stdio: ['ignore', 'pipe', 'pipe'],
  windowsHide: true,
  detached: process.platform !== 'win32',
});

let stopping = false;
let fatalLogError = false;
let forceKillTimer;

function terminateChild(signal = 'SIGTERM') {
  if (!child.pid || child.exitCode !== null || child.signalCode !== null) return;
  try {
    if (process.platform === 'win32') {
      child.kill(signal);
    } else {
      process.kill(-child.pid, signal);
    }
  } catch (error) {
    if (error.code !== 'ESRCH') {
      process.stderr.write(`run-with-log-limit: failed to terminate child: ${error.message}\n`);
    }
  }
}

function abortForLogError(error) {
  if (fatalLogError) return;
  fatalLogError = true;
  stopping = true;
  process.stderr.write(`run-with-log-limit: log write failed: ${error.message}\n`);
  child.stdout.destroy();
  child.stderr.destroy();
  terminateChild('SIGTERM');
  forceKillTimer = setTimeout(() => terminateChild('SIGKILL'), 3000);
  forceKillTimer.unref();
}

function writeSafely(log, chunk) {
  if (fatalLogError) return;
  try {
    log.write(chunk);
  } catch (error) {
    abortForLogError(error);
  }
}

child.stdout.on('data', (chunk) => writeSafely(stdoutLog, chunk));
child.stderr.on('data', (chunk) => writeSafely(stderrLog, chunk));
child.on('error', (error) => writeSafely(stderrLog, `Failed to start child process: ${error.message}\n`));

for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(signal, () => {
    if (stopping) return;
    stopping = true;
    terminateChild(signal);
  });
}

const signalExitCodes = {
  SIGHUP: 129,
  SIGINT: 130,
  SIGTERM: 143,
};

child.on('close', (code, signal) => {
  if (forceKillTimer) clearTimeout(forceKillTimer);
  stdoutLog.close();
  stderrLog.close();
  if (fatalLogError) {
    process.exitCode = 1;
  } else if (Number.isInteger(code)) {
    process.exitCode = code;
  } else {
    process.exitCode = signalExitCodes[signal] ?? 1;
  }
});
