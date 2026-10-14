import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '../..');
const manual = fs.readFileSync(path.join(root, 'GAUSSDB_GUI_OPERATION_MANUAL_20260922.md'), 'utf8');
const wordManual = 'GaussDB_DBeaver_调试与Package操作手册_20261014.docx';
const manualImages = [...manual.matchAll(/!\[[^\]]*]\((docs\/gaussdb\/images\/[^)]+)\)/g)]
    .map(match => match[1]);

test('customer manual points to the current release and build', () => {
    assert.match(manual, /gaussdb-2615-20261014/);
    assert.match(manual, /202610140553/);
    assert.doesNotMatch(manual, /gaussdb-2615-20260922/);
});

test('current Word manual exists and obsolete release metadata is absent', () => {
    assert(fs.statSync(path.join(root, wordManual)).isFile());
    for (const source of [manual, fs.readFileSync(path.join(root, 'README.md'), 'utf8')]) {
        assert.doesNotMatch(source, /202610120248|gaussdb-2615-20261012|20261006\.docx/);
    }
});

test('customer manual documents both Kylin architectures without cross-architecture claims', () => {
    assert.match(manual, /KylinV10-x86_64/);
    assert.match(manual, /KylinV10-aarch64/);
    assert.match(manual, /Windows ARM64、macOS 和 LoongArch 安装包不在本次发布范围/);
});

test('every image referenced by the customer manual exists in source', () => {
    assert.deepEqual(manualImages, [
        'docs/gaussdb/images/manual/language-config.png',
        'docs/gaussdb/images/manual/debug-source.png',
        'docs/gaussdb/images/manual/debug-step-variables.png',
        'docs/gaussdb/images/manual/call-stack.png',
        'docs/gaussdb/images/manual/debug-transaction-choice.png',
        'docs/gaussdb/images/manual/package-compile-menu.png',
        'docs/gaussdb/images/manual/package-errors.png',
        'docs/gaussdb/images/manual/package-error-location.png',
        'docs/gaussdb/images/manual/package-delete-confirm.png',
    ]);
    for (const image of manualImages) assert(fs.statSync(path.join(root, image)).isFile(), image);
});

test('Linux and Windows assemblers package every referenced manual image', () => {
    for (const assembler of ['product/community/kylin/assemble.sh', 'product/community/windows/assemble.mjs']) {
        const source = fs.readFileSync(path.join(root, assembler), 'utf8');
        assert.match(source, /docs\/gaussdb\/images/, assembler);
        for (const image of manualImages) assert.match(source, new RegExp(path.basename(image).replace('.', '\\.')), `${assembler}: ${image}`);
    }
});
