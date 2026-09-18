// Assemble a clean Windows x86_64 payload. No downloading or credential handling.
// Usage: node assemble.mjs PRODUCT EXTRACTED_JRE NEW_OUTPUT SOURCE_REVISION
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const [product, jre, output, revision] = process.argv.slice(2);
assert(product && jre && output && /^[0-9a-f]{40}$/.test(revision ?? ''), 'Expected product, JRE, new output and full source SHA');
assert(!fs.existsSync(output), 'Output must not already exist');
function requireX64(file) {
  const bytes = fs.readFileSync(file);
  assert.equal(bytes.toString('ascii', 0, 2), 'MZ', `${file}: not PE`);
  const offset = bytes.readUInt32LE(0x3c);
  assert.equal(bytes.readUInt32LE(offset), 0x4550, `${file}: invalid PE`);
  assert.equal(bytes.readUInt16LE(offset + 4), 0x8664, `${file}: not x86_64`);
}
requireX64(path.join(product, 'dbeaver.exe'));
requireX64(path.join(jre, 'bin/java.exe'));
requireX64(path.join(jre, 'bin/server/jvm.dll'));
const bundles = fs.readdirSync(path.join(product, 'plugins'));
for (const id of ['org.jkiss.dbeaver.ext.gaussdb', 'org.jkiss.dbeaver.ext.gaussdb.ui',
  'org.jkiss.dbeaver.ext.gaussdb.debug.core', 'org.jkiss.dbeaver.ext.gaussdb.debug.ui']) {
  assert.equal(bundles.filter(name => name.startsWith(`${id}_`)).length, 1, `Missing/duplicate ${id}`);
}
assert(!bundles.some(name => /swtbot|gaussdb.acceptance|\.test_/.test(name)), 'Test bundle in payload');
const payload = path.join(output, 'dbeaver');
fs.mkdirSync(output, {recursive: true});
fs.cpSync(product, payload, {recursive: true});
fs.cpSync(jre, path.join(payload, 'jre'), {recursive: true});
const iniFile = path.join(payload, 'dbeaver.ini');
let ini = fs.readFileSync(iniFile, 'utf8');
assert(!/^\s*-vm\s*$/m.test(ini), 'Unexpected preconfigured VM');
assert(ini.includes('-vmargs'), 'Missing VM arguments');
fs.writeFileSync(iniFile, ini.replace('-vmargs', '-vm\njre/bin/javaw.exe\n-vmargs'));
const root = path.resolve(import.meta.dirname, '../../..');
for (const name of ['LICENSE.md', 'GAUSSDB_CROSS_PLATFORM_USER_GUIDE.md', 'GAUSSDB_WINDOWS_TEST_GUIDE.md']) {
  fs.copyFileSync(path.join(root, name), path.join(payload, name));
}
fs.copyFileSync(path.join(import.meta.dirname, 'README.md'), path.join(payload, 'WINDOWS-PREVIEW.md'));
fs.writeFileSync(path.join(payload, 'BUILD-MANIFEST.txt'),
  `Source: https://github.com/jarrenL/dbeaver\nRevision: ${revision}\nPlatform: win32/win32/x86_64\nUnsigned preview; Windows execution NOT validated.\nJDBC/native GaussDB tools are NOT bundled.\n\n${fs.readFileSync(path.join(jre, 'release'), 'utf8')}`);
const files = [], directories = [];
function walk(dir, relative = '') {
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const rel = path.join(relative, entry.name);
    assert(!/[\r\n"$]/.test(rel), 'Unsafe NSIS filename');
    if (entry.isDirectory()) { walk(path.join(dir, entry.name), rel); directories.push(rel); }
    else { assert(entry.isFile(), 'Symlinks not supported'); files.push(rel); }
  }
}
walk(payload);
const nsisPath = name => '$INSTDIR\\' + name.split(path.sep).join('\\');
fs.writeFileSync(path.join(output, 'remove-files.nsh'),
  files.map(name => `Delete "${nsisPath(name)}"`).concat(directories.map(name => `RMDir "${nsisPath(name)}"`)).join('\n') + '\n');
console.log(`Validated x86_64 launchers/JRE, four GaussDB bundles, no test plugins. Packaged ${files.length} files.`);
