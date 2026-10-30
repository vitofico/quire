// Install with npm ci --prefix scripts; run with npm test --prefix scripts.
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const source = fs.readFileSync(path.join(__dirname, '../../reader/src/main/assets/quire-structural-anchors.js'), 'utf8');
let browser;
before(async () => { browser = await chromium.launch({ headless: true, ...(process.env.QUIRE_CHROMIUM_EXECUTABLE ? { executablePath: process.env.QUIRE_CHROMIUM_EXECUTABLE } : {}) }); });
after(async () => { await browser?.close(); });

async function pageFor(body, href = 'chapter.xhtml', contentType = 'text/html') {
  const page = await browser.newPage();
  const url = new URL(href, 'https://readium.test/').href;
  await page.route(url, route => route.fulfill({ body: '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Test</title></head><body>' + body + '</body></html>', contentType }));
  await page.goto(url);
  await page.evaluate(() => {
    const groups = new Map();
    window.readium = {
      getCurrentSelection: () => ({ text: { highlight: window.getSelection().toString() } }),
      getDecorations: name => {
        if (groups.has(name)) return groups.get(name);
        const items = [];
        let serial = 0;
        const container = document.createElement('div');
        container.id = 'r2-decoration-test'; container.setAttribute('data-group', name);
        document.body.append(container);
        const group = {
          items,
          add(decoration) {
            const element = document.querySelector(decoration.locator.locations.cssSelector);
            if (!element) return;
            const range = document.createRange(); range.selectNode(element);
            items.push({ id: 'overlay-' + ++serial, decoration, range }); group.requestLayout();
          },
          remove(id) { const index = items.findIndex(item => item.decoration.id === id); if (index >= 0) items.splice(index, 1); group.requestLayout(); },
          clear() { items.length = 0; group.requestLayout(); },
          update(decoration) { group.remove(decoration.id); group.add(decoration); },
          requestLayout() { container.replaceChildren(...items.map(item => { const div = document.createElement('div'); div.id = item.id; return div; })); },
        };
        groups.set(name, group); return group;
      },
    };
  });
  await page.evaluate(script => { (0, eval)(script); }, source);
  return page;
}

async function selectAndCapture(page, start, end, startOffset, endOffset) {
  return page.evaluate(({ start, end, startOffset, endOffset }) => {
    const range = document.createRange();
    range.setStart(document.querySelector(start).firstChild, startOffset);
    range.setEnd(document.querySelector(end).firstChild, endOffset);
    const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
    return window.quireStructuralAnchors.capture('chapter.xhtml');
  }, { start, end, startOffset, endOffset });
}

function decoration(anchor, id = 'saved') {
  return { id, locator: { href: 'chapter.xhtml', text: {}, locations: { cssSelector: ':not(*)', quireDomRange: JSON.stringify(anchor) } } };
}

test('SHA-256 chapter signature uses known UTF-8 vectors', async () => {
  const page = await pageFor('<p>text</p>');
  const hashes = await page.evaluate(() => ['', 'abc', 'café 日本', 'x'.repeat(10000)].map(window.quireStructuralAnchors.sha256));
  const crypto = require('node:crypto');
  assert.deepEqual(hashes, ['', 'abc', 'café 日本', 'x'.repeat(10000)].map(value => crypto.createHash('sha256').update(value).digest('hex')));
  await page.close();
});

test('identical paragraphs resolve the selected second occurrence after JSON persistence', async () => {
  const page = await pageFor('<p>same quote after</p><p>same quote after</p>');
  const snapshot = await selectAndCapture(page, 'p:nth-of-type(2)', 'p:nth-of-type(2)', 5, 10);
  assert.equal(snapshot.anchor.quote, 'quote');
  assert.deepEqual(snapshot.anchor.start.path, [1, 0]);
  const result = await page.evaluate(dec => {
    const api = window.quireStructuralAnchors; api.prepare('test');
    const group = readium.getDecorations('test'); group.add(dec);
    return { count: group.items.length, selectedSecond: group.items[0].range.startContainer.parentElement === document.querySelector('p:nth-of-type(2)'), text: group.items[0].range.toString() };
  }, decoration(JSON.parse(JSON.stringify(snapshot.anchor))));
  assert.deepEqual(result, { count: 1, selectedSecond: true, text: 'quote' });
  await page.close();
});

