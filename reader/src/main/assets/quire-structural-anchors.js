/* Exact highlight ranges for pinned Readium 3.0. No publication DOM mutation. */
(() => {
  if (window.quireStructuralAnchors) return;
  const KEY = 'quireDomRange';
  const VERSION = 3;
  const adapters = new Map();

  // SHA-256 binds paths/offsets to the original chapter's content, excluding reader overlays.
  function sha256(text) {
    const bytes = new TextEncoder().encode(text);
    const words = [];
    for (let i = 0; i < bytes.length; i++) words[i >> 2] = (words[i >> 2] || 0) | bytes[i] << (24 - (i % 4) * 8);
    words[bytes.length >> 2] = (words[bytes.length >> 2] || 0) | 0x80 << (24 - (bytes.length % 4) * 8);
    const end = (((bytes.length + 8) >> 6) + 1) * 16;
    words[end - 2] = Math.floor(bytes.length / 0x20000000);
    words[end - 1] = bytes.length << 3;
    const k = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
    const h = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
    const rotr = (x, n) => x >>> n | x << (32 - n);
    for (let offset = 0; offset < end; offset += 16) {
      const w = [];
      for (let i = 0; i < 64; i++) {
        if (i < 16) w[i] = words[offset + i] | 0;
        else {
          const a = w[i - 15], b = w[i - 2];
          w[i] = (w[i - 16] + (rotr(a,7) ^ rotr(a,18) ^ a >>> 3) + w[i - 7] + (rotr(b,17) ^ rotr(b,19) ^ b >>> 10)) | 0;
        }
      }
      let [a,b,c,d,e,f,g,j] = h;
      for (let i = 0; i < 64; i++) {
        const t1 = (j + (rotr(e,6) ^ rotr(e,11) ^ rotr(e,25)) + (e & f ^ ~e & g) + k[i] + w[i]) | 0;
        const t2 = ((rotr(a,2) ^ rotr(a,13) ^ rotr(a,22)) + (a & b ^ a & c ^ b & c)) | 0;
        j=g; g=f; f=e; e=(d+t1)|0; d=c; c=b; b=a; a=(t1+t2)|0;
      }
      [a,b,c,d,e,f,g,j].forEach((v,i) => { h[i] = (h[i] + v) | 0; });
    }
    return h.map(v => (v >>> 0).toString(16).padStart(8, '0')).join('');
  }

  function ignored(element) {
    return element.nodeType === 1 && (
      ['SCRIPT','STYLE','NOSCRIPT'].includes(element.tagName.toUpperCase()) ||
      element.id === 'readium-virtual-page' ||
      (element.hasAttribute('data-group') && element.id.startsWith('r2-decoration-'))
    );
  }
  const characterData = node => node.nodeType === 3 || node.nodeType === 4;
  const contentNode = node => characterData(node) || node.nodeType === 1 && !ignored(node);
  const contentChildren = node => Array.from(node.childNodes).filter(contentNode);
  function contentTree(node) {
    // Node types and boundaries are significant: merging equal adjacent text must invalidate
    // paths, and literal markup in a text node must not collide with an actual element tree.
    return characterData(node) ? [node.nodeType, node.nodeValue] :
      [1, node.namespaceURI, node.localName, contentChildren(node).map(contentTree)];
  }
  let observedBody = null;
  let cachedHash = null;
  function insideIgnored(node) {
    for (let current = node; current; current = current.parentNode) {
      if (ignored(current)) return true;
    }
    return false;
  }
  function contentChanged(records) {
    return records.some(record => !insideIgnored(record.target) &&
      (record.type === 'characterData' ||
        [...record.addedNodes, ...record.removedNodes].some(node => !ignored(node))));
  }
  const observer = new MutationObserver(records => {
    if (contentChanged(records)) cachedHash = null;
  });
  function updateContentState() {
    if (observedBody !== document.body) {
      observer.disconnect();
      observedBody = document.body;
      cachedHash = null;
      observer.observe(observedBody, { subtree: true, childList: true, characterData: true });
    }
    // A publisher can edit content and render in the same JS turn, before the observer runs.
    if (contentChanged(observer.takeRecords())) cachedHash = null;
  }
  function contentHash() {
    updateContentState();
    if (cachedHash !== null) return cachedHash;
    cachedHash = sha256(JSON.stringify(contentTree(document.body)));
    return cachedHash;
  }
  function selector(element) {
    if (!document.body.contains(element)) throw new Error('outside publication body');
    const path = [];
    for (let current = element; current !== document.body; current = current.parentElement) {
      if (!current || ignored(current)) throw new Error('reader decoration selected');
      const siblings = Array.from(current.parentElement.children).filter(s => s.localName === current.localName);
      path.unshift(current.localName + ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')');
    }
    return ['body'].concat(path).join(' > ');
  }
  function endpoint(node, offset) {
    const path = [];
    if (!document.body.contains(node)) throw new Error('outside publication body');
    for (let current = node; current !== document.body; current = current.parentNode) {
      if (!current.parentNode) throw new Error('missing endpoint parent');
      const index = contentChildren(current.parentNode).indexOf(current);
      if (index < 0) throw new Error('unsupported endpoint node');
      path.unshift(index);
    }
    const contentOffset = node.nodeType === 1
      ? Array.from(node.childNodes).slice(0, offset).filter(contentNode).length
      : offset;
    return { path, nodeType: node.nodeType, offset: contentOffset };
  }
  function resolveEndpoint(point) {
    if (!point || !Array.isArray(point.path) ||
        !Number.isSafeInteger(point.offset) || point.offset < 0) throw new Error('invalid endpoint');
    let node = document.body;
    for (const index of point.path) {
      const children = contentChildren(node);
      if (!Number.isSafeInteger(index) || index < 0 || !children[index]) throw new Error('invalid node path');
      node = children[index];
    }
    const children = contentChildren(node);
    if (![1,3,4].includes(node.nodeType) || node.nodeType !== point.nodeType ||
        point.offset > (characterData(node) ? node.nodeValue.length : children.length)) throw new Error('invalid offset');
    const offset = characterData(node) ? point.offset : point.offset < children.length
      ? Array.prototype.indexOf.call(node.childNodes, children[point.offset])
      : children.length ? Array.prototype.indexOf.call(node.childNodes, children[children.length - 1]) + 1 : 0;
    return { node, offset };
  }
  function rangeText(range) {
    const parts = [];
    function visit(node) {
      if (ignored(node)) return;
      if (characterData(node)) parts.push(node.nodeValue);
      else node.childNodes.forEach(visit);
    }
    visit(range.cloneContents());
    return parts.join('');
  }
  function resolve(locator) {
    let anchor = locator && locator.locations && locator.locations[KEY];
    if (typeof anchor === 'string') anchor = JSON.parse(anchor);
    if (!anchor || anchor.version !== VERSION || typeof anchor.quote !== 'string' || !anchor.quote.trim() ||
        typeof anchor.rangeText !== 'string' ||
        anchor.href !== locator.href || anchor.documentHash !== contentHash()) throw new Error('missing or stale structural anchor');
    const start = resolveEndpoint(anchor.start), end = resolveEndpoint(anchor.end);
    const range = document.createRange();
    range.setStart(start.node, start.offset); range.setEnd(end.node, end.offset);
    if (range.collapsed || rangeText(range) !== anchor.rangeText) throw new Error('structural text mismatch');
    return range;
  }
  function capture(href) {
    try {
      const pathname = decodeURIComponent(new URL(document.location.href).pathname);
      const expectedPath = decodeURIComponent(new URL(href, 'https://quire.invalid/').pathname).replace(/^\/+/, '');
      if (!pathname.endsWith('/' + expectedPath)) return null;
      const selection = window.getSelection();
      if (!selection || selection.rangeCount !== 1 || selection.isCollapsed) return null;
      const range = selection.getRangeAt(0);
      const native = readium.getCurrentSelection();
      if (!native || !native.text || native.text.highlight !== selection.toString()) return null;
      const anchor = { version: VERSION, href, documentHash: contentHash(),
        start: endpoint(range.startContainer, range.startOffset), end: endpoint(range.endContainer, range.endOffset),
        quote: native.text.highlight, rangeText: rangeText(range) };
      resolve({ href, locations: { [KEY]: anchor } });
      return { anchor, text: native.text };
    } catch (_) { return null; }
  }
  function prepare(name) {
    if (!window.readium || typeof readium.getDecorations !== 'function') return { ready: false, reason: 'unsupported Readium API' };
    const group = readium.getDecorations(name);
    if (!group || !Array.isArray(group.items) || !['add','remove','update','clear','requestLayout'].every(k => typeof group[k] === 'function'))
      return { ready: false, reason: 'unsupported Readium decoration group' };
    if (adapters.has(name)) return { ready: adapters.get(name).group === group, rejectedIds: [...adapters.get(name).rejected.keys()] };
    const raw = { add: group.add, remove: group.remove, clear: group.clear };
    const rejected = new Map();
    function removeAll(id) {
      while (group.items.some(item => item.decoration.id === id)) {
        const length = group.items.length;
        raw.remove(id);
        if (group.items.length >= length) throw new Error('unsupported Readium removal');
      }
    }
    group.add = function (decoration) {
      removeAll(decoration.id);
      try {
        const range = resolve(decoration.locator);
        const startElement = range.startContainer.nodeType === 1 ? range.startContainer : range.startContainer.parentElement;
        // The stock matcher is never allowed to choose the target. Seed only a small element,
        // then replace its range and redraw synchronously before the browser can paint.
        const seed = { ...decoration, locator: { ...decoration.locator, text: null,
          locations: { cssSelector: selector(startElement) } } };
        raw.add(seed);
        const item = group.items.find(item => item.decoration.id === decoration.id);
        if (!item) throw new Error('unsupported Readium addition');
        item.decoration = decoration;
        item.range = range;
        rejected.delete(decoration.id);
        group.requestLayout();
      } catch (error) {
        removeAll(decoration.id);
        rejected.set(decoration.id, error.message);
      }
    };
    group.remove = function (id) { removeAll(id); rejected.delete(id); };
    group.update = function (decoration) { group.add(decoration); };
    group.clear = function () { raw.clear(); rejected.clear(); };
    const existing = group.items.map(item => item.decoration);
    raw.clear();
    adapters.set(name, { group, rejected, validationRejected: new Map() });
    existing.forEach(decoration => group.add(decoration));
    return { ready: true, rejectedIds: [...rejected.keys()] };
  }
  function diagnostics(name) {
    updateContentState();
    const adapter = adapters.get(name);
    const rejected = adapter && new Map([...adapter.validationRejected, ...adapter.rejected]);
    return adapter ? { ready: true, rejectedIds: [...rejected.keys()],
      contentCurrent: cachedHash !== null,
      rejections: Array.from(rejected).reduce((all, [id, reason]) => { all[id] = reason; return all; }, {}) } : { ready: false, rejectedIds: [] };
  }
  function validate(name, decorations) {
    const adapter = adapters.get(name);
    if (!adapter) return { ready: false };
    const retained = new Set(decorations.map(item => item.id));
    for (const id of adapter.validationRejected.keys()) {
      if (!retained.has(id)) adapter.validationRejected.delete(id);
    }
    const rejectedIds = [];
    for (const decoration of decorations) {
      try {
        resolve(decoration.locator);
        adapter.validationRejected.delete(decoration.id);
      } catch (error) {
        rejectedIds.push(decoration.id);
        adapter.validationRejected.set(decoration.id, error.message);
      }
    }
    return { rejectedIds };
  }
  function firstPaintedRect(range) {
    const nodes = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_CDATA_SECTION);
    for (let node = nodes.nextNode(); node; node = nodes.nextNode()) {
      if (insideIgnored(node) || !range.intersectsNode(node)) continue;
      let hidden = false;
      for (let element = node.parentElement; element; element = element.parentElement) {
        const style = getComputedStyle(element);
        if (style.visibility !== 'visible' || style.display === 'none' || Number(style.opacity) === 0) { hidden = true; break; }
      }
      if (hidden) continue;
      const start = node === range.startContainer ? range.startOffset : 0;
      const end = node === range.endContainer ? range.endOffset : node.length;
      for (let offset = start; offset < end;) {
        const length = node.data.codePointAt(offset) > 0xffff ? 2 : 1;
        const point = document.createRange();
        point.setStart(node, offset); point.setEnd(node, Math.min(offset + length, end));
        // Whitespace at a line boundary can have a misleading zero-area caret rectangle.
        if (/\S/u.test(point.toString())) {
          const rect = Array.from(point.getClientRects()).find(r => r.width > 0 && r.height > 0);
          if (rect) return rect;
        }
        offset += length;
      }
    }
    throw new Error('range has no painted text');
  }
  function navigation(locator, apply) {
    try {
      if (!window.readium || readium.isFixedLayout || typeof readium.scrollToPosition !== 'function')
        throw new Error('unsupported Readium navigation');
      const range = resolve(locator);
      const rect = firstPaintedRect(range);
      const root = document.scrollingElement;
      const style = document.documentElement.style;
      const writing = getComputedStyle(document.body).writingMode;
      if (!root || writing && writing !== 'horizontal-tb') throw new Error('unsupported writing mode');
      // These mode/direction predicates and page width mirror the pinned Readium 3.0 script.
      const scroll = ['--USER__view', '--USER__scroll'].some(k => style.getPropertyValue(k).trim() === 'readium-scroll-on');
      const rtl = document.body.dir.toLowerCase() === 'rtl';
      const width = window.Android && typeof Android.getViewportWidth === 'function'
        ? Android.getViewportWidth() / window.devicePixelRatio : window.innerWidth;
      let progression;
      if (scroll) progression = (rect.top + window.scrollY) / root.scrollHeight;
      else {
        if (!Number.isFinite(width) || width <= 0) throw new Error('missing viewport');
        // Choose the column containing the first character, not the paragraph's first column.
        const leading = (rtl ? rect.right - 1 : rect.left + 1) + window.scrollX;
        const page = rtl ? Math.max(0, Math.ceil(-leading / width)) : Math.floor(leading / width);
        progression = page * width / root.scrollWidth;
      }
      if (!Number.isFinite(progression) || progression < -0.000001 || progression > 1.000001)
        throw new Error('range outside navigation bounds');
      const beforeX = window.scrollX, beforeY = window.scrollY;
      if (apply) readium.scrollToPosition(Math.max(0, Math.min(1, progression)));
      const after = firstPaintedRect(resolve(locator));
      return { available: true, visible: after.right > 0 && after.left < window.innerWidth &&
        after.bottom > 0 && after.top < window.innerHeight,
        moved: beforeX !== window.scrollX || beforeY !== window.scrollY };
    } catch (_) { return { available: false, visible: false, moved: false }; }
  }
  function viewport(href) {
    try {
      if (typeof document.caretRangeFromPoint !== 'function') return null;
      const xs = [8, window.innerWidth / 2, window.innerWidth - 8];
      if (document.body.dir.toLowerCase() === 'rtl') xs.reverse();
      for (let y = 2; y < window.innerHeight; y += 24) {
        for (const x of xs) {
          const caret = document.caretRangeFromPoint(x, y);
          if (!caret || !characterData(caret.startContainer) || insideIgnored(caret.startContainer)) continue;
          const node = caret.startContainer;
          for (let delta = 0; delta < 8; delta++) {
            for (const offset of [caret.startOffset + delta, caret.startOffset - delta - 1]) {
              if (offset < 0 || offset >= node.length) continue;
              const range = document.createRange();
              range.setStart(node, offset);
              range.setEnd(node, Math.min(node.length, offset + (node.data.codePointAt(offset) > 0xffff ? 2 : 1)));
              if (!range.toString().trim()) continue;
              const rect = firstPaintedRect(range);
              if (rect.right <= 0 || rect.left >= window.innerWidth || rect.bottom <= 0 || rect.top >= window.innerHeight) continue;
              return { anchor: { version: VERSION, href, documentHash: contentHash(),
                start: endpoint(range.startContainer, range.startOffset), end: endpoint(range.endContainer, range.endOffset),
                quote: range.toString(), rangeText: rangeText(range) }, text: { highlight: range.toString() } };
            }
          }
        }
      }
    } catch (_) { /* Unsupported layouts leave the reader's existing position untouched. */ }
    return null;
  }
  function position() {
    const root = document.scrollingElement;
    const layout = ['width','height','fontFamily','fontSize','lineHeight','columnWidth','columnGap','columnCount','writingMode']
      .map(key => getComputedStyle(document.body)[key]);
    layout.push(Array.from(document.querySelectorAll('img,svg,canvas,video,object')).filter(el => !insideIgnored(el))
      .map(el => { const r = el.getBoundingClientRect(); return [r.left + scrollX, r.top + scrollY, r.width, r.height]; }));
    return root ? { documentHash: contentHash(), x: window.scrollX, y: window.scrollY,
      width: window.innerWidth, height: window.innerHeight, scrollWidth: root.scrollWidth,
      scrollHeight: root.scrollHeight, direction: document.body.dir.toLowerCase(),
      scroll: ['--USER__view', '--USER__scroll'].some(k => document.documentElement.style.getPropertyValue(k).trim() === 'readium-scroll-on'),
      layout: JSON.stringify(layout) } : null;
  }
  function restorePosition(saved, apply) {
    try {
      const now = position();
      if (!saved || !now || saved.documentHash !== now.documentHash ||
          ![saved.x, saved.y, saved.width, saved.height, saved.scrollWidth, saved.scrollHeight].every(Number.isFinite))
        throw new Error('missing origin position');
      const atStart = saved.x === 0 && saved.y === 0;
      if (!atStart && ['width','height','scrollWidth','scrollHeight','direction','scroll','layout'].some(key => saved[key] !== now[key]))
        throw new Error('origin layout changed');
      const beforeX = window.scrollX, beforeY = window.scrollY;
      if (apply) {
        if (atStart && window.readium && typeof readium.scrollToStart === 'function') readium.scrollToStart();
        else if (saved.x !== beforeX || saved.y !== beforeY) {
          const progress = saved.scroll ? saved.y / saved.scrollHeight : Math.abs(saved.x) / saved.scrollWidth;
          if (!window.readium || typeof readium.scrollToPosition !== 'function' || progress < 0 || progress > 1)
            throw new Error('unsupported origin navigation');
          readium.scrollToPosition(progress);
        }
      }
      return { available: true, visible: Math.abs(window.scrollX - saved.x) < 2 && Math.abs(window.scrollY - saved.y) < 2,
        moved: beforeX !== window.scrollX || beforeY !== window.scrollY };
    } catch (_) { return { available: false, visible: false, moved: false }; }
  }
  window.quireStructuralAnchors = { capture, prepare, diagnostics, validate, sha256,
    jump: locator => navigation(locator, true), visible: locator => navigation(locator, false), viewport, position, restorePosition };
})();
