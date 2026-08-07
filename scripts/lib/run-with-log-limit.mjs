#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

function fail(message) {
  process.stderr.write(`run-with-log-limit: ${message}\n`);
  process.exit(2);
}

function positiveInteger(value, name) {
  if (!/^\d+$/.test(value ?? '') || Number(value) <= 0) {
    fail(`${name} must be a positive integer`);
  }
  return Number(value);
}

function parseArguments(argv) {
  const separator = argv.indexOf('--');
  if (separator < 0 || separator === argv.length - 1) {
    fail('usage: run-with-log-limit.mjs [options] -- command [args...]');
  }

  const optionArgs = argv.slice(0, separator);
  const commandArgs = argv.slice(separator + 1);
  const values = new Map();
  for (let index = 0; index < optionArgs.length; index += 2) {
    const key = optionArgs[index];
    const value = optionArgs[index + 1];
    if (!key?.startsWith('--') || value === undefined) {
      fail(`invalid option near ${key ?? '<empty>'}`);
    }
    values.set(key, value);
  }

  const cwd = values.get('--cwd');
  const stdoutPath = values.get('--stdout');
  const stderrPath = values.get('--stderr');
  if (!cwd || !stdoutPath || !stderrPath) {
    fail('--cwd, --stdout, and --stderr are required');
  }

  let maxBytes;
  if (values.has('--max-size-bytes')) {
    maxBytes = positiveInteger(values.get('--max-size-bytes'), '--max-size-bytes');
  } else {
    maxBytes = positiveInteger(values.get('--max-size-mb') ?? '10', '--max-size-mb') * 1024 * 1024;
  }

  return {
    cwd: path.resolve(cwd),
    stdoutPath: path.resolve(stdoutPath),
    stderrPath: path.resolve(stderrPath),
    maxBytes,
    maxFilesPerDay: positiveInteger(values.get('--max-files') ?? '3', '--max-files'),
    retentionDays: positiveInteger(values.get('--retention-days') ?? '30', '--retention-days'),
    command: commandArgs[0],
    commandArgs: commandArgs.slice(1),
  };
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function localDateKey(date = new Date()) {
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-');
}

function naturalDayOrdinal(dateKey) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateKey);
  if (!match) return Number.NaN;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const timestamp = Date.UTC(year, month - 1, day);
  const check = new Date(timestamp);
  if (check.getUTCFullYear() !== year || check.getUTCMonth() !== month - 1 || check.getUTCDate() !== day) {
    return Number.NaN;
  }
  return Math.floor(timestamp / 86400000);
}

class RotatingLog {
  constructor(filePath, maxBytes, maxFilesPerDay, retentionDays) {
    this.filePath = filePath;
    this.maxBytes = maxBytes;
    this.maxFilesPerDay = maxFilesPerDay;
    this.retentionDays = retentionDays;
    this.fd = undefined;
    fs.mkdirSync(path.dirname(filePath), { recursive: true });

    const today = localDateKey();
    this.prune(today);
    if (fs.existsSync(filePath)) {
      this.trimToTail(filePath);
      const stat = fs.statSync(filePath);
      const fileDate = localDateKey(stat.mtime);
      if (stat.size > 0 && fileDate !== today) {
        this.archiveActive(fileDate);
      }
    }

    this.activeDate = today;
    this.size = fs.existsSync(filePath) ? fs.statSync(filePath).size : 0;
    this.fd = fs.openSync(filePath, 'a');
  }

  trimToTail(filePath) {
    const size = fs.statSync(filePath).size;
    if (size <= this.maxBytes) return;
    const fd = fs.openSync(filePath, 'r');
    try {
      const tail = Buffer.alloc(this.maxBytes);
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

  archivePath(dateKey, index) {
    return index === 1 ? `${this.filePath}.${dateKey}` : `${this.filePath}.${dateKey}.${index}`;
  }

  prune(today = localDateKey()) {
    const cutoffOrdinal = naturalDayOrdinal(today) - this.retentionDays + 1;
    const directory = path.dirname(this.filePath);
    const baseName = path.basename(this.filePath);
    const datedPattern = new RegExp(`^${escapeRegExp(baseName)}\\.(\\d{4}-\\d{2}-\\d{2})(?:\\.(\\d+))?$`);
    const legacyPattern = new RegExp(`^${escapeRegExp(baseName)}\\.(\\d+)$`);

    for (const entry of fs.readdirSync(directory)) {
      const archivePath = path.join(directory, entry);
      const datedMatch = datedPattern.exec(entry);
      if (datedMatch) {
        const archiveOrdinal = naturalDayOrdinal(datedMatch[1]);
        const part = Number(datedMatch[2] ?? '1');
        if (!Number.isFinite(archiveOrdinal) || archiveOrdinal < cutoffOrdinal || part > this.maxFilesPerDay) {
          fs.rmSync(archivePath, { force: true });
        } else if (fs.statSync(archivePath).isFile()) {
          this.trimToTail(archivePath);
        }
        continue;
      }

      const legacyMatch = legacyPattern.exec(entry);
      if (!legacyMatch) continue;
      const archiveOrdinal = naturalDayOrdinal(localDateKey(fs.statSync(archivePath).mtime));
      if (archiveOrdinal < cutoffOrdinal || Number(legacyMatch[1]) >= this.maxFilesPerDay) {
        fs.rmSync(archivePath, { force: true });
      } else if (fs.statSync(archivePath).isFile()) {
        this.trimToTail(archivePath);
      }
    }
  }

  archiveActive(dateKey) {
    if (this.fd !== undefined) {
      fs.closeSync(this.fd);
      this.fd = undefined;
    }
    if (!fs.existsSync(this.filePath) || fs.statSync(this.filePath).size === 0) {
      fs.rmSync(this.filePath, { force: true });
      this.size = 0;
      return;
    }

    fs.rmSync(this.archivePath(dateKey, this.maxFilesPerDay), { force: true });
    for (let index = this.maxFilesPerDay - 1; index >= 1; index -= 1) {
      const source = this.archivePath(dateKey, index);
      if (fs.existsSync(source)) {
        fs.renameSync(source, this.archivePath(dateKey, index + 1));
      }
    }
    fs.renameSync(this.filePath, this.archivePath(dateKey, 1));
    this.size = 0;
  }

  reopen() {
    this.fd = fs.openSync(this.filePath, 'a');
    this.size = fs.statSync(this.filePath).size;
  }

  rotateForSize() {
    this.archiveActive(this.activeDate);
    this.reopen();
  }

  rotateForDay(today) {
    this.archiveActive(this.activeDate);
    this.activeDate = today;
    this.prune(today);
    this.reopen();
  }

  write(chunk) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    let offset = 0;
    while (offset < buffer.length) {
      const today = localDateKey();
      if (today !== this.activeDate) {
        this.rotateForDay(today);
      }
      if (this.size >= this.maxBytes) {
        this.rotateForSize();
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

const options = parseArguments(process.argv.slice(2));
if (!fs.statSync(options.cwd).isDirectory()) {
  fail(`working directory does not exist: ${options.cwd}`);
}
process.chdir(options.cwd);

const stdoutLog = new RotatingLog(
  options.stdoutPath,
  options.maxBytes,
  options.maxFilesPerDay,
  options.retentionDays,
);
const stderrLog = new RotatingLog(
  options.stderrPath,
  options.maxBytes,
  options.maxFilesPerDay,
  options.retentionDays,
);
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