test('identical sibling paragraph pairs preserve exact cross-block endpoints and raw whitespace', async () => {
  const page = await pageFor('<p>alpha</p><p>beta</p><p>alpha</p><p>beta</p>');
  const snapshot = await selectAndCapture(page, 'p:nth-of-type(3)', 'p:nth-of-type(4)', 2, 2);
  // Chromium inserts a block newline in Selection.toString but not DOM Range.toString.
  assert.equal(snapshot.anchor.rangeText, 'phabe');
  const result = await page.evaluate(dec => {
    window.quireStructuralAnchors.prepare('test'); const group = readium.getDecorations('test'); group.add(dec);
    const range = group.items[0].range;
    return [range.startContainer.parentElement === document.querySelector('p:nth-of-type(3)'), range.endContainer.parentElement === document.querySelector('p:nth-of-type(4)'), range.toString()];
  }, decoration(snapshot.anchor));
  assert.deepEqual(result, [true, true, 'phabe']);
  await page.close();
});

test('malformed, legacy, stale and missing selectors fail closed without text fallback', async () => {
  const page = await pageFor('<p>same quote after</p><p>same quote after</p>');
  const { anchor } = await selectAndCapture(page, 'p:nth-of-type(2)', 'p:nth-of-type(2)', 5, 10);
  const cases = [null, { ...anchor, version: 99 }, { ...anchor, start: { ...anchor.start, path: [999] } },
    { ...anchor, start: { ...anchor.start, offset: 999 } }, { ...anchor, documentHash: 'stale' }, { ...anchor, rangeText: 'wrong quote' }];
  const result = await page.evaluate(cases => {
    const api = window.quireStructuralAnchors; api.prepare('test'); const group = readium.getDecorations('test');
    cases.forEach((anchor, i) => group.add({ id: String(i), locator: { href: 'chapter.xhtml', text: { highlight: 'quote' }, locations: { cssSelector: ':not(*)', quireDomRange: JSON.stringify(anchor) } } }));
    return { count: group.items.length, rejected: api.diagnostics('test').rejectedIds.length };
  }, cases);
  assert.deepEqual(result, { count: 0, rejected: cases.length });
  await page.close();
});

test('stock pre-adapter additions cannot render and repeated replay/remove is idempotent', async () => {
  const page = await pageFor('<p>same quote after</p><p>same quote after</p>');
  const { anchor } = await selectAndCapture(page, 'p:nth-of-type(2)', 'p:nth-of-type(2)', 5, 10);
  const result = await page.evaluate(dec => {
    const group = readium.getDecorations('test'); group.add(dec);
    const before = group.items.length;
    const api = window.quireStructuralAnchors; api.prepare('test'); api.prepare('test');
    group.add(dec); group.add(dec); group.update(dec);
    group.add({ ...dec, id: 'other' });
    const replayed = group.items.map(item => item.decoration.id);
    group.remove(dec.id);
    return { before, replayed, afterRemove: group.items.map(item => item.decoration.id), domCount: document.querySelector('[data-group="test"]').children.length };
  }, decoration(anchor));
  assert.deepEqual(result, { before: 0, replayed: ['saved', 'other'], afterRemove: ['other'], domCount: 1 });
  await page.close();
});

