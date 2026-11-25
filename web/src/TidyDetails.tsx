import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { CoverChoice, DetailsAnswer, ShelfBook, TidiedField } from './data'
import { type CoverPreview, type DetailsStore, type Proposal, foundCover, proposeDetails, withDetails } from './details'
import { type DiscoveryRequest, requestMessage, translateRequestMessage } from './discovery/api'
import { useAiOperation } from './discovery/aiOperation'
import { Cover, type LoadCover, authors } from './Shelf'

type Props = {
  book: ShelfBook
  store: DetailsStore | null
  /** Set when this server improves details and the reader opted in to AI. */
  request?: DiscoveryRequest
  loadCover?: LoadCover
  /** The server takes a cover choice. */
  coverChoice?: boolean
  /** The server paints covers. */
  painting?: boolean
  t: (key: string) => string
}

const fields: TidiedField[] = ['title', 'authors', 'series', 'description']
const labels: Record<TidiedField, string> = { title: 'Title', authors: 'Authors', series: 'Series', description: 'Description' }
/** Past this, a description shows trimmed until the reader expands it. */
const longText = 280

function valueOf(field: TidiedField, book: { title: string; authors?: string[]; author?: string | null; series_name?: string | null; series_index?: number | null; description?: string | null }) {
  if (field === 'title') return book.title
  if (field === 'authors') return book.authors?.length ? book.authors.join(', ') : book.author ?? ''
  if (field === 'series') return [book.series_name, book.series_index].filter((x) => x != null).join(' · ')
  return book.description ?? ''
}

/** The fields whose proposed value differs from what the reader sees now. */
export function changesOf(book: ShelfBook, answer: DetailsAnswer) {
  const shown = withDetails(book)
  return fields.filter((field) => valueOf(field, shown) !== valueOf(field, answer.book))
}

/** What a cover answer would show, to tell whether it differs from the cover shown now. */
const coverOf = (book: ShelfBook, answer: DetailsAnswer) =>
  answer.cover.url ?? (book.cover instanceof Blob && (answer.cover.source === 'file' || answer.cover.source === 'catalog') ? 'own' : 'none')
const coverNow = (book: ShelfBook) => foundCover(book) ?? (book.cover instanceof Blob ? 'own' : 'none')

/** Whether a tidy's answer is worth a preview: it changes something shown, or the reader may pick a cover. */
export const worthPreview = (book: ShelfBook, answer: DetailsAnswer, coverChoice?: boolean) =>
  !!coverChoice || !!changesOf(book, answer).length || coverOf(book, answer) !== coverNow(book)

/** The improvements kept: every field the answer leaves as shown, and the changes the reader ticked. */
export const acceptedOf = (changes: TidiedField[], ticked: Set<TidiedField>) =>
  fields.filter((field) => !changes.includes(field) || ticked.has(field))

/** The cover choice the preview starts on: the reader's last one, when this server still offers it. */
export function initialChoice(book: ShelfBook, coverChoice?: boolean, painting?: boolean): CoverChoice {
  const last = (coverChoice && book.details?.cover_choice) || 'keep'
  if (last === 'paint' && !painting) return 'draw'
  // Without a cover of its own, "keep" already finds one.
  return last === 'find' && !(book.cover instanceof Blob) ? 'keep' : last
}

const shownFields = ['title', 'authors', 'series_name', 'series_index', 'description', 'subjects', 'language', 'isbn', 'publisher'] as const

/** Whether the reader sees anything other than the book's own details and cover, so "Use original" would change it. */
export function differsFromOriginal(book: ShelfBook): boolean {
  if (!book.details?.answer) return false
  const improved = { ...book, details: { ...book.details, original: false } }
  const shown = withDetails(improved)
  // An empty list and no list look the same.
  const seen = (value: unknown) => JSON.stringify(Array.isArray(value) && !value.length ? null : value ?? null)
  return !!foundCover(improved) || shownFields.some((field) => seen(shown[field]) !== seen(book[field]))
}

