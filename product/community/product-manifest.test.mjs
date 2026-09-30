import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { inventory, verifyInventory } from './product-manifest.mjs';

function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'product-inventory-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    fs.writeFileSync(path.join(root, 'launcher'), 'binary fixture');
    fs.chmodSync(path.join(root, 'launcher'), 0o755);
    fs.mkdirSync(path.join(root, 'legal'));
    fs.writeFileSync(path.join(root, 'legal', 'license'), 'license text');
    fs.symlinkSync('license', path.join(root, 'legal', 'alias'));
    return root;
}

test('inventory is stable, records links without following, and verifies unchanged tree', t => {
    const root = fixture(t);
    const manifest = inventory(root);
    assert.deepEqual(inventory(root), manifest);
    assert.equal(verifyInventory(root, manifest), 4);
    assert.deepEqual(manifest.entries.find(e => e.type === 'link'),
        { path: 'legal/alias', type: 'link', target: 'license' });
});

for (const [name, mutate] of [
    ['changed bytes', r => fs.writeFileSync(path.join(r, 'launcher'), 'modified')],
    ['extra file', r => fs.writeFileSync(path.join(r, 'unexpected'), 'extra')],
    ['missing file', r => fs.unlinkSync(path.join(r, 'launcher'))],
    ['lost executable bit', r => fs.chmodSync(path.join(r, 'launcher'), 0o644)],
    ['changed link', r => {
        fs.unlinkSync(path.join(r, 'legal', 'alias'));
        fs.symlinkSync('../launcher', path.join(r, 'legal', 'alias'));
    }],
]) {
    test(`rejects ${name}`, t => {
        const root = fixture(t);
        const manifest = inventory(root);
        mutate(root);
        assert.throws(() => verifyInventory(root, manifest), /inventory differs/);
    });
}
