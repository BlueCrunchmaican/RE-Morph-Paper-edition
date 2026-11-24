/* RE:Morph multimorph grid: the parts that don't touch the page (names, issue parsing, labels, URLs).
   Works in a browser (window.ComboLogic) and in node (require), so it can be tested without a browser. */
(function (root, factory) {
	if (typeof module === 'object' && module.exports) module.exports = factory();
	else root.ComboLogic = factory();
})(typeof self !== 'undefined' ? self : this, function () {
	'use strict';

	var REPO = 'BlueCrunchmaican/RE-Morph-Paper-edition';
	var TEMPLATE = 'multimorph-combo.yml';
	var ISSUE_NEW = 'https://github.com/' + REPO + '/issues/new';
	var API_ISSUES = 'https://api.github.com/repos/' + REPO + '/issues';
	var ISSUE_LINK_PREFIX = 'https://github.com/' + REPO + '/';

	// Order matches the statuses in data.json (the exporter's order), then the one the page adds itself.
	var IDS = ['works', 'sweep', 'error', 'fixed', 'look', 'unclear', 'none'];
	var LABELS = {
		works: 'works',
		sweep: 'works (sweep)',
		error: 'error',
		fixed: 'fixed, needs a retest',
		look: 'needs a look',
		unclear: 'unclear',
		none: 'not tested yet',
		reported: 'reported'
	};
	// Which one wins when a pair has several open reports.
	var OVERLAY_RANK = { error: 4, reported: 3, look: 2, fixed: 1 };

	var PLAYER_ALIASES = { player: 1, ownbody: 1, own: 1, me: 1, myself: 1, myownbody: 1, self: 1, yourownbody: 1, ownplayer: 1 };

	/** Lower-case letters only, so "Wither Skeleton", "wither_skeleton" and "wither-skeleton" are the same name. */
	function nameKey(s) {
		return String(s == null ? '' : s).toLowerCase().replace(/[^a-z]/g, '');
	}

	/** forms: the data.json list ({id, name}). Returns a Map of name key to form id. */
	function buildIndex(forms) {
		var m = new Map();
		forms.forEach(function (f) {
			m.set(nameKey(f.id), f.id);
			m.set(nameKey(f.name), f.id);
		});
		var player = forms.filter(function (f) { return f.id === 'player'; })[0];
		if (player) Object.keys(PLAYER_ALIASES).forEach(function (a) { m.set(a, 'player'); });
		return m;
	}

	/** Turns whatever someone typed into a form id, or null. */
	function resolveForm(text, index) {
		var s = String(text == null ? '' : text).trim().toLowerCase();
		if (!s || s === '_no response_') return null;
		s = s.replace(/\(.*?\)/g, ' ').replace(/^minecraft:/, '');
		var k = nameKey(s);
		if (!k) return null;
		if (index.has(k)) return index.get(k);
		var noBaby = k.replace(/^baby/, '');
		if (noBaby !== k && index.has(noBaby)) return index.get(noBaby);
		if (k.charAt(k.length - 1) === 's' && index.has(k.slice(0, -1))) return index.get(k.slice(0, -1));
		return null;
	}

	/** The two answers an issue form leaves in the body: "### Main body" then the text, and the same for the borrowed form. */
	function bodyFields(body) {
		var text = String(body || '');
		var main = /###\s*Main body\s*\r?\n+([^\r\n]*)/i.exec(text);
		var borrowed = /###\s*Borrowed form\s*\r?\n+([^\r\n]*)/i.exec(text);
		return { main: main ? main[1].trim() : '', borrowed: borrowed ? borrowed[1].trim() : '' };
	}

	/** "[Multimorph] zombie + bat: head sits high" gives ["zombie", "bat"]. */
	function titleFields(title) {
		var m = /\[multimorph\]\s*([^+:]+?)\s*\+\s*([^:]+?)\s*(?::|$)/i.exec(String(title || ''));
		return m ? { main: m[1].trim(), borrowed: m[2].trim() } : { main: '', borrowed: '' };
	}

	/** A slim issue ({t: title, m: main answer, b: borrowed answer}) to {main, borrowed} form ids, or null. */
	function parsePair(issue, index) {
		var a = resolveForm(issue.m, index), b = resolveForm(issue.b, index);
		if (a && b && a !== b) return { main: a, borrowed: b };
		var t = titleFields(issue.t);
		a = resolveForm(t.main, index); b = resolveForm(t.borrowed, index);
		if (a && b && a !== b) return { main: a, borrowed: b };
		return null;
	}

	/** The triage labels an issue carries, as a status id: fixed beats bug, bug beats needs look, no label is "reported". */
	function statusFromLabels(labelNames) {
		var names = (labelNames || []).map(function (n) { return String(n).trim().toLowerCase(); });
		function has() {
			for (var i = 0; i < arguments.length; i++) if (names.indexOf(arguments[i]) >= 0) return true;
			return false;
		}
		if (has('fixed')) return 'fixed';
		if (has('bug', 'error')) return 'error';
		if (has('needs look', 'needs a look', 'needs-look')) return 'look';
		return 'reported';
	}

	/** Cuts a GitHub API issue down to what the page keeps (and caches). Pull requests give null. */
	function slimIssue(raw) {
		if (!raw || raw.pull_request || typeof raw.number !== 'number') return null;
		var f = bodyFields(raw.body);
		return {
			n: raw.number,
			t: String(raw.title || '').slice(0, 200),
			u: String(raw.html_url || ''),
			s: raw.state === 'closed' ? 'closed' : 'open',
			r: raw.state_reason || '',
			l: (raw.labels || []).map(function (x) { return typeof x === 'string' ? x : x && x.name; }).filter(Boolean),
			m: f.main,
			b: f.borrowed
		};
	}

	/** Only links that stay on this repository are ever put on the page. */
	function safeIssueUrl(u) {
		return typeof u === 'string' && u.indexOf(ISSUE_LINK_PREFIX) === 0 && !/[\s<>"]/.test(u) ? u : '';
	}

	/**
	 * Slim issues to a per-pair overlay.
	 * Returns {pairs: {"main|borrowed": {status, open: [issue], closed: [issue]}}, unplaced: n}.
	 * Open reports set the status; closed ones (completed or not planned) leave the export's status alone.
	 */
	function overlay(issues, index) {
		var pairs = {}, unplaced = 0;
		issues.forEach(function (is) {
			var p = parsePair(is, index);
			if (!p) { if (is.s === 'open') unplaced++; return; }
			var key = p.main + '|' + p.borrowed;
			var e = pairs[key] || (pairs[key] = { status: null, open: [], closed: [] });
			if (is.s === 'open') {
				e.open.push(is);
				var st = statusFromLabels(is.l);
				if (!e.status || OVERLAY_RANK[st] > OVERLAY_RANK[e.status]) e.status = st;
			} else {
				e.closed.push(is);
			}
		});
		return { pairs: pairs, unplaced: unplaced };
	}

	function enc(s) { return encodeURIComponent(s); }

	/** The new-issue link for the issue form, with the pair and the grid's status filled in. */
	function buildIssueUrl(mainName, borrowedName, statusLabel) {
		return ISSUE_NEW + '?template=' + enc(TEMPLATE) +
			'&title=' + enc('[Multimorph] ' + mainName + ' + ' + borrowedName + ': ') +
			'&main=' + enc(mainName) +
			'&borrowed=' + enc(borrowedName) +
			'&grid_status=' + enc(statusLabel || '');
	}

	return {
		REPO: REPO, TEMPLATE: TEMPLATE, ISSUE_NEW: ISSUE_NEW, API_ISSUES: API_ISSUES,
		IDS: IDS, LABELS: LABELS, OVERLAY_RANK: OVERLAY_RANK,
		nameKey: nameKey, buildIndex: buildIndex, resolveForm: resolveForm,
		bodyFields: bodyFields, titleFields: titleFields, parsePair: parsePair,
		statusFromLabels: statusFromLabels, slimIssue: slimIssue, safeIssueUrl: safeIssueUrl,
		overlay: overlay, buildIssueUrl: buildIssueUrl
	};
});