/** "Improve details" on request, with a preview to accept or reject each change, and the switch to the book's own details. */
export function TidyDetails({ book, store, request, loadCover, coverChoice, painting, t }: Props) {
  const { run, busy, error } = useAiOperation(request, `details:${book.key}`)
  const [proposal, setProposal] = useState<Proposal>()
  const [said, setSaid] = useState<string>()
  const alive = useRef(true)
  const opener = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])
  // Leaving the page ends the preview, so the shelf goes on.
  useEffect(() => () => proposal?.close(), [proposal])
  // Focus goes back to the button once the preview, which made the page inert, is gone.
  const [returning, setReturning] = useState(false)
  useEffect(() => {
    if (!returning) return
    setReturning(false)
    opener.current?.focus()
  }, [returning])
  const record = book.details
  const changed = differsFromOriginal(book)
  if (!store || (!request && !changed)) return null
  const improve = () => {
    setSaid(undefined)
    // A tidy spends a generation: leaving the page does not abort it.
    void run(async (signal) => {
      const next = await proposeDetails(store, request!, book, signal)
      if (!alive.current) return next.close()
      if (!worthPreview(book, next.answer, coverChoice)) {
        next.close()
        setSaid(t('These details already look right.'))
      } else setProposal(next)
    }, () => undefined, {})
  }
  const finish = (message?: string) => {
    setProposal(undefined)
    setSaid(message)
    setReturning(true)
  }
  return (
    <section className="tidy">
      {request && <p className="hint">{t('Improving sends this book’s details to your configured AI service.')}</p>}
      <div className="actions">
        {request && (
          <button ref={opener} disabled={busy || !!proposal} onClick={improve}>
            {t('Improve details')}
          </button>
        )}
        {record && changed && (
          <button
            disabled={busy}
            onClick={() => void store.setDetails(book.key, { ...record, original: !record.original }).catch(() => undefined)}
          >
            {t(record.original ? 'Use improved details' : 'Use original')}
          </button>
        )}
      </div>
      {busy && <p role="status">{t('Improving details…')}</p>}
      {said && <p role="status">{said}</p>}
      {error && <p role="alert">{translateRequestMessage(t, error)}</p>}
      {proposal && (
        <Preview
          book={book} proposal={proposal} loadCover={loadCover} coverChoice={coverChoice} painting={painting} t={t}
          done={finish}
        />
      )}
    </section>
  )
}

type PreviewProps = {
  book: ShelfBook
  proposal: Proposal
  loadCover?: LoadCover
  coverChoice?: boolean
  painting?: boolean
  t: (key: string) => string
  /** The preview ended, saying what changed when something was applied. */
  done: (message?: string) => void
}

type Loaded = { preview?: CoverPreview; failed?: string }
const loading = (loaded?: Loaded) => !loaded?.preview && !loaded?.failed

/** The changes a tidy proposes, side by side with what the book shows now. */
function Preview({ book, proposal, loadCover, coverChoice, painting, t, done }: PreviewProps) {
  const dialog = useRef<HTMLDialogElement>(null)
  const changes = changesOf(book, proposal.answer)
  const own = book.cover instanceof Blob
  const shown = withDetails(book)
  const [ticked, setTicked] = useState(() => new Set(changes))
  const [expanded, setExpanded] = useState(false)
  const [choice, setChoice] = useState(() => initialChoice(book, coverChoice, painting))
  const [covers, setCovers] = useState<Partial<Record<CoverChoice, Loaded>>>({})
  const [keepCover, setKeepCover] = useState(true)
  const [applying, setApplying] = useState(false)
  const [failed, setFailed] = useState<string>()
  const newCover = coverOf(book, proposal.answer) !== coverNow(book)
  useEffect(() => {
    const element = dialog.current
    if (element && !element.open) element.showModal?.()
  }, [])
  useEffect(() => {
    if (covers[choice] && !covers[choice]!.failed) return
    const stop = new AbortController()
    setCovers((all) => ({ ...all, [choice]: {} }))
    proposal.cover(choice, stop.signal).then(
      (preview) => setCovers((all) => ({ ...all, [choice]: { preview } })),
      (error) => {
        if (!stop.signal.aborted) setCovers((all) => ({ ...all, [choice]: { failed: translateRequestMessage(t, requestMessage(error)) } }))
      },
    )
    return () => stop.abort()
    // A failed cover is asked for again only when the reader picks it again.
  }, [choice, proposal])
  const loaded = covers[choice]
  const cancel = () => {
    proposal.close()
    done()
  }
  const apply = async () => {
    setApplying(true)
    setFailed(undefined)
    const accepted = acceptedOf(changes, ticked)
    // A cover that failed to download leaves the cover as it is; the text changes still apply.
    const chosen = coverChoice ? loaded?.preview : keepCover ? covers.keep?.preview : undefined
    const now = coverChoice ? book.details?.cover_choice ?? 'keep' : 'keep'
    try {
      const details = await proposal.apply(accepted, chosen ? (coverChoice ? choice : 'keep') : now, chosen ?? null)
      if (!details) return done()
      const kept = changes.filter((field) => ticked.has(field)).map((field) => t(field))
      const parts = []
      if (kept.length) {
        const list = new Intl.ListFormat(document.documentElement.lang || 'en', { type: 'conjunction' }).format(kept)
        parts.push(t('Updated: {fields}.').replace('{fields}', list))
      }
      if (coverNow({ ...book, details }) !== coverNow(book)) parts.push(t('New cover.'))
      if (choice === 'paint' && coverChoice && chosen) {
        parts.push(t(details.answer?.cover.painting ? 'A painted cover is on its way.' : details.answer?.cover.painted ? 'New cover.' : 'No painting today: the drawn cover stays.'))
      }
      done([...new Set(parts)].join(' ') || t('Nothing changed.'))
    } catch (error) {
      setApplying(false)
      setFailed(translateRequestMessage(t, requestMessage(error)))
    }
  }
  const options: [CoverChoice, string, string?][] = [
    own ? ['keep', 'Keep this book’s cover'] : ['keep', 'Find a cover', 'From Open Library when it has one, else drawn by Quire.'],
    ...(own ? [['find', 'Find a cover', 'From Open Library when it has one, else drawn by Quire.'] as [CoverChoice, string, string]] : []),
    ['draw', 'Quire’s drawn cover'],
    ...(painting ? [['paint', 'A painted cover', 'Painting takes a while: the drawn cover shows until the painting arrives.'] as [CoverChoice, string, string]] : []),
  ]
  const long = changes.includes('description') &&
    Math.max(valueOf('description', shown).length, valueOf('description', proposal.answer.book).length) > longText
  const text = (value: string) => value ? <p className={long && !expanded ? 'clamp' : undefined}>{value}</p> : <p className="muted">{t('Empty')}</p>
  return (
    <dialog
      ref={dialog}
      className="sheet"
      aria-labelledby={`improve-${book.key}`}
      onCancel={(event) => {
        event.preventDefault()
        if (!applying) cancel()
      }}
    >
      <div className="sheet-body">
        <h2 id={`improve-${book.key}`}>{t('Improve details')}</h2>
        {changes.length ? <p className="hint">{t('Tick the changes to keep. The rest stays as it is.')}</p>
          : <p>{t('These details already look right.')}</p>}
        {changes.map((field) => (
          <div className="change" key={field}>
            <label className="check">
              <input
                type="checkbox"
                checked={ticked.has(field)}
                onChange={(event) => {
                  const next = new Set(ticked)
                  if (event.target.checked) next.add(field)
                  else next.delete(field)
                  setTicked(next)
                }}
              />
              <strong>{t(labels[field])}</strong>
            </label>
            <Compare t={t} now={text(valueOf(field, shown))} proposed={text(valueOf(field, proposal.answer.book))} />
            {field === 'description' && long && (
              <button className="quiet" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
                {t(expanded ? 'Show less' : 'Show more')}
              </button>
            )}
          </div>
        ))}
        {coverChoice ? (
          <fieldset className="change">
            <legend>{t('Cover')}</legend>
            {options.map(([value, label, hint]) => (
              <label className="check" key={value}>
                <input type="radio" name={`cover-${book.key}`} value={value} checked={choice === value} onChange={() => setChoice(value)} />
                <span>
                  {t(label)}
                  {hint && <small className="hint">{t(hint)}</small>}
                </span>
              </label>
            ))}
            <Compare
              t={t}
              now={<Cover book={book} load={loadCover} />}
              proposed={<Proposed book={book} loaded={loaded} loadCover={loadCover} t={t} />}
            />
          </fieldset>
        ) : newCover && (
          <div className="change">
            <label className="check">
              <input type="checkbox" checked={keepCover} onChange={(event) => setKeepCover(event.target.checked)} />
              <strong>{t('Cover')}</strong>
            </label>
            <Compare
              t={t}
              now={<Cover book={book} load={loadCover} />}
              proposed={<Proposed book={book} loaded={covers.keep} loadCover={loadCover} t={t} />}
            />
          </div>
        )}
      </div>
      <div className="sheet-actions">
        {failed && <p role="alert">{failed}</p>}
        <div className="actions">
          <button disabled={applying} onClick={cancel}>{t('Cancel')}</button>
          <button
            className="primary"
            disabled={applying || (coverChoice ? loading(loaded) : keepCover && newCover && loading(covers.keep))}
            onClick={() => void apply()}
          >
            {t('Apply')}
          </button>
        </div>
      </div>
    </dialog>
  )
}

