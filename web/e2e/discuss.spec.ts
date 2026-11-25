import { expect, test, type Page } from '@playwright/test'

test.use({ actionTimeout: 10_000, navigationTimeout: 15_000 })

const dune = 'd'.repeat(64)
const swan = 'e'.repeat(64)
const limits = {
  max_highlights: 2, max_text_chars: 2000, max_note_chars: 1000, max_title_chars: 500, max_authors: 10,
  max_author_chars: 200, max_question_chars: 1000, max_answer_chars: 4000, max_questions: 6,
  max_total_chars: 60000, questions_per_minute: 10,
}

type Reply = { status?: number; json: unknown }

async function connect(page: Page, { discuss = true } = {}) {
  const bodies: unknown[] = []
  const replies: Reply[] = []
  await page.addInitScript(() => {
    Reflect.deleteProperty(Navigator.prototype, 'serviceWorker')
    // Keep what Copy writes where the test can read it, in every browser.
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: async (text: string) => { (window as unknown as { copied: string }).copied = text } },
    })
  })
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: false, ai: true, aiAvailable: true, login: 'native', password: true,
    oidc: false, catalogUrl: null, accountManagement: null,
  } }))
  await page.route('**/auth/v1/me', route => route.fulfill({ json: {
    version: 1, user: { id: 'reader', backend: 'native' }, csrf_token: 'test',
    capabilities: { progress_metadata_sync: false, ai_enabled: true },
  } }))
  await page.route('**/ai/v1/**', route => {
    switch (new URL(route.request().url()).pathname) {
      case '/ai/v1/config':
        return route.fulfill({ json: {
          configured: true, providers: [], highlights_discuss: discuss, highlights_discuss_limits: discuss ? limits : null,
        } })
      case '/ai/v1/preferences':
        return route.fulfill({ json: { ai_enabled: true, provider_id: 'default', style: { tone: 'neutral', language: 'auto' } } })
      case '/ai/v1/highlights/discuss': {
        expect(route.request().headers()['x-csrf-token']).toBe('test')
        bodies.push(route.request().postDataJSON())
        const reply = replies.shift() ?? { json: { answer: 'No more answers.', cited: [] } }
        return route.fulfill({ status: reply.status ?? 200, json: reply.json })
      }
      default:
        return route.fulfill({ status: 404 })
    }
  })
  await page.goto('/quire/#/highlights')
  await expect(page.getByRole('heading', { name: 'Highlights', level: 1 })).toBeVisible()
  // Three passages from two books, straight into this account's browser storage.
  await page.evaluate(async ({ dune, swan }) => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(`quire-web:${JSON.stringify([location.origin, 'connected', 'native', 'reader'])}`, 1)
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    const tx = db.transaction('localHighlights', 'readwrite')
    const rows = [
      { id: 'h1', hash: dune, text: 'Fear is the mind-killer.', title: 'Dune', author: 'Frank Herbert', comment: 'Compare with the Stoics.' },
      { id: 'h2', hash: dune, text: 'The spice must flow.', title: 'Dune', author: 'Frank Herbert' },
      { id: 'h3', hash: swan, text: 'Absence of evidence is not evidence of absence.', title: 'The Black Swan', author: 'Nassim Nicholas Taleb' },
    ]
    rows.forEach((row, index) => tx.objectStore('localHighlights').put({
      id: row.id, document: { content_hash: row.hash }, locator: '{}', colour: 'YELLOW', text: row.text,
      book_title: row.title, book_author: row.author, comment: row.comment ?? null, created_at: index, updated_at: index,
    }, row.id))
    await new Promise((resolve, reject) => { tx.oncomplete = resolve; tx.onabort = () => reject(tx.error) })
    db.close()
  }, { dune, swan })
  const settled = page.waitForResponse('**/ai/v1/preferences')
  await page.reload()
  await settled
  await expect(page.locator('.quote')).toHaveCount(3)
  return { bodies, replies }
}

const quote = (page: Page, text: string) => page.locator('.quote', { hasText: text })

