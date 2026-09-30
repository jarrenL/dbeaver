import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {verifyCleanProduct} from './verify-clean-product.mjs';

function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'clean-product-test-'));
    t.after(() => fs.rmSync(root, {recursive: true, force: true}));
    fs.mkdirSync(path.join(root, 'plugins'));
    fs.mkdirSync(path.join(root, 'configuration/org.eclipse.equinox.simpleconfigurator'), {recursive: true});
    fs.writeFileSync(path.join(root, 'dbeaver.ini'), '-startup\nplugins/launcher.jar\n-vmargs\n-Xmx1g\n');
    fs.writeFileSync(path.join(root, 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'), 'org.jkiss.dbeaver.core,1,plugins/core.jar,4,false\n');
    return root;
}
test('clean source is accepted without claiming GUI or license acceptance', t => {
    assert.equal(verifyCleanProduct(fixture(t)).cleanSource, true);
});
for (const name of ['.metadata', 'workspace6', 'queue', 'credentials-config.json', 'data-sources.json',
    'run.launch', '001.cmd.result', 'gsjdbc200.jar', 'plugins/org.eclipse.swtbot.jar',
    'plugins/org.jkiss.dbeaver.ext.gaussdb.test_1.jar']) {
    test('rejects ' + name, t => {
        const root = fixture(t); fs.writeFileSync(path.join(root, name), 'synthetic');
        assert.throws(() => verifyCleanProduct(root), /polluted product/);
    });
}
test('rejects test registrations even if jar was removed', t => {
    const root = fixture(t);
    fs.appendFileSync(path.join(root, 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'), 'org.eclipse.swtbot.swt.finder,1,plugins/missing.jar,4,false\n');
    assert.throws(() => verifyCleanProduct(root), /test bundle/);
});
test('rejects queue and workspace overrides', t => {
    const root = fixture(t);
    for (const extra of ['-Dgaussdb.swtbot.queue=/tmp/test', '-data\n/tmp/workspace']) {
        fs.writeFileSync(path.join(root, 'dbeaver.ini'), extra);
        assert.throws(() => verifyCleanProduct(root), /test\/user override/);
    }
});
test('does not follow external symbolic links', t => {
    const root = fixture(t); fs.symlinkSync('/nonexistent-private-location', path.join(root, 'linked'));
    assert.throws(() => verifyCleanProduct(root), /symlink/);
});
test('never ships saved global user settings', t => {
    const root = fixture(t);
    fs.mkdirSync(path.join(root, 'settings'));
    fs.writeFileSync(path.join(root, 'settings/global-settings.ini'), 'synthetic=true');
    assert.throws(() => verifyCleanProduct(root), /global-settings/);
    assert.throws(() => verifyCleanProduct(root, {assembled: true}), /global-settings/);
});
test('assembled mode permits only contained regular license links', t => {
    const root = fixture(t);
    fs.mkdirSync(path.join(root, 'jre/legal/base'), {recursive: true});
    fs.mkdirSync(path.join(root, 'jre/legal/other'));
    fs.writeFileSync(path.join(root, 'jre/legal/base/LICENSE'), 'synthetic license');
    fs.symlinkSync('../base/LICENSE', path.join(root, 'jre/legal/other/LICENSE'));
    assert.throws(() => verifyCleanProduct(root), /symlink/);
    assert.equal(verifyCleanProduct(root, {assembled: true}).assembled, true);
});
for (const target of ['../../../../outside', '../../../dbeaver.ini', '../missing', '../base', '/nonexistent-license']) {
    test('assembled mode rejects unsafe license target ' + target, t => {
        const root = fixture(t);
        fs.mkdirSync(path.join(root, 'jre/legal/base'), {recursive: true});
        fs.mkdirSync(path.join(root, 'jre/legal/other'));
        fs.symlinkSync(target, path.join(root, 'jre/legal/other/LICENSE'));
        assert.throws(() => verifyCleanProduct(root, {assembled: true}), /symlink/);
    });
}
