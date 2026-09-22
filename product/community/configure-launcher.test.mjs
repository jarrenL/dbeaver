import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {configureLauncher} from './configure-launcher.mjs';

function fixture(t, newline = '\n') {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gaussdb-launcher-test-'));
    t.after(() => fs.rmSync(root, {recursive: true, force: true}));
    fs.mkdirSync(path.join(root, 'plugins'));
    fs.writeFileSync(path.join(root, 'plugins/org.jkiss.dbeaver.launcher_1.0.59.test.jar'), 'fixture');
    fs.writeFileSync(path.join(root, 'dbeaver.ini'), ['-startup', 'plugins/org.eclipse.equinox.launcher.jar',
        '-vm', 'jre/bin/javaw.exe', '-vmargs', '-Xmx1024m', ''].join(newline));
    return root;
}

for (const newline of ['\n', '\r\n']) {
    test(`uses DBeaver launcher, preserves VM and is idempotent (${JSON.stringify(newline)})`, t => {
        const root = fixture(t, newline);
        configureLauncher(root);
        const first = fs.readFileSync(path.join(root, 'dbeaver.ini'), 'utf8');
        assert(first.includes('plugins/org.jkiss.dbeaver.launcher_1.0.59.test.jar'));
        assert(first.includes(['-vm', 'jre/bin/javaw.exe', '-vmargs'].join(newline)));
        configureLauncher(root);
        assert.equal(fs.readFileSync(path.join(root, 'dbeaver.ini'), 'utf8'), first);
    });
}
test('rejects ambiguous launchers instead of silently shipping broken settings', t => {
    const root = fixture(t);
    fs.writeFileSync(path.join(root, 'plugins/org.jkiss.dbeaver.launcher_2.jar'), 'fixture');
    assert.throws(() => configureLauncher(root), /exactly one/);
});
test('rejects forced locale in a customer package', t => {
    const root = fixture(t);
    fs.appendFileSync(path.join(root, 'dbeaver.ini'), '-nl\nzh\n');
    assert.throws(() => configureLauncher(root), /override/);
});
