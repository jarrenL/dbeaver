// Install pinned, resource-only Eclipse Babel fragments for hosts present in this product.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

export function installChineseResources(product) {
    const archive = path.join(import.meta.dirname, 'l10n/BabelLanguagePack-eclipse-zh.zip');
    assert.equal(createHash('sha256').update(fs.readFileSync(archive)).digest('hex'),
        'c330aeacbeefbf94687c0af3b4c33ab24ee6bae34d7f6bb92b70a6d9f6099764', 'Babel archive checksum mismatch');
    const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'gaussdb-babel-'));
    try {
        execFileSync('unzip', ['-q', archive, '-d', temporary]);
        const infoFile = path.join(product, 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info');
        let info = fs.readFileSync(infoFile, 'utf8');
        const installed = new Set(info.split(/\r?\n/).filter(l => l && !l.startsWith('#')).map(l => l.split(',')[0]));
        const plugins = path.join(temporary, 'eclipse/plugins');
        let count = 0;
        for (const name of fs.readdirSync(plugins).sort()) {
            if (!name.endsWith('.jar')) continue;
            const jar = path.join(plugins, name);
            const manifest = execFileSync('unzip', ['-p', jar, 'META-INF/MANIFEST.MF'], {encoding: 'utf8'})
                .replace(/\r?\n /g, '');
            const field = key => new RegExp(`^${key}: (.+)$`, 'm').exec(manifest)?.[1].trim();
            const host = field('Fragment-Host');
            if (!host || !installed.has(host.split(';')[0].trim())) continue;
            // This pinned resource pack uses unconstrained host declarations. Fail on future changes.
            assert(!host.includes(';'), `Review fragment host compatibility: ${host}`);
            const id = field('Bundle-SymbolicName').split(';')[0].trim();
            const version = field('Bundle-Version');
            assert(id.endsWith('.nl_zh') && version, `Unexpected language fragment ${name}`);
            const entries = execFileSync('unzip', ['-Z1', jar], {encoding: 'utf8'});
            assert(!/\.class$/m.test(entries), `Language archive must contain resources only: ${name}`);
            fs.copyFileSync(jar, path.join(product, 'plugins', name));
            if (!installed.has(id)) {
                info = info.trimEnd() + `\n${id},${version},plugins/${name},4,false\n`;
                installed.add(id);
            }
            count++;
        }
        for (const host of ['org.eclipse.debug.ui', 'org.eclipse.ui.workbench', 'org.eclipse.jface']) {
            assert(installed.has(`${host}.nl_zh`), `Missing required Chinese fragment: ${host}`);
        }
        fs.writeFileSync(infoFile, info);
        const licenseDir = path.join(product, 'licenses/eclipse-babel');
        const featureDir = path.join(temporary, 'eclipse/features');
        fs.mkdirSync(licenseDir, {recursive: true});
        for (const feature of fs.readdirSync(featureDir)) {
            fs.cpSync(path.join(featureDir, feature), path.join(licenseDir, feature), {recursive: true});
        }
        fs.copyFileSync(path.join(import.meta.dirname, 'l10n/README.md'), path.join(licenseDir, 'SOURCE.md'));
        console.log(`Installed ${count} Chinese resource fragments for existing product hosts`);
        return count;
    } finally {
        fs.rmSync(temporary, {recursive: true, force: true});
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    assert(process.argv[2], 'Expected product directory');
    installChineseResources(process.argv[2]);
}
