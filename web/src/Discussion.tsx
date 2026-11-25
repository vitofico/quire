import { useEffect, useRef, useState } from 'react'
import { type DiscoveryRequest, type DiscussLimits, discoveryJson, jsonBody, translateRequestMessage } from './discovery/api'
import { useAiOperation } from './discovery/aiOperation'
import { interpolate } from './discovery/translation'
import { type Exchange, type Passage, discussBody, shareNote } from './discuss'

type Props = {
  request: DiscoveryRequest
  limits: DiscussLimits
  passages: Passage[]
  t: (key: string) => string
  close: () => void
}

const starters = ['What connects these?', 'Where do they disagree?', 'Summarise as a note']

/** A short conversation about the selected passages. Nothing is kept: closing discards it. */
export function Discussion({ request, limits, passages, t, close }: Props) {
  const { run, busy, error, cancel } = useAiOperation(request, 'highlights-discuss')
  const [exchanges, setExchanges] = useState<Exchange[]>([])
  const [question, setQuestion] = useState('')
  const [includeNotes, setIncludeNotes] = useState(true)
  const [problem, setProblem] = useState('')
  const [status, setStatus] = useState('')
  const [clipped, setClipped] = useState(false)
  const heading = useRef<HTMLHeadingElement>(null)
  const panel = useRef<HTMLElement>(null)
  const box = useRef<HTMLTextAreaElement>(null)
  const left = limits.max_questions - exchanges.length
  const latest = exchanges.at(-1)
  const hasNotes = passages.some((p) => p.note)

  useEffect(() => heading.current?.focus(), [])
  useEffect(() => {
    // Escape closes from anywhere, even once focus has left a control that went away.
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !document.querySelector('dialog[open]')) close()
    }
    document.addEventListener('keydown', escape)
    return () => document.removeEventListener('keydown', escape)
  }, [close])
  useEffect(() => {
    // A starter question goes away with the first answer: keep focus in the panel.
    if (exchanges.length && !panel.current?.contains(document.activeElement)) (box.current ?? heading.current)?.focus()
  }, [exchanges.length])

  function ask(text: string) {
    if (!text.trim() || busy || left <= 0) return
    setProblem('')
    setStatus('')
    if (!navigator.onLine) return setProblem('You are offline. Connect to ask about these passages.')
    const { body, clipped, tooLarge } = discussBody(passages, exchanges, text, includeNotes, limits)
    setClipped(clipped)
    if (tooLarge) return setProblem('These passages and questions are too long together. Select fewer passages or start again.')
    void run(
      (signal) => discoveryJson<{ answer: string; cited: number[] }>(request, '/ai/v1/highlights/discuss', jsonBody('POST', body, signal)),
      ({ answer, cited }) => {
        setExchanges((list) => [...list, { question: text.trim(), answer, cited }])
        setQuestion('')
      },
    )
  }

  function show(n: number) {
    const item = document.getElementById(`discuss-passage-${n}`) as HTMLDetailsElement | null
    if (!item) return
    item.open = true
    item.classList.remove('flash')
    void item.offsetWidth // restart the flash when the same passage is shown twice
    item.classList.add('flash')
    item.scrollIntoView({ block: 'nearest' })
    item.querySelector('summary')?.focus()
  }

  async function send(how: 'share' | 'copy') {
    if (!latest) return
    const text = shareNote(latest, passages, t('Passages'))
    try {
      if (how === 'share') await navigator.share({ text })
      else {
        await navigator.clipboard.writeText(text)
        setStatus('Copied.')
      }
    } catch (e) {
      if ((e as Error).name !== 'AbortError') setProblem('Quire could not copy or share this answer.')
    }
  }

  return (
    <section className="discuss panel" aria-labelledby="discuss-title" ref={panel}>
      <div className="discuss-head">
        <h2 id="discuss-title" ref={heading} tabIndex={-1}>{t('Think it through')}</h2>
        <button type="button" className="ghost" onClick={close}>{t('Close')}</button>
      </div>
      <p className="hint">{t('Nothing here is saved. Closing the panel discards the conversation.')}</p>
      <h3>{t('Selected passages')}</h3>
      <ol className="discuss-passages">
        {passages.map((p) => (
          <li key={p.n}>
            <details id={`discuss-passage-${p.n}`} onAnimationEnd={(e) => e.currentTarget.classList.remove('flash')}>
              <summary><span className="discuss-n">{p.n}</span><span className="discuss-line">{p.text}</span></summary>
              <blockquote>{p.text}</blockquote>
              {p.note && <p>{p.note}</p>}
              <p className="hint">{[p.title, p.authors.join(', ')].filter(Boolean).join(', ')}</p>
            </details>
          </li>
        ))}
      </ol>
      {clipped && <p className="hint">{t('Some long passages were shortened to fit the server’s limit.')}</p>}
      {hasNotes && (
        <label className="check">
          <input type="checkbox" checked={includeNotes} onChange={(e) => setIncludeNotes(e.target.checked)} />
          {t('Include my notes')}
        </label>
      )}
      <p className="hint">
        {t(includeNotes && hasNotes
          ? 'Sent to your server’s AI provider: the selected passages, your notes on them, and the books’ titles.'
          : 'Sent to your server’s AI provider: the selected passages and the books’ titles.')}
      </p>
      <div className="discuss-log" role="log" aria-live="polite" aria-label={t('Conversation')}>
        {exchanges.map((e, index) => (
          <div className="discuss-turn" key={index}>
            <p className="discuss-question">{e.question}</p>
            <p className="discuss-answer">{e.answer}</p>
            {e.cited.length > 0 && (
              <p className="discuss-cited">
                {t('Based on passages')}{' '}
                {e.cited.map((n) => (
                  <button type="button" key={n} className="discuss-ref" aria-label={interpolate(t, 'Passage {n}', { n })} onClick={() => show(n)}>
                    {n}
                  </button>
                ))}
              </p>
            )}
          </div>
        ))}
      </div>
      {latest && (
        <div className="actions">
          {'share' in navigator && <button type="button" onClick={() => void send('share')}>{t('Share')}</button>}
          <button type="button" onClick={() => void send('copy')}>{t('Copy')}</button>
        </div>
      )}
      {busy && (
        <p role="status">
          {t('Thinking…')} <button type="button" className="ghost" onClick={cancel}>{t('Cancel')}</button>
        </p>
      )}
      {status && <p role="status">{t(status)}</p>}
      {(problem || error) && <p role="alert">{problem ? t(problem) : translateRequestMessage(t, error!)}</p>}
      {left > 0 ? (
        <form
          className="discuss-ask"
          onSubmit={(e) => {
            e.preventDefault()
            ask(question)
          }}
        >
          {!latest && (
            <div className="chips">
              {starters.map((s) => (
                <button type="button" className="chip" key={s} disabled={busy} onClick={() => ask(t(s))}>{t(s)}</button>
              ))}
            </div>
          )}
          <label className="field">
            {t(latest ? 'Follow-up question' : 'Your question')}
            <textarea ref={box} value={question} maxLength={limits.max_question_chars} rows={2} onChange={(e) => setQuestion(e.target.value)} />
          </label>
          <div className="actions">
            <button className="primary">{t('Ask')}</button>
            {latest && <span className="hint">{interpolate(t, 'Questions left: {count}', { count: left })}</span>}
          </div>
        </form>
      ) : (
        <p>
          {t('This conversation reached its question limit.')}{' '}
          <button type="button" onClick={() => setExchanges([])}>{t('Start again')}</button>
        </p>
      )}
    </section>
  )
}
