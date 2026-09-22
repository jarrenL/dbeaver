// Select the DBeaver launcher, which reads settings/global-settings.ini before OSGi starts.
// The default Equinox launcher does not load the language selected in the product UI.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';

export function configureLauncher(product) {
    const launchers = fs.readdirSync(path.join(product, 'plugins'))
        .filter(name => /^org\.jkiss\.dbeaver\.launcher_[^/]+\.jar$/.test(name));
    assert.equal(launchers.length, 1, 'Expected exactly one DBeaver launcher');
    const iniFile = path.join(product, 'dbeaver.ini');
    const ini = fs.readFileSync(iniFile, 'utf8');
    assert.equal((ini.match(/^-startup\r?$/gm) ?? []).length, 1, 'Missing/duplicate startup option');
    const startup = `plugins/${launchers[0]}`;
    const updated = ini.replace(/(^-startup\r?\n)[^\r\n]+/m, `$1${startup}`);
    assert(!/^-nl\r?$/m.test(updated), 'Do not override the user language in the package');
    fs.writeFileSync(iniFile, updated);
    return startup;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    assert(process.argv[2], 'Expected product directory');
    console.log(`Configured startup: ${configureLauncher(process.argv[2])}`);
}
