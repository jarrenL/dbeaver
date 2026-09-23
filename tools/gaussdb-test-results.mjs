// Export only test identities/outcomes, never Surefire properties, environment or JDBC logs.
// Usage: node tools/gaussdb-test-results.mjs OUTPUT_JSON [SINCE_ISO_TIMESTAMP]
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const root = path.resolve(import.meta.dirname, '..');
const output = process.argv[2];
assert(output, 'Specify an output JSON file');
const since = process.argv[3] === undefined ? null : Date.parse(process.argv[3]);
assert(since === null || Number.isFinite(since), 'Invalid report start timestamp');
const modules = ['org.jkiss.dbeaver.test.platform', 'org.jkiss.dbeaver.ext.gaussdb.test',
    'org.jkiss.dbeaver.ext.gaussdb.debug.test', 'org.jkiss.dbeaver.ext.postgresql.test'];
const decode = s => s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>').replace(/&amp;/g, '&');
const attributes = s => Object.fromEntries([...s.matchAll(/([\w.-]+)="([^"]*)"/g)].map(m => [m[1], decode(m[2])]));
const cases = [], totals = {tests: 0, passed: 0, skipped: 0, failures: 0, errors: 0};
const reports = [];
for (const module of modules) {
    const directory = path.join(root, 'test', module, 'target/surefire-reports');
    for (const filename of fs.readdirSync(directory).filter(n => /^TEST-.*\.xml$/.test(n)).sort()) {
        const file = path.join(directory, filename);
        if (since !== null && fs.statSync(file).mtimeMs < since) continue;
        const xml = fs.readFileSync(file, 'utf8');
        const suite = attributes(xml.match(/<testsuite\b([^>]*)>/)[1]);
        const items = [...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)];
        assert.equal(items.length, Number(suite.tests), `Unparsed test cases in ${filename}`);
        const counts = {tests: items.length, passed: 0, skipped: 0, failures: 0, errors: 0};
        for (const [, header, body = ''] of items) {
            const a = attributes(header);
            const outcome = /<error\b/.test(body) ? 'errors' : /<failure\b/.test(body) ? 'failures'
                : /<skipped\b/.test(body) ? 'skipped' : 'passed';
            counts[outcome]++;
            cases.push({module, class: a.classname, name: a.name, outcome});
        }
        for (const key of ['skipped', 'failures', 'errors']) {
            assert.equal(counts[key], Number(suite[key] || 0), `${key} mismatch: ${filename}`);
        }
        for (const key of Object.keys(totals)) totals[key] += counts[key];
        reports.push({file: path.relative(root, file), modified: fs.statSync(file).mtime.toISOString(), ...counts});
    }
}
const identities = cases.map(c => `${c.module}/${c.class}/${c.name}`);
assert(reports.length > 0, 'No matching test reports');
assert.equal(new Set(identities).size, identities.length, 'Duplicate test identities');
fs.mkdirSync(path.dirname(path.resolve(output)), {recursive: true});
fs.writeFileSync(output, JSON.stringify({
    generatedAt: new Date().toISOString(),
    since: since === null ? null : new Date(since).toISOString(),
    scope: 'Current local JUnit reports; not a historical TPDSS equivalence or GUI coverage claim',
    totals, reports, cases
}, null, 2) + '\n');
console.log(JSON.stringify(totals));
