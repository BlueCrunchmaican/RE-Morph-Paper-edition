// Checks the grid page's logic (docs/combos/logic.js) without a browser.
// Run with: node tools/grid-test/logic.test.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const L = require('../../docs/combos/logic.js');
const data = JSON.parse(fs.readFileSync(path.join(__dirname, '../../docs/combos/data.json'), 'utf8'));

let passed = 0;
function t(name, fn) { fn(); passed++; console.log('ok  ' + name); }

const index = L.buildIndex(data.forms);

t('data.json has 91 forms and 8,190 pairs', () => {
	assert.strictEqual(data.forms.length, 91);
	assert.strictEqual(data.grid.length, 91);
	let pairs = 0;
	data.grid.forEach((row, r) => {
		assert.strictEqual(row.length, 91);
		for (let c = 0; c < 91; c++) {
			const ch = row.charAt(c);
			if (r === c) assert.strictEqual(ch, '-'); else { assert.ok(/[0-6]/.test(ch), ch); pairs++; }
		}
	});
	assert.strictEqual(pairs, 8190);
	assert.strictEqual(data.ver.length, 91);
	assert.strictEqual(data.seen.length, 91);
	const sum = data.statuses.reduce((a, s) => a + s.count, 0);
	assert.strictEqual(sum, 8190);
});

t('every form id resolves to itself, by id and by name', () => {
	data.forms.forEach(f => {
		assert.strictEqual(L.resolveForm(f.id, index), f.id);
		assert.strictEqual(L.resolveForm(f.name, index), f.id);
	});
});

t('underscores, dashes, case, spaces and minecraft: prefixes all work', () => {
	assert.strictEqual(L.resolveForm('wither_skeleton', index), 'wither_skeleton');
	assert.strictEqual(L.resolveForm('Wither Skeleton', index), 'wither_skeleton');
	assert.strictEqual(L.resolveForm('wither-skeleton', index), 'wither_skeleton');
	assert.strictEqual(L.resolveForm('minecraft:ender_dragon', index), 'ender_dragon');
	assert.strictEqual(L.resolveForm('Zombified Piglin', index), 'zombified_piglin');
	assert.strictEqual(L.resolveForm('baby husk', index), 'husk');
	assert.strictEqual(L.resolveForm('cows', index), 'cow');
	assert.strictEqual(L.resolveForm('sheep (blue, baby)', index), 'sheep');
	assert.strictEqual(L.resolveForm('', index), null);
	assert.strictEqual(L.resolveForm('_No response_', index), null);
	assert.strictEqual(L.resolveForm('not a mob', index), null);
});

t('"own body" and its spellings mean the player form', () => {
	['own body', 'Own Body', 'player', 'my own body', 'me', 'own_body'].forEach(s => assert.strictEqual(L.resolveForm(s, index), 'player', s));
});

t('issue form body gives the two answers', () => {
	const body = '### Main body\n\nhorse\n\n### Borrowed form\n\nphantom\n\n### What\'s wrong?\n\n- [x] Wrong size\n';
	assert.deepStrictEqual(L.bodyFields(body), { main: 'horse', borrowed: 'phantom' });
	const crlf = '### Main body\r\n\r\nwither skeleton\r\n\r\n### Borrowed form\r\n\r\nown body\r\n';
	assert.deepStrictEqual(L.bodyFields(crlf), { main: 'wither skeleton', borrowed: 'own body' });
	assert.deepStrictEqual(L.bodyFields(null), { main: '', borrowed: '' });
});

t('issue titles give the pair', () => {
	assert.deepStrictEqual(L.titleFields('[Multimorph] cow + frog: tongue is off'), { main: 'cow', borrowed: 'frog' });
	assert.deepStrictEqual(L.titleFields('[multimorph] wither_skeleton + own body'), { main: 'wither_skeleton', borrowed: 'own body' });
	assert.deepStrictEqual(L.titleFields('something else'), { main: '', borrowed: '' });
});

t('parsePair prefers the body and falls back to the title', () => {
	assert.deepStrictEqual(L.parsePair({ t: '[Multimorph] cow + frog: x', m: 'horse', b: 'phantom' }, index), { main: 'horse', borrowed: 'phantom' });
	assert.deepStrictEqual(L.parsePair({ t: '[Multimorph] cow + frog: x', m: '', b: '' }, index), { main: 'cow', borrowed: 'frog' });
	assert.deepStrictEqual(L.parsePair({ t: '[Multimorph] cow + frog: x', m: 'nonsense', b: 'phantom' }, index), { main: 'cow', borrowed: 'frog' });
	assert.strictEqual(L.parsePair({ t: 'Bug report', m: '', b: '' }, index), null);
	assert.strictEqual(L.parsePair({ t: '', m: 'cow', b: 'cow' }, index), null);
});

t('labels map to statuses (fixed beats bug beats needs look, none is reported)', () => {
	assert.strictEqual(L.statusFromLabels(['multimorph']), 'reported');
	assert.strictEqual(L.statusFromLabels(['multimorph', 'bug']), 'error');
	assert.strictEqual(L.statusFromLabels(['multimorph', 'needs look']), 'look');
	assert.strictEqual(L.statusFromLabels(['multimorph', 'Needs Look']), 'look');
	assert.strictEqual(L.statusFromLabels(['multimorph', 'fixed']), 'fixed');
	assert.strictEqual(L.statusFromLabels(['multimorph', 'bug', 'fixed']), 'fixed');
	assert.strictEqual(L.statusFromLabels(['multimorph', 'bug', 'needs look']), 'error');
	assert.strictEqual(L.statusFromLabels([]), 'reported');
	assert.strictEqual(L.statusFromLabels(undefined), 'reported');
});

