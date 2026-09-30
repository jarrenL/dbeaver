import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {installChineseResources} from './install-zh-resources.mjs';

const root = path.resolve(import.meta.dirname, '../..');
test('GaussDB package editor extension labels resolve in English and Chinese', () => {
    const module = path.join(root, 'plugins/org.jkiss.dbeaver.ext.gaussdb.ui');
    const xml = fs.readFileSync(path.join(module, 'plugin.xml'), 'utf8');
    const keys = new Set([...xml.matchAll(/="%([\w.]+)"/g)].map(match => match[1]));
    assert(keys.has('db.editor.source.description'));
    for (const locale of ['', '_zh']) {
        const properties = fs.readFileSync(path.join(module, `OSGI-INF/l10n/bundle${locale}.properties`), 'utf8');
        const defined = new Set([...properties.matchAll(/^([^#!\s=]+)\s*=\s*\S/gm)].map(match => match[1]));
        for (const key of keys) assert(defined.has(key), `${locale}: missing extension resource ${key}`);
    }
});
for (const [module, packagePath, messages, resources] of [
    ['org.jkiss.dbeaver.ext.gaussdb.debug.ui', 'org/jkiss/dbeaver/ext/gaussdb/debug/ui/internal', 'GaussDBDebugMessages', 'GaussDBDebugMessages'],
    ['org.jkiss.dbeaver.ext.gaussdb.ui', 'org/jkiss/dbeaver/ext/gaussdb/ui/internal', 'GaussDBMessages', 'GaussDBResources'],
    ['org.jkiss.dbeaver.ui.app.config', 'org/jkiss/dbeaver/ui/app/config/nls', 'ProductConfigMessages', 'ProductConfigMessages']
]) {
    test(`${module}: every NLS field has English and Chinese resources`, () => {
        const dir = path.join(root, 'plugins', module, 'src', packagePath);
        const fields = [...fs.readFileSync(path.join(dir, `${messages}.java`), 'utf8')
            .matchAll(/public static String (\w+);/g)].map(m => m[1]);
        for (const locale of ['', '_zh']) {
            const text = fs.readFileSync(path.join(dir, `${resources}${locale}.properties`), 'utf8');
            for (const field of fields) assert(new RegExp(`^${field}\\s*=\\s*\\S`, 'm').test(text), `${locale}: ${field}`);
        }
    });
}
test('Babel installs only existing hosts and can be applied twice without duplicate bundles', t => {
    const product = fs.mkdtempSync(path.join(os.tmpdir(), 'gaussdb-babel-test-'));
    t.after(() => fs.rmSync(product, {recursive: true, force: true}));
    fs.mkdirSync(path.join(product, 'plugins'));
    const config = path.join(product, 'configuration/org.eclipse.equinox.simpleconfigurator');
    fs.mkdirSync(config, {recursive: true});
    const info = path.join(config, 'bundles.info');
    fs.writeFileSync(info, ['org.eclipse.debug.ui', 'org.eclipse.ui.workbench', 'org.eclipse.jface']
        .map(id => `${id},1.0.0,plugins/${id}.jar,4,false`).join('\n') + '\n');
    assert.equal(installChineseResources(product), 3);
    const first = fs.readFileSync(info, 'utf8');
    assert.equal(installChineseResources(product), 3);
    assert.equal(fs.readFileSync(info, 'utf8'), first);
    assert.equal(fs.readdirSync(path.join(product, 'plugins')).length, 3);
});