test('changed chapter content rejects a formerly valid structural anchor', async () => {
  const page = await pageFor('<p>same quote after</p><p>same quote after</p>');
  const { anchor } = await selectAndCapture(page, 'p:nth-of-type(2)', 'p:nth-of-type(2)', 5, 10);
  const result = await page.evaluate(dec => {
    document.querySelector('p').append(' revision');
    const api = window.quireStructuralAnchors; api.prepare('test'); const group = readium.getDecorations('test'); group.add(dec);
    return { count: group.items.length, rejected: api.diagnostics('test').rejectedIds };
  }, decoration(anchor));
  assert.deepEqual(result, { count: 0, rejected: ['saved'] });
  await page.close();
});


test('percent-encoded EPUB resource paths normalize both capture sides', async () => {
  const href = 'shelf/chapter%20caf%C3%A9.xhtml';
  const page = await pageFor('<p>encoded path quote</p>', href);
  const snapshot = await page.evaluate(href => {
    const range = document.createRange(); range.selectNodeContents(document.querySelector('p'));
    const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
    return window.quireStructuralAnchors.capture(href);
  }, href);
  assert.equal(snapshot.anchor.href, href);
  assert.equal(snapshot.anchor.quote, 'encoded path quote');
  await page.close();
});

test('preflight rejects an invalid item while retaining and drawing its valid peer', async () => {
  const page = await pageFor('<p>same quote after</p><p>same quote after</p>');
  const { anchor } = await selectAndCapture(page, 'p:nth-of-type(2)', 'p:nth-of-type(2)', 5, 10);
  const result = await page.evaluate(valid => {
    const api = window.quireStructuralAnchors; api.prepare('test');
    const invalid = { ...valid, id: 'bad', locator: { ...valid.locator, locations: {} } };
    const input = [valid, invalid];
    const { rejectedIds } = api.validate('test', input);
    const group = readium.getDecorations('test');
    input.filter(item => !rejectedIds.includes(item.id)).forEach(item => group.add(item));
    return { rejectedIds, drawn: group.items.map(item => item.decoration.id),
      diagnostics: api.diagnostics('test').rejectedIds };
  }, decoration(anchor));
  assert.deepEqual(result, { rejectedIds: ['bad'], drawn: ['saved'], diagnostics: ['bad'] });
  await page.close();
});

test('reader overlays keep the fingerprint cache valid but publisher mutations invalidate it immediately', async () => {
  const page = await pageFor('<p>same quote after</p><p>same quote after</p>');
  const { anchor } = await selectAndCapture(page, 'p:nth-of-type(2)', 'p:nth-of-type(2)', 5, 10);
  const result = await page.evaluate(dec => {
    const api = window.quireStructuralAnchors; api.prepare('test');
    const group = readium.getDecorations('test'); group.add(dec);
    const style = document.createElement('style'); style.textContent = 'p { color: black; }';
    document.body.append(style);
    const virtual = document.createElement('div'); virtual.id = 'readium-virtual-page';
    document.body.append(virtual);
    const afterReaderChanges = api.diagnostics('test').contentCurrent;
    document.querySelector('p').append(' revision');
    const afterPublisherChange = api.diagnostics('test').contentCurrent;
    const { rejectedIds } = api.validate('test', [dec]);
    group.add(dec);
    return { afterReaderChanges, afterPublisherChange, rejectedIds, drawn: group.items.length };
  }, decoration(anchor));
  assert.deepEqual(result, { afterReaderChanges: true, afterPublisherChange: false,
    rejectedIds: ['saved'], drawn: 0 });
  await page.close();
});

test('an unsupported decoration-group shape fails closed before native rendering', async () => {
  const page = await pageFor('<p>same quote after</p>');
  const { anchor } = await selectAndCapture(page, 'p', 'p', 5, 10);
  const result = await page.evaluate(dec => {
    const group = readium.getDecorations('test'); group.requestLayout = undefined;
    const prepared = window.quireStructuralAnchors.prepare('test');
    group.add(dec);
    return { ready: prepared.ready, drawn: group.items.length };
  }, decoration(anchor));
  assert.deepEqual(result, { ready: false, drawn: 0 });
  await page.close();
});