test('select passages across books, ask, follow up without notes, and see an error in plain words', async ({ page }) => {
  const { bodies, replies } = await connect(page)
  replies.push(
    { json: { answer: 'Both warn against letting one force rule you.\n\nOne is about fear, one about knowledge.', cited: [2, 1] } },
    { json: { answer: 'Taleb would call it a fragile habit.', cited: [] } },
    { status: 409, json: { detail: 'ai_not_opted_in' } },
  )
  await page.getByRole('button', { name: 'Select', exact: true }).click()
  await quote(page, 'Fear is').getByRole('checkbox').check()
  await quote(page, 'Absence of evidence').getByRole('checkbox').check()
  await expect(page.getByText('2 selected')).toBeVisible()
  // The test server allows two: the third cannot be ticked, and the counter says why.
  await expect(page.getByText('You can select up to 2 passages.')).toBeVisible()
  await expect(quote(page, 'spice').getByRole('checkbox')).toBeDisabled()

  const opener = page.getByRole('button', { name: 'Think it through' })
  await opener.click()
  const panel = page.getByRole('region', { name: 'Think it through' })
  await expect(panel.getByRole('heading', { name: 'Think it through' })).toBeFocused()
  await expect(panel.locator('.discuss-passages li')).toHaveCount(2)

  await panel.getByRole('button', { name: 'What connects these?' }).click()
  const log = panel.getByRole('log')
  await expect(log.getByText('Both warn against letting one force rule you.')).toBeVisible()
  // Plain text, line breaks kept.
  await expect(log.locator('.discuss-answer')).toHaveCSS('white-space', 'pre-wrap')
  await expect.poll(() => bodies.length).toBe(1)
  expect(bodies[0]).toEqual({
    highlights: [
      { n: 1, text: 'Fear is the mind-killer.', note: 'Compare with the Stoics.', title: 'Dune', authors: ['Frank Herbert'] },
      { n: 2, text: 'Absence of evidence is not evidence of absence.', title: 'The Black Swan', authors: ['Nassim Nicholas Taleb'] },
    ],
    messages: [{ role: 'reader', content: 'What connects these?' }],
  })

  // A citation opens and focuses its passage in the panel.
  await panel.getByRole('button', { name: 'Passage 2' }).click()
  await expect(panel.locator('#discuss-passage-2 summary')).toBeFocused()
  await expect(panel.locator('#discuss-passage-2 blockquote')).toBeVisible()

  await panel.getByRole('button', { name: 'Copy' }).click()
  await expect(panel.getByText('Copied.')).toBeVisible()
  expect(await page.evaluate(() => (window as unknown as { copied: string }).copied)).toBe(
    'What connects these?\n\nBoth warn against letting one force rule you.\n\nOne is about fear, one about knowledge.\n\nPassages\n' +
    '1. "Absence of evidence is not evidence of absence." (The Black Swan, Nassim Nicholas Taleb)\n' +
    '2. "Fear is the mind-killer." (Dune, Frank Herbert)',
  )

  await panel.getByRole('checkbox', { name: 'Include my notes' }).uncheck()
  await panel.getByRole('textbox', { name: 'Follow-up question' }).fill('What would Taleb say?')
  await panel.getByRole('button', { name: 'Ask' }).click()
  await expect(log.getByText('Taleb would call it a fragile habit.')).toBeVisible()
  await expect(panel.getByText('Questions left: 4')).toBeVisible()
  await expect.poll(() => bodies.length).toBe(2)
  const second = bodies[1] as { highlights: object[]; messages: object[] }
  expect(second.highlights[0]).not.toHaveProperty('note')
  expect(second.messages).toEqual([
    { role: 'reader', content: 'What connects these?' },
    { role: 'assistant', content: 'Both warn against letting one force rule you.\n\nOne is about fear, one about knowledge.' },
    { role: 'reader', content: 'What would Taleb say?' },
  ])

  await panel.getByRole('textbox', { name: 'Follow-up question' }).fill('And the Stoics?')
  await panel.getByRole('button', { name: 'Ask' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Turn on AI in Settings to use this.')
  // The question stays in the box to ask again.
  await expect(panel.getByRole('textbox', { name: 'Follow-up question' })).toHaveValue('And the Stoics?')

  await page.keyboard.press('Escape')
  await expect(panel).toHaveCount(0)
  await expect(opener).toBeFocused()
})

test('the conversation is a full page at phone width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { replies } = await connect(page)
  replies.push({ json: { answer: 'A short answer.', cited: [1] } })
  await page.getByRole('button', { name: 'Select', exact: true }).click()
  await quote(page, 'spice').getByRole('checkbox').check()
  await page.getByRole('button', { name: 'Think it through' }).click()
  const panel = page.getByRole('region', { name: 'Think it through' })
  await panel.getByRole('button', { name: 'Summarise as a note' }).click()
  await expect(panel.getByText('A short answer.')).toBeVisible()
  const box = await panel.boundingBox()
  expect(box).toMatchObject({ x: 0, y: 0, width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)
  await panel.getByRole('button', { name: 'Close' }).click()
  await expect(page.getByRole('button', { name: 'Think it through' })).toBeFocused()
})

test('no Select without the server feature', async ({ page }) => {
  await connect(page, { discuss: false })
  await expect(page.getByRole('searchbox', { name: 'Search passages or comments' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Select', exact: true })).toHaveCount(0)
})