function Compare({ now, proposed, t }: { now: ReactNode; proposed: ReactNode; t: (key: string) => string }) {
  return (
    <div className="compare">
      <div>
        <small>{t('Current')}</small>
        {now}
      </div>
      <div>
        <small>{t('Proposed')}</small>
        {proposed}
      </div>
    </div>
  )
}

/** The cover a choice gives: its new image, the one this browser keeps, the book's own, or none. */
function Proposed({ book, loaded, loadCover, t }: { book: ShelfBook; loaded?: Loaded; loadCover?: LoadCover; t: (key: string) => string }) {
  const preview = loaded?.preview
  const [url, setUrl] = useState('')
  useEffect(() => {
    let source = '', live = true
    const show = (blob?: Blob) => {
      if (!live || !blob) return
      source = URL.createObjectURL(blob)
      setUrl(source)
    }
    setUrl('')
    const own = book.cover instanceof Blob ? book.cover : undefined
    if (preview?.image) show(preview.image)
    else if (preview?.kept && loadCover) loadCover(book.key).then(show, () => undefined)
    else if (preview && !preview.answer.cover.url) show(own)
    return () => {
      live = false
      if (source) URL.revokeObjectURL(source)
    }
  }, [preview, book.key, book.cover, loadCover])
  if (loaded?.failed) return <p role="alert">{loaded.failed}</p>
  if (!preview) return <p className="muted">{t('Loading cover…')}</p>
  const cover = preview.answer.cover
  return (
    <>
      <span className={'cover' + (url ? ' with-image' : '')}>
        {url ? <img src={url} alt="" /> : <span>{book.title} {authors(book) && <small>{authors(book)}</small>}</span>}
      </span>
      {cover.generated ? <small className="origin">{t('Generated cover')}</small>
        : cover.source === 'openlibrary' && <small className="origin">{t('From Open Library')}</small>}
    </>
  )
}
