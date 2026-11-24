/* RE:Morph multimorph grid. Reads data.json (my last test results) and, when it can, the open multimorph issues from GitHub. */
(function () {
	'use strict';
	var L = window.ComboLogic;
	var IDS = L.IDS, LABELS = L.LABELS;
	var CACHE_KEY = 'morph-combos-issues-v1';
	var CACHE_TTL = 10 * 60 * 1000;
	var MAX_PAGES = 10;

	var $ = function (id) { return document.getElementById(id); };
	function el(tag, cls, text) {
		var e = document.createElement(tag);
		if (cls) e.className = cls;
		if (text != null) e.textContent = text;
		return e;
	}

	// ---------------------------------------------------------------- per-viewer conveniences (never required)

	function store(key, value) {
		try {
			if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value);
		} catch (e) { /* storage blocked: the page works without it */ }
	}
	function recall(key) {
		try { return localStorage.getItem(key); } catch (e) { return null; }
	}

	// ---------------------------------------------------------------- state

	var data, index;
	var forms = [], n = 0;
	var base = [];            // base[r][c]: status id from my last test
	var ovl = {};             // "main|borrowed": {status, open, closed}
	var cells = [];           // cells[r][c]
	var rowHeads = [], colHeads = [];
	var hiddenStatus = {};    // status id -> true while hidden
	var matched = null;       // Set of form indexes the search matches, or null
	var focusAt = { r: 0, c: 1 };
	var liveOk = false;

	function statusOf(r, c) {
		var o = ovl[forms[r].id + '|' + forms[c].id];
		return (o && o.status) || base[r][c];
	}
	function labelOf(r, c) {
		return forms[r].name + ' + ' + forms[c].name + ': ' + LABELS[statusOf(r, c)];
	}
	function statusText(id) {
		if (id === 'reported') return 'Someone has reported this pair and I haven’t gone through the report yet.';
		var i = IDS.indexOf(id);
		return (data.statuses[i] && data.statuses[i].text) || '';
	}

	// ---------------------------------------------------------------- building the grid

	function build() {
		forms = data.forms;
		n = forms.length;
		index = L.buildIndex(forms);
		for (var r = 0; r < n; r++) {
			base[r] = [];
			for (var c = 0; c < n; c++) {
				var ch = data.grid[r].charAt(c);
				base[r][c] = ch === '-' ? 'same' : IDS[+ch];
			}
		}
		var grid = $('grid');
		var frag = document.createDocumentFragment();
		var head = el('div', 'row head');
		head.setAttribute('role', 'row');
		var corner = el('div', 'corner', 'main body (row) / borrowed form (column)');
		corner.setAttribute('role', 'columnheader');
		head.appendChild(corner);
		forms.forEach(function (f, i) {
			var h = el('div', 'colhead', f.name);
			h.setAttribute('role', 'columnheader');
			h.title = 'borrowed: ' + f.name;
			colHeads[i] = h;
			head.appendChild(h);
		});
		frag.appendChild(head);
		for (var r2 = 0; r2 < n; r2++) {
			var row = el('div', 'row');
			row.setAttribute('role', 'row');
			var rh = el('div', 'rowhead', forms[r2].name);
			rh.setAttribute('role', 'rowheader');
			rh.title = 'main: ' + forms[r2].name;
			rowHeads[r2] = rh;
			row.appendChild(rh);
			cells[r2] = [];
			for (var c2 = 0; c2 < n; c2++) {
				var cell = el('div');
				cell.setAttribute('role', 'gridcell');
				cell.dataset.r = r2;
				cell.dataset.c = c2;
				cell.tabIndex = -1;
				if (r2 === c2) {
					cell.className = 'c same';
					cell.setAttribute('aria-label', forms[r2].name + ' + ' + forms[r2].name + ': the same form can’t be combined');
				} else {
					paintCell(cell, r2, c2);
				}
				cells[r2][c2] = cell;
				row.appendChild(cell);
			}
			frag.appendChild(row);
		}
		grid.appendChild(frag);
		cells[focusAt.r][focusAt.c].tabIndex = 0;
		$('loading').hidden = true;
		grid.hidden = false;
	}

	function paintCell(cell, r, c) {
		var s = statusOf(r, c);
		cell.className = 'c s-' + s;
		cell.setAttribute('aria-label', labelOf(r, c) + (ovl[forms[r].id + '|' + forms[c].id] && ovl[forms[r].id + '|' + forms[c].id].open.length ? ' (open report)' : ''));
	}

	function repaintOverlay() {
		for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) if (r !== c) paintCell(cells[r][c], r, c);
		applyFilter();
		drawLegend();
	}

	// ---------------------------------------------------------------- legend and filters

	function counts() {
		var k = {};
		for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) {
			if (r === c) continue;
			var s = statusOf(r, c);
			k[s] = (k[s] || 0) + 1;
		}
		return k;
	}

	function drawLegend() {
		var k = counts();
		var ul = $('legend');
		ul.textContent = '';
		var order = ['works', 'sweep', 'look', 'fixed', 'error', 'reported', 'unclear', 'none'];
		var always = { error: 1, fixed: 1, look: 1, reported: 1 };
		order.forEach(function (id) {
			var count = k[id] || 0;
			if (!count && !always[id]) return;
			var li = el('li');
			var b = el('button');
			b.type = 'button';
			b.setAttribute('aria-pressed', hiddenStatus[id] ? 'false' : 'true');
			b.dataset.status = id;
			b.title = statusText(id);
			b.appendChild(el('span', 'sw s-' + id));
			b.appendChild(document.createTextNode(LABELS[id]));
			b.appendChild(el('span', 'n', count.toLocaleString('en-US')));
			li.appendChild(b);
			ul.appendChild(li);
		});
		var same = el('li');
		var sb = el('span', 'hint', '');
		sb.style.display = 'inline-flex';
		sb.style.alignItems = 'center';
		sb.style.gap = '8px';
		sb.appendChild(el('span', 'sw same'));
		sb.appendChild(document.createTextNode('same form, can’t be combined'));
		same.appendChild(sb);
		ul.appendChild(same);
	}

	function applyFilter() {
		var role = $('role').value;
		for (var r = 0; r < n; r++) {
			for (var c = 0; c < n; c++) {
				if (r === c) continue;
				var show = !hiddenStatus[statusOf(r, c)];
				if (show && matched) {
					var mr = matched.has(r), mc = matched.has(c);
					show = role === 'main' ? mr : role === 'borrowed' ? mc : (mr || mc);
				}
				cells[r][c].classList.toggle('dim', !show);
			}
		}
	}

	function onSearch(scroll) {
		var q = $('search').value.trim();
		var note = $('find-note');
		if (!q) {
			matched = null; note.hidden = true; applyFilter(); return;
		}
		var key = L.nameKey(q), low = q.toLowerCase();
		matched = new Set();
		var alias = L.resolveForm(q, index);
		forms.forEach(function (f, i) {
			if (L.nameKey(f.name).indexOf(key) >= 0 || f.name.toLowerCase().indexOf(low) >= 0 || (alias && alias === f.id)) matched.add(i);
		});
		note.hidden = false;
		note.textContent = matched.size ? matched.size + (matched.size === 1 ? ' form matches.' : ' forms match.') : 'No form matches “' + q + '”.';
		applyFilter();
		if (scroll && matched.size) {
			var first = Math.min.apply(null, Array.from(matched));
			var frame = $('frame'), cell = frame.querySelector('.c').offsetWidth;
			var role = $('role').value;
			if (role === 'borrowed') frame.scrollTo({ left: first * cell, top: 0 });
			else frame.scrollTo({ top: first * cell, left: 0 });
		}
	}

	function resetFilters() {
		hiddenStatus = {};
		$('search').value = '';
		$('role').value = 'both';
		matched = null;
		$('find-note').hidden = true;
		applyFilter();
		drawLegend();
	}

	// ---------------------------------------------------------------- tooltip

	var tip = $('tip'), hlRow = -1, hlCol = -1;
	function highlight(r, c) {
		if (hlRow >= 0) { rowHeads[hlRow].classList.remove('hl'); colHeads[hlCol].classList.remove('hl'); }
		hlRow = r; hlCol = c;
		if (r >= 0) { rowHeads[r].classList.add('hl'); colHeads[c].classList.add('hl'); }
	}
	function showTip(cell, x, y) {
		var r = +cell.dataset.r, c = +cell.dataset.c;
		tip.textContent = r === c ? forms[r].name + ': can’t be combined with itself' : labelOf(r, c) + (cell.classList.contains('dim') ? '' : ' (click to open)');
		tip.hidden = false;
		var w = tip.offsetWidth, h = tip.offsetHeight;
		var left = Math.min(Math.max(8, x + 12), window.innerWidth - w - 8);
		var top = y + 18 + h > window.innerHeight ? y - h - 12 : y + 18;
		tip.style.left = left + 'px';
		tip.style.top = Math.max(8, top) + 'px';
		highlight(r, c);
	}
	function hideTip() { tip.hidden = true; highlight(-1, -1); }

	// ---------------------------------------------------------------- the detail dialog

	var dlg = $('detail'), lastOpened = null;

	function openPair(r, c) {
		if (r === c) return;
		var key = forms[r].id + '|' + forms[c].id;
		var s = statusOf(r, c), o = ovl[key];
		$('d-title').textContent = forms[r].name + ' + ' + forms[c].name;
		var chip = $('d-chip');
		chip.textContent = '';
		chip.appendChild(el('span', 'sw s-' + s));
		chip.appendChild(document.createTextNode(LABELS[s]));
		$('d-text').textContent = 'Main body: ' + forms[r].name + '. Borrowed form: ' + forms[c].name + '. ' + statusText(s);
		var note = $('d-note');
		var text = data.notes[key];
		note.hidden = !text;
		note.textContent = text ? text : '';
		var meta = [];
		var v = data.ver[r].charAt(c);
		if (base[r][c] !== 'none' && v !== '-') meta.push('Last tested on ' + data.versions[parseInt(v, 36)] + '.');
		var bits = parseInt(data.seen[r].charAt(c), 16), seen = [];
		Object.keys(data.with).forEach(function (b) { if (bits & +b) seen.push(data.with[b]); });
		if (seen.length) meta.push('Checked: ' + seen.join(', ') + '.');
		$('d-meta').textContent = meta.join(' ');
		var list = $('d-list');
		list.textContent = '';
		var open = (o && o.open) || [], closed = (o && o.closed) || [];
		$('d-reports').hidden = !(open.length || closed.length);
		$('d-reports-h').textContent = open.length ? 'Open reports for this pair' : 'Closed reports for this pair';
		open.concat(closed).forEach(function (is) {
			var url = L.safeIssueUrl(is.u);
			if (!url) return;
			var li = el('li');
			var a = el('a', null, '#' + is.n + ' ' + (is.t || 'report'));
			a.href = url; a.target = '_blank'; a.rel = 'noopener noreferrer';
			li.appendChild(a);
			li.appendChild(el('span', 'st', is.s === 'open' ? 'open' : 'closed'));
			list.appendChild(li);
		});
		var btn = $('d-new');
		btn.href = L.buildIssueUrl(forms[r].name, forms[c].name, LABELS[s]);
		btn.textContent = open.length ? 'Report something new' : 'Report this pair';
		lastOpened = cells[r][c];
		try { history.replaceState(null, '', '#main=' + encodeURIComponent(forms[r].id) + '&borrowed=' + encodeURIComponent(forms[c].id)); } catch (e) { /* not essential */ }
		if (!dlg.open) {
			if (typeof dlg.showModal === 'function') dlg.showModal(); else dlg.setAttribute('open', '');
		}
		btn.focus();
	}

	function closeDialog() {
		if (typeof dlg.close === 'function') dlg.close(); else dlg.removeAttribute('open');
	}
	dlg.addEventListener('close', function () {
		try { history.replaceState(null, '', location.pathname + location.search); } catch (e) { /* not essential */ }
		if (lastOpened) lastOpened.focus();
	});
	$('d-close').addEventListener('click', closeDialog);
	dlg.addEventListener('click', function (e) { if (e.target === dlg) closeDialog(); });

	function openFromHash() {
		var h = location.hash.replace(/^#/, '');
		if (!h) return;
		var p = new URLSearchParams(h);
		var m = p.get('main'), b = p.get('borrowed');
		if (!m || !b) return;
		var r = forms.findIndex(function (f) { return f.id === m; });
		var c = forms.findIndex(function (f) { return f.id === b; });
		if (r >= 0 && c >= 0 && r !== c) {
			var cell = $('frame'), size = cells[0][0].offsetWidth;
			cell.scrollTo({ top: Math.max(0, r * size - 80), left: Math.max(0, c * size - 120) });
			openPair(r, c);
		}
	}

	// ---------------------------------------------------------------- grid events

	function focusCell(r, c) {
		r = Math.max(0, Math.min(n - 1, r)); c = Math.max(0, Math.min(n - 1, c));
		cells[focusAt.r][focusAt.c].tabIndex = -1;
		focusAt = { r: r, c: c };
		cells[r][c].tabIndex = 0;
		cells[r][c].focus();
	}

	function wireGrid() {
		var grid = $('grid');
		grid.addEventListener('click', function (e) {
			var cell = e.target.closest && e.target.closest('.c');
			if (!cell) return;
			var r = +cell.dataset.r, c = +cell.dataset.c;
			cells[focusAt.r][focusAt.c].tabIndex = -1;
			focusAt = { r: r, c: c };
			cell.tabIndex = 0;
			openPair(r, c);
		});
		grid.addEventListener('mouseover', function (e) {
			var cell = e.target.closest && e.target.closest('.c');
			if (cell) showTip(cell, e.clientX, e.clientY);
		});
		grid.addEventListener('mousemove', function (e) {
			var cell = e.target.closest && e.target.closest('.c');
			if (cell && !tip.hidden) showTip(cell, e.clientX, e.clientY);
		});
		grid.addEventListener('mouseleave', hideTip);
		grid.addEventListener('focusin', function (e) {
			var cell = e.target.closest && e.target.closest('.c');
			if (!cell) return;
			var rect = cell.getBoundingClientRect();
			showTip(cell, rect.left, rect.top + rect.height);
		});
		grid.addEventListener('focusout', hideTip);
		grid.addEventListener('keydown', function (e) {
			var cell = e.target.closest && e.target.closest('.c');
			if (!cell) return;
			var r = +cell.dataset.r, c = +cell.dataset.c, used = true;
			switch (e.key) {
				case 'ArrowUp': focusCell(r - 1, c); break;
				case 'ArrowDown': focusCell(r + 1, c); break;
				case 'ArrowLeft': focusCell(r, c - 1); break;
				case 'ArrowRight': focusCell(r, c + 1); break;
				case 'Home': focusCell(e.ctrlKey ? 0 : r, 0); break;
				case 'End': focusCell(e.ctrlKey ? n - 1 : r, n - 1); break;
				case 'PageUp': focusCell(r - 10, c); break;
				case 'PageDown': focusCell(r + 10, c); break;
				case 'Enter': case ' ': openPair(r, c); break;
				default: used = false;
			}
			if (used) e.preventDefault();
		});
		$('legend').addEventListener('click', function (e) {
			var b = e.target.closest && e.target.closest('button[data-status]');
			if (!b) return;
			var id = b.dataset.status;
			if (hiddenStatus[id]) delete hiddenStatus[id]; else hiddenStatus[id] = true;
			b.setAttribute('aria-pressed', hiddenStatus[id] ? 'false' : 'true');
			applyFilter();
		});
		var t;
		$('search').addEventListener('input', function () { clearTimeout(t); t = setTimeout(function () { onSearch(true); }, 120); });
		$('role').addEventListener('change', function () { onSearch(true); });
		$('reset').addEventListener('click', resetFilters);
		$('zoom').addEventListener('change', function () { setZoom($('zoom').value, true); });
		$('theme').addEventListener('click', cycleTheme);
		window.addEventListener('hashchange', openFromHash);
	}

	// ---------------------------------------------------------------- theme and zoom

	var THEMES = ['auto', 'light', 'dark'];
	function setTheme(t) {
		if (THEMES.indexOf(t) < 0) t = 'auto';
		if (t === 'auto') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', t);
		$('theme').textContent = 'Theme: ' + t;
		return t;
	}
	function cycleTheme() {
		var cur = document.documentElement.getAttribute('data-theme') || 'auto';
		var next = setTheme(THEMES[(THEMES.indexOf(cur) + 1) % THEMES.length]);
		store('morph-combos-theme', next);
	}
	function setZoom(v, save) {
		v = ['10', '14', '20'].indexOf(String(v)) >= 0 ? String(v) : '14';
		$('frame').style.setProperty('--cell', v + 'px');
		$('zoom').value = v;
		if (save) store('morph-combos-zoom', v);
	}

	// ---------------------------------------------------------------- live reports from GitHub

	function readCache(allowStale) {
		try {
			var s = sessionStorage.getItem(CACHE_KEY);
			if (!s) return null;
			var o = JSON.parse(s);
			if (!o || !Array.isArray(o.issues) || typeof o.at !== 'number') return null;
			if (!allowStale && Date.now() - o.at > CACHE_TTL) return null;
			return o;
		} catch (e) { return null; }
	}
	function writeCache(issues) {
		try { sessionStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), issues: issues })); } catch (e) { /* fine without it */ }
	}

	function fetchPage(page) {
		var ctl = typeof AbortController === 'function' ? new AbortController() : null;
		var timer = ctl ? setTimeout(function () { ctl.abort(); }, 10000) : null;
		return fetch(L.API_ISSUES + '?labels=multimorph&state=all&per_page=100&page=' + page, {
			headers: { 'Accept': 'application/vnd.github+json' },
			signal: ctl ? ctl.signal : undefined
		}).then(function (res) {
			if (timer) clearTimeout(timer);
			if (!res.ok) {
				var err = new Error('http ' + res.status);
				err.limited = res.status === 429 || (res.status === 403 && res.headers.get('x-ratelimit-remaining') === '0');
				err.reset = +res.headers.get('x-ratelimit-reset') || 0;
				throw err;
			}
			return res.json();
		}, function (e) { if (timer) clearTimeout(timer); throw e; });
	}

	function fetchAll() {
		var all = [];
		function next(page) {
			return fetchPage(page).then(function (arr) {
				if (!Array.isArray(arr)) throw new Error('unexpected answer');
				arr.forEach(function (raw) { var s = L.slimIssue(raw); if (s) all.push(s); });
				if (arr.length >= 100 && page < MAX_PAGES) return next(page + 1);
				return all;
			});
		}
		return next(1);
	}

	function liveNote(text, warn) {
		var p = $('live');
		p.textContent = text;
		p.classList.toggle('warn', !!warn);
	}

	function applyIssues(issues, stale, at) {
		var res = L.overlay(issues, index);
		ovl = res.pairs;
		liveOk = true;
		var open = 0;
		Object.keys(ovl).forEach(function (k) { open += ovl[k].open.length; });
		var when = new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
		var msg = open
			? open + (open === 1 ? ' open report is' : ' open reports are') + ' on the grid, read from GitHub at ' + when + '.'
			: 'No open reports right now. Squares show my last test results (read from GitHub at ' + when + ').';
		if (res.unplaced) msg += ' ' + res.unplaced + (res.unplaced === 1 ? ' report' : ' reports') + ' couldn’t be matched to a pair.';
		if (stale) msg += ' GitHub didn’t answer just now, so this may be a few minutes old.';
		liveNote(msg, stale);
		repaintOverlay();
	}

	function loadLive() {
		var fresh = readCache(false);
		if (fresh) { applyIssues(fresh.issues, false, fresh.at); return; }
		fetchAll().then(function (issues) {
			writeCache(issues);
			applyIssues(issues, false, Date.now());
		}, function (err) {
			var old = readCache(true);
			if (old) { applyIssues(old.issues, true, old.at); return; }
			var m;
			if (err && err.limited) {
				var when = err.reset ? new Date(err.reset * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
				m = 'GitHub lets a browser read its public lists only 60 times an hour and that limit is used up' + (when ? ' until about ' + when : '') + '. The squares show my last test results, without recent reports.';
			} else {
				m = 'I couldn’t read the open reports from GitHub just now (no connection, or GitHub is busy). The squares show my last test results, without recent reports. Reload to try again.';
			}
			liveNote(m, true);
		});
	}

	// ---------------------------------------------------------------- start

	function start() {
		setTheme(recall('morph-combos-theme') || 'auto');
		setZoom(recall('morph-combos-zoom') || '14', false);
		fetch('data.json', { cache: 'no-cache' }).then(function (r) {
			if (!r.ok) throw new Error('data ' + r.status);
			return r.json();
		}).then(function (d) {
			data = d;
			build();
			drawLegend();
			wireGrid();
			openFromHash();
			loadLive();
		}).catch(function () {
			$('loading').textContent = 'The grid data didn’t load. If you opened this file straight from a folder, open it through the GitHub Pages link instead. The same data is in the CSV and the written list linked below.';
			liveNote('', false);
		});
	}
	start();
})();