t('slimIssue keeps what is needed, drops pull requests', () => {
	const raw = { number: 7, title: '[Multimorph] cow + frog: x', html_url: 'https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/issues/7', state: 'open',
		body: '### Main body\n\ncow\n\n### Borrowed form\n\nfrog\n', labels: [{ name: 'multimorph' }, { name: 'bug' }, 'needs look'] };
	assert.deepStrictEqual(L.slimIssue(raw), { n: 7, t: raw.title, u: raw.html_url, s: 'open', r: '', l: ['multimorph', 'bug', 'needs look'], m: 'cow', b: 'frog' });
	assert.strictEqual(L.slimIssue({ number: 8, pull_request: {} }), null);
	assert.strictEqual(L.slimIssue(null), null);
});

t('overlay: open reports set the status, closed ones leave the export alone, the worst open one wins', () => {
	const mk = (n, state, labels, main, borrowed, r) => ({ n, t: '[Multimorph] x + y: z', u: 'https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/issues/' + n, s: state, r: r || '', l: labels, m: main, b: borrowed });
	const issues = [
		mk(1, 'open', ['multimorph'], 'cow', 'frog'),
		mk(2, 'open', ['multimorph', 'bug'], 'cow', 'frog'),
		mk(3, 'closed', ['multimorph', 'bug'], 'zombie', 'bat', 'completed'),
		mk(4, 'open', ['multimorph', 'needs look'], 'horse', 'phantom'),
		mk(5, 'open', ['multimorph', 'fixed'], 'pig', 'bat'),
		mk(6, 'open', ['multimorph'], 'unknown thing', 'bat'),
		mk(7, 'closed', ['multimorph'], 'unknown thing', 'bat', 'not_planned'),
		mk(8, 'open', ['multimorph'], 'wither skeleton', 'own body')
	];
	const o = L.overlay(issues, index);
	assert.strictEqual(o.pairs['cow|frog'].status, 'error');
	assert.strictEqual(o.pairs['cow|frog'].open.length, 2);
	assert.strictEqual(o.pairs['zombie|bat'].status, null);
	assert.strictEqual(o.pairs['zombie|bat'].closed.length, 1);
	assert.strictEqual(o.pairs['horse|phantom'].status, 'look');
	assert.strictEqual(o.pairs['pig|bat'].status, 'fixed');
	assert.strictEqual(o.pairs['wither_skeleton|player'].status, 'reported');
	assert.strictEqual(o.unplaced, 1);
});

t('issue links stay on this repository', () => {
	assert.strictEqual(L.safeIssueUrl('https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/issues/3'), 'https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/issues/3');
	assert.strictEqual(L.safeIssueUrl('https://evil.example/issues/3'), '');
	assert.strictEqual(L.safeIssueUrl('javascript:alert(1)'), '');
	assert.strictEqual(L.safeIssueUrl('https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/issues/3" onclick="x'), '');
});

t('new-issue links are encoded and carry the issue form ids', () => {
	const u = new URL(L.buildIssueUrl('wither skeleton', 'own body', 'needs a look'));
	assert.strictEqual(u.origin + u.pathname, 'https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/issues/new');
	assert.strictEqual(u.searchParams.get('template'), 'multimorph-combo.yml');
	assert.strictEqual(u.searchParams.get('title'), '[Multimorph] wither skeleton + own body: ');
	assert.strictEqual(u.searchParams.get('main'), 'wither skeleton');
	assert.strictEqual(u.searchParams.get('borrowed'), 'own body');
	assert.strictEqual(u.searchParams.get('grid_status'), 'needs a look');
	const raw = L.buildIssueUrl('cave_spider', 'fish & chips + "x"', 'works');
	assert.ok(!/ /.test(raw) && !/"/.test(raw), raw);
	const v = new URL(raw);
	assert.strictEqual(v.searchParams.get('main'), 'cave_spider');
	assert.strictEqual(v.searchParams.get('borrowed'), 'fish & chips + "x"');
});

t('names in data.json round-trip through a new-issue link and back to the same form', () => {
	data.forms.forEach(a => {
		const u = new URL(L.buildIssueUrl(a.name, 'bat', 'works'));
		assert.strictEqual(L.resolveForm(u.searchParams.get('main'), index), a.id);
		assert.deepStrictEqual(L.titleFields(u.searchParams.get('title')), { main: a.name, borrowed: 'bat' });
	});
});

t('the issue form ids the links rely on exist in the template', () => {
	const yml = fs.readFileSync(path.join(__dirname, '../../.github/ISSUE_TEMPLATE/multimorph-combo.yml'), 'utf8');
	['main', 'borrowed', 'grid_status'].forEach(id => assert.ok(new RegExp('^\\s+id: ' + id + '$', 'm').test(yml), id));
	assert.ok(/^labels: \["multimorph"\]$/m.test(yml));
});

console.log('\n' + passed + ' checks passed');
