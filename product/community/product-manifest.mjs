// Record and verify every application file, executable bit and symbolic link.
// Store the manifest outside the application so it does not hash itself.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { verifyCleanProduct } from './verify-clean-product.mjs';

export function inventory(root) {
    const entries = [];
    function walk(directory, prefix) {
        for (const name of fs.readdirSync(directory).sort()) {
            const full = path.join(directory, name);
            const relative = prefix ? `${prefix}/${name}` : name;
            const stat = fs.lstatSync(full);
            if (stat.isSymbolicLink()) {
                entries.push({ path: relative, type: 'link', target: fs.readlinkSync(full) });
            } else if (stat.isDirectory()) {
                entries.push({ path: relative, type: 'directory' });
                walk(full, relative);
            } else if (stat.isFile()) {
                entries.push({ path: relative, type: 'file', size: stat.size,
                    executable: (stat.mode & 0o111) !== 0,
                    sha256: crypto.createHash('sha256').update(fs.readFileSync(full)).digest('hex') });
            } else {
                throw new Error(`Unsupported file type: ${relative}`);
            }
        }
    }
    walk(root, '');
    return { format: 1, entries };
}

export function verifyInventory(root, manifest) {
    const actual = inventory(root);
    if (JSON.stringify(actual) !== JSON.stringify(manifest)) {
        throw new Error('Application inventory differs: files, bytes, executable bits or link targets changed');
    }
    return actual.entries.length;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [mode, root, output, ...extra] = process.argv.slice(2);
    if (!['create', 'verify'].includes(mode) || !root || !output || extra.length) {
        throw new Error('Usage: product-manifest.mjs create|verify APPLICATION EXTERNAL_MANIFEST.json');
    }
    const realRoot = fs.realpathSync(root);
    const resolvedOutput = path.join(fs.realpathSync(path.dirname(path.resolve(output))), path.basename(output));
    const relative = path.relative(realRoot, resolvedOutput);
    if (relative === '' || (!relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative))) {
        throw new Error('Manifest must be outside the application');
    }
    verifyCleanProduct(realRoot, { assembled: true });
    if (mode === 'create') {
        fs.writeFileSync(output, `${JSON.stringify(inventory(realRoot), null, 2)}\n`, { flag: 'wx' });
        console.log('Created application inventory; not a signature or functional acceptance result');
    } else {
        console.log(`Verified ${verifyInventory(realRoot, JSON.parse(fs.readFileSync(output, 'utf8')))} entries`);
    }
}
