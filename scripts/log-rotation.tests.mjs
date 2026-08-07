#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const runner = path.join(scriptDirectory, 'lib', 'run-with-log-limit.mjs');
const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'smart-worksite-log-rotation-'));

try {
  const stdoutPath = path.join(tempDirectory, 'service.out.log');
  const stderrPath = path.join(tempDirectory, 'service.err.log');
  const today = new Date();
  const recentDate = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 29);
  const expiredDate = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 30);
  const formatDate = (date) => [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-');
  const recentArchive = `${stdoutPath}.${formatDate(recentDate)}`;
  const expiredArchive = `${stdoutPath}.${formatDate(expiredDate)}`;
  fs.writeFileSync(recentArchive, 'recent archive');
  fs.writeFileSync(expiredArchive, 'expired archive');

  const childScript = [
    "process.stdout.write('O'.repeat(2048));",
    "process.stderr.write('E'.repeat(1536));",
  ].join('');
  const result = spawnSync(process.execPath, [
    runner,
    '--cwd', tempDirectory,
    '--stdout', stdoutPath,
    '--stderr', stderrPath,
    '--max-size-bytes', '256',
    '--max-files', '3',
    '--retention-days', '30',
    '--', process.execPath, '-e', childScript,
  ], { encoding: 'utf8' });

  assert.equal(result.status, 0, `runner failed: ${result.stderr}`);
  assert.equal(fs.existsSync(expiredArchive), false, 'archive outside the 30-day natural-day window was not deleted');
  assert.equal(fs.existsSync(recentArchive), true, 'archive on the 30-day inclusive boundary was deleted');
  assert.ok(
    fs.readdirSync(tempDirectory).some((entry) => /^service\.out\.log\.\d{4}-\d{2}-\d{2}(?:\.\d+)?$/.test(entry)),
    'rotated logs must use natural-day archive names',
  );

  for (const logPath of [stdoutPath, stderrPath]) {
    const matchingFiles = fs.readdirSync(tempDirectory)
      .filter((entry) => entry === path.basename(logPath) || entry.startsWith(`${path.basename(logPath)}.`));
    assert.ok(matchingFiles.length >= 2, `${logPath} did not rotate`);
    const archiveCountsByDay = new Map();
    for (const entry of matchingFiles) {
      assert.ok(fs.statSync(path.join(tempDirectory, entry)).size <= 256, `${entry} exceeded max-size-bytes`);
      const archiveMatch = entry.match(/\.(\d{4}-\d{2}-\d{2})(?:\.\d+)?$/);
      if (archiveMatch) {
        archiveCountsByDay.set(archiveMatch[1], (archiveCountsByDay.get(archiveMatch[1]) ?? 0) + 1);
      }
    }
    for (const [date, count] of archiveCountsByDay) {
      assert.ok(count <= 3, `${logPath} exceeded max-files for ${date}`);
    }
  }

  const existingPath = path.join(tempDirectory, 'existing.out.log');
  fs.writeFileSync(existingPath, `old-prefix-${'A'.repeat(600)}-active-tail`);
  fs.writeFileSync(`${existingPath}.1`, `old-prefix-${'B'.repeat(600)}-archive-tail`);
  const existingResult = spawnSync(process.execPath, [
    runner,
    '--cwd', tempDirectory,
    '--stdout', existingPath,
    '--stderr', path.join(tempDirectory, 'existing.err.log'),
    '--max-size-bytes', '256',
    '--max-files', '3',
    '--retention-days', '30',
    '--', process.execPath, '-e', '',
  ], { encoding: 'utf8' });
  assert.equal(existingResult.status, 0, `runner failed while bounding existing logs: ${existingResult.stderr}`);
  const existingFiles = fs.readdirSync(tempDirectory)
    .filter((entry) => entry === path.basename(existingPath) || entry.startsWith(`${path.basename(existingPath)}.`));
  assert.ok(existingFiles.length <= 3, 'existing logs exceeded max-files');
  for (const entry of existingFiles) {
    assert.ok(fs.statSync(path.join(tempDirectory, entry)).size <= 256, `${entry} exceeded max-size-bytes`);
  }
  assert.ok(
    existingFiles.some((entry) => fs.readFileSync(path.join(tempDirectory, entry), 'utf8').includes('active-tail')),
    'existing oversized active log did not preserve its tail',
  );
  assert.ok(
    existingFiles.some((entry) => fs.readFileSync(path.join(tempDirectory, entry), 'utf8').includes('archive-tail')),
    'existing oversized archive did not preserve its tail',
  );

  const exitResult = spawnSync(process.execPath, [
    runner,
    '--cwd', tempDirectory,
    '--stdout', path.join(tempDirectory, 'exit.out.log'),
    '--stderr', path.join(tempDirectory, 'exit.err.log'),
    '--max-size-bytes', '256',
    '--max-files', '2',
    '--retention-days', '30',
    '--', process.execPath, '-e', "process.stderr.write('expected failure'); process.exit(7)",
  ], { encoding: 'utf8' });
  assert.equal(exitResult.status, 7, 'child exit code was not propagated');
  assert.match(fs.readFileSync(path.join(tempDirectory, 'exit.err.log'), 'utf8'), /expected failure/);

  const writeFailurePath = path.join(tempDirectory, 'write-failure.out.log');
  fs.mkdirSync(`${writeFailurePath}.${formatDate(today)}`);
  const writeFailureResult = spawnSync(process.execPath, [
    runner,
    '--cwd', tempDirectory,
    '--stdout', writeFailurePath,
    '--stderr', path.join(tempDirectory, 'write-failure.err.log'),
    '--max-size-bytes', '64',
    '--max-files', '2',
    '--retention-days', '30',
    '--', process.execPath, '-e', "setInterval(() => process.stdout.write('X'.repeat(128)), 10)",
  ], { encoding: 'utf8', timeout: 5000 });
  assert.notEqual(writeFailureResult.error?.code, 'ETIMEDOUT', 'runner left child alive after a log write failure');
  assert.equal(writeFailureResult.status, 1, 'log write failure must fail the runner');

  process.stdout.write('PASS: bounded host log rotation works.\n');
} finally {
  fs.rmSync(tempDirectory, { recursive: true, force: true });
}
