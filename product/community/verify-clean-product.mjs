import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';

/** Source-product gate, before adding JRE/native components. Never scan secret contents. */
export function verifyCleanProduct(root) {
    const rejected = [];
    let files = 0;
    function walk(directory) {
        for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
            const absolute = path.join(directory, entry.name);
            const relative = path.relative(root, absolute);
            if (entry.isSymbolicLink()) { rejected.push(relative + ' (symlink)'); continue; }
            if (/^(\.metadata|workspace\d*|queue)$/i.test(entry.name)
                || /^(data-sources\.json|credentials-config\.json|\.dbeaver-data-sources\.xml)$/i.test(entry.name)
                || /\.(launch|cmd\.result|cmd\.done)$/i.test(entry.name)
                || /^gsjdbc[^/]*\.jar$/i.test(entry.name)
                || /swtbot|gaussdb[.-]acceptance|org\.jkiss\..*\.test_/i.test(entry.name)) {
                rejected.push(relative);
                continue;
            }
            if (entry.isDirectory()) walk(absolute);
            else if (entry.isFile()) files++;
        }
    }
    assert(fs.statSync(root).isDirectory(), 'Expected a product directory');
    walk(root);
    const ini = fs.readFileSync(path.join(root, 'dbeaver.ini'), 'utf8');
    if (/gaussdb\.swtbot\.queue|^-data\s*$|^-[a-z]*password(?:=|\s)/im.test(ini)) rejected.push('dbeaver.ini (test/user override)');
    const info = fs.readFileSync(path.join(root, 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'), 'utf8');
    if (/swtbot|gaussdb[.-]acceptance|org\.jkiss\.[^,\r\n]*\.test,/i.test(info)) rejected.push('bundles.info (test bundle registration)');
    assert.equal(rejected.length, 0, 'Refusing polluted product source: ' + rejected.join(', '));
    return {files, cleanSource: true, scope: 'Source hygiene only; not executable, license or GUI acceptance'};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try { assert(process.argv[2], 'Expected product directory'); console.log(JSON.stringify(verifyCleanProduct(process.argv[2]))); }
    catch (error) { console.error(error.message); process.exitCode = 1; }
}