test('Select all endpoints ignore reader-owned nodes and restore in a fresh document', async () => {
  const page = await pageFor('<p>alpha</p><p>beta</p>');
  const snapshot = await page.evaluate(() => {
    const api = window.quireStructuralAnchors; api.prepare('test');
    const virtual = document.createElement('div'); virtual.id = 'readium-virtual-page';
    const style = document.createElement('style'); style.textContent = 'p { color: black; }';
    document.body.append(virtual, style);
    window.getSelection().selectAllChildren(document.body);
    return api.capture('chapter.xhtml');
  });
  assert.equal(snapshot.anchor.start.nodeType, 1);
  assert.equal(snapshot.anchor.start.offset, 0);
  assert.equal(snapshot.anchor.end.offset, 2);
  assert.equal(snapshot.anchor.rangeText, 'alphabeta');
  await page.close();

  const restored = await pageFor('<p>alpha</p><p>beta</p>');
  const result = await restored.evaluate(dec => {
    window.quireStructuralAnchors.prepare('test');
    const group = readium.getDecorations('test'); group.add(dec);
    const range = group.items[0].range;
    return { count: group.items.length, text: range.toString(),
      bodyEndpoints: range.startContainer === document.body && range.endContainer === document.body };
  }, decoration(snapshot.anchor));
  assert.deepEqual(result, { count: 1, text: 'alphabeta', bodyEndpoints: true });
  await restored.close();
});

test('hidden publication text may differ from the native quote without losing the selected DOM range', async () => {
  const page = await pageFor('<p>visible <span style="display:none">hidden</span> quote</p>');
  const snapshot = await page.evaluate(() => {
    window.getSelection().selectAllChildren(document.querySelector('p'));
    return window.quireStructuralAnchors.capture('chapter.xhtml');
  });
  assert.equal(snapshot.anchor.quote.includes('hidden'), false);
  assert.equal(snapshot.anchor.rangeText, 'visible hidden quote');
  const result = await page.evaluate(dec => {
    window.quireStructuralAnchors.prepare('test');
    const group = readium.getDecorations('test'); group.add(dec);
    return { count: group.items.length, rawText: group.items[0].range.toString() };
  }, decoration(snapshot.anchor));
  assert.deepEqual(result, { count: 1, rawText: 'visible hidden quote' });
  await page.close();
});

test('preflight rejection diagnostics survive an empty native map until desired annotations are cleared', async () => {
  const page = await pageFor('<p>quote</p>');
  const result = await page.evaluate(() => {
    const api = window.quireStructuralAnchors; api.prepare('test');
    api.validate('test', [{ id: 'bad', locator: {} }]);
    readium.getDecorations('test').clear();
    const afterNativeClear = api.diagnostics('test').rejectedIds;
    api.validate('test', []);
    return { afterNativeClear, afterDesiredClear: api.diagnostics('test').rejectedIds };
  });
  assert.deepEqual(result, { afterNativeClear: ['bad'], afterDesiredClear: [] });
  await page.close();
});

test('comment-only edits cannot move a saved repeated text-node range to another occurrence', async () => {
  const page = await pageFor('<p></p>');
  const snapshot = await page.evaluate(() => {
    const paragraph = document.querySelector('p');
    paragraph.append(document.createTextNode('same'), document.createComment('divider'), document.createTextNode('same'));
    const range = document.createRange(); range.selectNodeContents(paragraph.lastChild);
    const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
    return window.quireStructuralAnchors.capture('chapter.xhtml');
  });
  const result = await page.evaluate(dec => {
    const paragraph = document.querySelector('p');
    paragraph.prepend(document.createComment('one'), document.createComment('two'));
    window.quireStructuralAnchors.prepare('test');
    const group = readium.getDecorations('test'); group.add(dec);
    return { count: group.items.length, correctNode: group.items[0].range.startContainer === paragraph.lastChild };
  }, decoration(snapshot.anchor));
  assert.deepEqual(result, { count: 1, correctNode: true });
  await page.close();
});

