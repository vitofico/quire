import { useEffect, useRef, useState } from 'react'
import {
  type DiscoveryRequest, type DiscussLimits, DiscoveryHttpError, discoveryJson, jsonBody, translateRequestMessage,
} from './discovery/api'
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
const tooLong = 'These passages and questions are too long together. Select fewer passages or start again.'
type Answer = { answer: string; cited: number[] }

/** A 422 from this endpoint: a limit it went over (`{code, field, limit}`) or a body it could not read. */
function refusal(cause: unknown) {
  if (!(cause instanceof DiscoveryHttpError) || cause.status !== 422 || cause.detail === 'ai_provider_unknown') return
  return (cause.detail as { code?: string } | null)?.code === 'too_large'
    ? tooLong
    : 'The server could not use these passages or this question.'
}

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
  const panel = useRef<HTMLDialogElement>(null)
  const box = useRef<HTMLTextAreaElement>(null)
  const restarted = useRef(false)
  const left = limits.max_questions - exchanges.length
  const latest = exchanges.at(-1)
  const hasNotes = passages.some((p) => p.note)
  const notesSent = includeNotes && hasNotes

  useEffect(() => {
    // Beside the list on a wide screen; a modal full page on a phone, so nothing behind it is reachable.
    const dialog = panel.current!
    const phone = window.matchMedia('(max-width: 760px)')
    const place = () => {
      if (dialog.open) dialog.close()
      if (phone.matches) dialog.showModal()
      else dialog.show()
      heading.current?.focus()
    }
    place()
    phone.addEventListener('change', place)
    return () => phone.removeEventListener('change', place)
  }, [])
  useEffect(() => {
    // Escape closes from anywhere, unless something else already used it.
    const escape = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || e.isComposing || e.defaultPrevented || document.querySelector('dialog.ask[open]')) return
      e.preventDefault()
      close()
    }
    document.addEventListener('keydown', escape)
    return () => document.removeEventListener('keydown', escape)
  }, [close])
  useEffect(() => {
    // Start again, or a control that went away (a starter question, a disabled Ask), leaves focus nowhere.
    const lost = !document.activeElement || document.activeElement === document.body
    if (restarted.current || (!busy && lost)) (box.current ?? heading.current)?.focus()
    restarted.current = false
  }, [busy, exchanges.length])

  function ask(text: string) {
    const asked = text.trim()
    if (!asked || busy || left <= 0) return
    setProblem('')
    setStatus('')
    if (!navigator.onLine) return setProblem('You are offline. Connect to ask about these passages.')
    const { body, clipped, tooLarge } = discussBody(passages, exchanges, asked, includeNotes, limits)
    setClipped(clipped)
    if (tooLarge) return setProblem(tooLong)
    // Cleared while waiting, so a next question typed meanwhile survives; back in the box on failure.
    setQuestion('')
    const restore = () => setQuestion((typed) => typed || asked)
    void run(
      async (signal): Promise<Answer | { refused: string }> => {
        try {
          return await discoveryJson<Answer>(request, '/ai/v1/highlights/discuss', jsonBody('POST', body, signal))
        } catch (cause) {
          restore()
          const refused = refusal(cause)
          if (refused) return { refused }
          throw cause
        }
      },
      (value) => {
        if ('refused' in value) return setProblem(value.refused)
        setExchanges((list) => [...list, { question: asked, answer: value.answer, cited: value.cited }])
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

  function restart() {
    restarted.current = true
    setExchanges([])
    setStatus('')
    setProblem('')
    setClipped(false)
  }

  return (
    <dialog
      className="discuss panel"
      aria-labelledby="discuss-title"
      ref={panel}
      // Escape is handled above; the browser closing the dialog on its own would leave it hidden but open here.
      onCancel={(e) => e.preventDefault()}
      onClose={(e) => {
        if (!e.currentTarget.open) close()
      }}
    >
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
          {/* Fixed for the conversation, so every turn was asked with the same material. */}
          <input
            type="checkbox"
            checked={includeNotes}
            disabled={busy || exchanges.length > 0}
            onChange={(e) => setIncludeNotes(e.target.checked)}
          />
          {t('Include my notes')}
        </label>
      )}
      <p className="hint">
        {t(notesSent
          ? 'Sent to your server’s AI provider: the selected passages, your notes on them, and the books’ titles and authors.'
          : 'Sent to your server’s AI provider: the selected passages and the books’ titles and authors.')}
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
      <p role="status" className="discuss-status">{busy ? t('Thinking…') : status && t(status)}</p>
      {busy && <button type="button" className="ghost" onClick={cancel}>{t('Cancel')}</button>}
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
            <button className="primary" disabled={busy || !question.trim()}>{t('Ask')}</button>
            {latest && <span className="hint">{interpolate(t, 'Questions left: {count}', { count: left })}</span>}
          </div>
        </form>
      ) : (
        <p>
          {t('This conversation reached its question limit.')}{' '}
          <button type="button" onClick={restart}>{t('Start again')}</button>
        </p>
      )}
    </dialog>
  )
}