test('ignored reader DIV insertion cannot shift a saved range onto an identical publication DIV', async () => {
  const page = await pageFor('<div>same quote after</div><div>same quote after</div>');
  const snapshot = await selectAndCapture(page, 'body > div:nth-of-type(2)', 'body > div:nth-of-type(2)', 5, 10);
  const result = await page.evaluate(dec => {
    const original = document.body.lastElementChild;
    const reader = document.createElement('div'); reader.id = 'readium-virtual-page';
    document.body.prepend(reader);
    window.quireStructuralAnchors.prepare('test');
    const group = readium.getDecorations('test'); group.add(dec);
    return { count: group.items.length, correct: group.items[0].range.startContainer.parentElement === original };
  }, decoration(snapshot.anchor));
  assert.deepEqual(result, { count: 1, correct: true });
  await page.close();
});

test('text-node merging with unchanged joined text invalidates the original structural binding', async () => {
  const page = await pageFor('<p></p>');
  const snapshot = await page.evaluate(() => {
    const paragraph = document.querySelector('p');
    paragraph.append(...[1,2,3].map(() => document.createTextNode('same')));
    const range = document.createRange(); range.selectNodeContents(paragraph.childNodes[1]);
    const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
    return window.quireStructuralAnchors.capture('chapter.xhtml');
  });
  const result = await page.evaluate(dec => {
    const paragraph = document.querySelector('p');
    paragraph.firstChild.appendData(paragraph.childNodes[1].nodeValue);
    paragraph.childNodes[1].remove();
    window.quireStructuralAnchors.prepare('test');
    const group = readium.getDecorations('test'); group.add(dec);
    return { count: group.items.length, rejected: window.quireStructuralAnchors.diagnostics('test').rejectedIds };
  }, decoration(snapshot.anchor));
  assert.deepEqual(result, { count: 0, rejected: ['saved'] });
  await page.close();
});

test('literal markup text and an actual element tree have different chapter fingerprints', async () => {
  const literal = await pageFor('&lt;p&gt;quote&lt;/p&gt;');
  const first = await literal.evaluate(() => {
    const range = document.createRange(); range.setStart(document.body.firstChild, 3); range.setEnd(document.body.firstChild, 8);
    const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
    return window.quireStructuralAnchors.capture('chapter.xhtml');
  });
  const markup = await pageFor('<p>quote</p>');
  const second = await selectAndCapture(markup, 'p', 'p', 0, 5);
  assert.equal(first.anchor.quote, second.anchor.quote);
  assert.notEqual(first.anchor.documentHash, second.anchor.documentHash);
  await literal.close(); await markup.close();
});

test('XHTML CDATA is included in the structural binding even when native quote text omits it', async () => {
  const page = await pageFor('<p>alpha<![CDATA[beta]]>omega</p>', 'chapter.xhtml', 'application/xhtml+xml');
  const snapshot = await page.evaluate(() => {
    window.getSelection().selectAllChildren(document.querySelector('p'));
    return { nodes: Array.from(document.querySelector('p').childNodes).map(node => node.nodeType),
      snapshot: window.quireStructuralAnchors.capture('chapter.xhtml') };
  });
  assert.deepEqual(snapshot.nodes, [3,4,3]);
  assert.equal(snapshot.snapshot.anchor.rangeText, 'alphabetaomega');
  const result = await page.evaluate(dec => {
    document.querySelector('p').childNodes[1].nodeValue = 'changed';
    window.quireStructuralAnchors.prepare('test');
    const group = readium.getDecorations('test'); group.add(dec);
    return { count: group.items.length, rejected: window.quireStructuralAnchors.diagnostics('test').rejectedIds };
  }, decoration(snapshot.snapshot.anchor));
  assert.deepEqual(result, { count: 0, rejected: ['saved'] });
  await page.close();
});
