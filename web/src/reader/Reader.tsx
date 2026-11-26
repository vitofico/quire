import { useEffect, useRef, useState, type ChangeEvent, type CSSProperties, type KeyboardEvent } from 'react'
import type { Replica } from '../data'
import type { Epub } from '../books/epub'
import { aligned, captureRange, chapterProgress, currentPage, displayRange, pageCount, parseLocator, readAnchor, resolveRange, showElement, showPage, startOf, viewportRange, type Locator } from './anchors'
import { chapterShell, readerSandbox, jumpFragment, mountChapter, chapterSearchText } from './publication'
import { chapterStyles, palettes, sanitizePreferences, type PauseBookmark, type ReaderPreferences } from './preferences'
import { findMatches, matchRange, searchableText } from './search'
import { loadReaderFonts } from './fonts'
import { readerStrings } from './strings'
import { clickTurn } from './clickTurn'
import { scrollsInside, wheelGesture } from './wheelGesture'
import { Icon, type IconName } from '../icons'
import { ask } from '../ask'
import './reader.css'

const colours = ['YELLOW', 'PINK', 'GREEN'] as const
export type ReaderColour = typeof colours[number]
export interface ReaderHighlight {
  id: string
  locator: string
  text: string
  colour: string
  comment?: string | null
}

interface Props {
  epub: Epub
  readerStorage: Replica
  bookId: string
  title: string
  author?: string | null
  locator?: string | null
  highlights?: ReaderHighlight[]
  jumpTo?: ReaderHighlight | null
  onProgress(locator: string, progression: number): void | Promise<void>
  onCreateHighlight(input: { locator: string; text: string; colour: ReaderColour; comment: string | null }): void | Promise<void>
  onEditHighlight?(id: string, patch: { colour?: ReaderColour; comment?: string | null }): void | Promise<void>
  onDeleteHighlight?(id: string): void | Promise<void>
  onBack(): void
  locale?: string
}

interface Destination {
  index: number
  locator?: Locator
  fragment?: string
  fraction?: number
  find?: { query: string; occurrence: number }
  highlight?: ReaderHighlight
  keepProgress?: boolean
}

type Panel = 'contents' | 'find' | 'passages' | 'type' | 'pause'
type ChapterDoc = { doc: Document; index: number }
type HighlightWindow = Window & {
  Highlight?: new (...ranges: Range[]) => object
  CSS?: { highlights?: Map<string, object> }
}

const emptyHighlights: ReaderHighlight[] = []
const clamp = (value: number) => Math.min(1, Math.max(0, Number.isFinite(value) ? value : 0))

function normalizedHref(href: string) {
  const path = href.split('#', 1)[0].replace(/^\/+|^\.\//g, '')
  try { return decodeURIComponent(path) } catch { return path }
}

function resourceIndex(epub: Epub, href: string) {
  return epub.spine.findIndex(chapter => normalizedHref(chapter.href) === normalizedHref(href))
}

function locatorAt(epub: Epub, index: number, fraction: number): Locator {
  const chapter = epub.spine[index]
  return {
    href: chapter.href,
    type: chapter.mediaType,
    locations: { progression: fraction, totalProgression: clamp((index + fraction) / epub.spine.length) },
  }
}

function paint(doc: Document, name: string, ranges: Range[]) {
  const frame = doc.defaultView as HighlightWindow | null
  const registry = frame?.CSS?.highlights
  if (frame?.Highlight && registry) {
    if (ranges.length) registry.set(name, new frame.Highlight(...ranges))
    else registry.delete(name)
  }
}


function restoreLegacy(doc: Document, locator: Locator): boolean {
  const selector = locator.locations?.cssSelector
  if (typeof selector === 'string' && typeof locator.text?.highlight === 'string') {
    try {
      const node = doc.querySelector(selector)
      if (node && node.textContent?.trim() === locator.text.highlight.trim()) {
        showElement(doc, node)
        return true
      }
    } catch { /* Invalid selectors fall back to chapter progression. */ }
  }
  const fragments = locator.locations?.fragments
  return Array.isArray(fragments)
    && fragments.some((value: unknown) => typeof value === 'string' && !!jumpFragment(doc, value))
}

function comparePassages(epub: Epub, first: ReaderHighlight, second: ReaderHighlight) {
  const a = parseLocator(first.locator)
  const b = parseLocator(second.locator)
  const firstIndex = a ? resourceIndex(epub, a.href) : -1
  const secondIndex = b ? resourceIndex(epub, b.href) : -1
  const chapterDifference = (firstIndex < 0 ? Infinity : firstIndex) - (secondIndex < 0 ? Infinity : secondIndex)
  if (chapterDifference) return chapterDifference
  const x = readAnchor(a)?.start
  const y = readAnchor(b)?.start
  if (!Array.isArray(x?.path) || !Array.isArray(y?.path)) return 0
  for (let i = 0; i < Math.min(x.path.length, y.path.length); i++) {
    if (x.path[i] !== y.path[i]) return x.path[i] - y.path[i]
  }
  if (x.path.length < y.path.length) return x.offset <= y.path[x.path.length] ? -1 : 1
  if (x.path.length > y.path.length) return y.offset <= x.path[y.path.length] ? 1 : -1
  return x.offset - y.offset
}

export default function Reader({
  epub, readerStorage, bookId, title, author, locator, highlights = emptyHighlights, jumpTo,
  onProgress, onCreateHighlight, onEditHighlight, onDeleteHighlight, onBack, locale,
}: Props) {
  const t = readerStrings(locale ?? document.documentElement.lang)
  // Palette and colour values are stored identifiers; their labels are sentence case.
  const option = (value: string) => t(value[0].toUpperCase() + value.slice(1).toLowerCase())
  const [preferences, setPreferences] = useState(() => sanitizePreferences(undefined))
  const preferencesRef = useRef(preferences)
  preferencesRef.current = preferences
  const [pause, setPause] = useState<PauseBookmark>()
  const [note, setNote] = useState('')
  const [settingsReady, setSettingsReady] = useState(false)
  const [panel, setPanel] = useState<Panel | null>(null)
  const [notice, setNotice] = useState('')
  const [external, setExternal] = useState<string | null>(null)
  // `low`: the selection leaves more room above it than below, so the bars are drawn over the top.
  const [selected, setSelected] = useState<{ range: Range; quote: string; low: boolean } | null>(null)
  const [comment, setComment] = useState('')
  const [colour, setColour] = useState<ReaderColour>('YELLOW')
  const [editing, setEditing] = useState<string | null>(null)
  const [openedPassage, setOpenedPassage] = useState<ReaderHighlight | null>(null)
  const [busy, setBusy] = useState(false)
  const [query, setQuery] = useState('')
  const [searchResults, setSearchResults] = useState<{index: number; occurrence: number}[]>([])
  const [searchPending, setSearchPending] = useState(false)
  const [result, setResult] = useState(-1)
  const frame = useRef<HTMLIFrameElement>(null)
  const reader = useRef<HTMLElement>(null)
  const panelHeading = useRef<HTMLHeadingElement>(null)
  const findInput = useRef<HTMLInputElement>(null)
  const panelTrigger = useRef<HTMLButtonElement>(null)
  const handleReaderKey = useRef(keyAction)
  handleReaderKey.current = keyAction
  useEffect(() => { reader.current?.focus() }, [])
  useEffect(() => {
    if (panel) (panel === 'find' ? findInput.current : panelHeading.current)?.focus()
  }, [panel])
  const style = useRef<HTMLStyleElement | null>(null)
  const readerFonts = useRef('')
  const warm = useRef<Range | undefined>(undefined)
  const latest = useRef<Locator | undefined>(undefined)
  const history = useRef<Locator[]>([])
  const captureSequence = useRef(0)
  const intentional = useRef(false)
  const pendingNavigation = useRef(false)
  // Outlives a chapter change, so the tail of the swipe that crossed it turns no second page.
  const wheelTurn = useRef(wheelGesture())
  const [loaded, setLoaded] = useState(false)
  const [active, setActive] = useState<ChapterDoc | null>(null)
  const [destination, setDestination] = useState<Destination>(() => {
    const saved = parseLocator(locator)
    const index = saved ? resourceIndex(epub, saved.href) : 0
    return {index: Math.max(0, index), locator: index >= 0 ? saved : undefined, keepProgress: true}
  })
  const [progress, setProgress] = useState(() => clamp(parseLocator(locator)?.locations?.totalProgression ?? 0))
  const index = destination.index

  // The same frame is kept mounted while reading. Its document contains only
  // the inert shell, trusted stylesheet and sanitized publication nodes.
  useEffect(() => {
    const doc = frame.current?.contentDocument
    if (!loaded || !doc?.body) return
    let dispose: (() => void) | undefined
    try {
      const stylesheet = doc.createElement('style')
      doc.head.append(stylesheet)
      style.current = stylesheet
      stylesheet.textContent = readerFonts.current + chapterStyles(preferencesRef.current)
      dispose = mountChapter(doc, epub, epub.spine[index].href)
      doc.title = title
      setActive({doc, index})
      return () => {
        dispose?.()
        stylesheet.remove()
        doc.getSelection()?.removeAllRanges()
        setActive(null)
      }
    } catch {
      dispose?.()
      style.current?.remove()
      setNotice(t('Chapter unavailable'))
      setActive(null)
    }
  }, [loaded, epub, index, title])

  const callbacks = useRef({ onProgress, t })
  callbacks.current = { onProgress, t }

  useEffect(() => {
    let cancelled = false
    void Promise.all([
      readerStorage.getPreference<ReaderPreferences>('reader'),
      readerStorage.getBookmark<PauseBookmark>(bookId),
    ]).then(([storedPreferences, bookmark]) => {
      if (cancelled) return
      const next = sanitizePreferences(storedPreferences)
      const doc = frame.current?.contentDocument
      if (style.current) style.current.textContent = readerFonts.current + chapterStyles(next)
      if (doc && warm.current && doc.body.contains(warm.current.startContainer)) displayRange(doc, warm.current)
      setPreferences(next)
      setPause(bookmark)
      setNote(bookmark?.note ?? '')
    }).catch(() => {
      if (!cancelled) setNotice(callbacks.current.t('Could not load reader settings'))
    }).finally(() => {
      if (!cancelled) setSettingsReady(true)
    })
    return () => { cancelled = true }
  }, [readerStorage, bookId])

  useEffect(() => {
    const controller = new AbortController()
    let release: (() => void) | undefined
    void loadReaderFonts(controller.signal).then(fonts => {
      if (controller.signal.aborted) {
        fonts.release()
        return
      }
      release = fonts.release
      readerFonts.current = fonts.css
      const doc = frame.current?.contentDocument
      if (!doc || !style.current) return
      style.current.textContent = fonts.css + chapterStyles(preferencesRef.current)
      void doc.fonts.load(`${preferencesRef.current.size}px "Quire Literata"`).then(() => {
        const range = warm.current
        if (!controller.signal.aborted && !pendingNavigation.current && range && doc.body.contains(range.startContainer)) {
          displayRange(doc, range)
        }
      }).catch(() => {})
    }).catch(() => { /* The system serif remains available offline and on font errors. */ })
    return () => { controller.abort(); release?.() }
  }, [])

  function baseLocator(doc: Document) {
    const fraction = clamp(chapterProgress(doc))
    return locatorAt(epub, index, fraction)
  }

  function remember(doc: Document) {
    warm.current = viewportRange(doc)
    return warm.current
  }

  async function saveLocation(next: Locator) {
    const total = next.locations?.totalProgression ?? 0
    setProgress(total)
    await callbacks.current.onProgress(JSON.stringify(next), total)
  }

  async function report(doc: Document, refine = false) {
    const token = ++captureSequence.current
    const range = viewportRange(doc)
    const base = baseLocator(doc)
    const viewport = range ? await captureRange(doc, base, range, range.toString(), true) : undefined
    if (token !== captureSequence.current || frame.current?.contentDocument !== doc || pendingNavigation.current) return
    const next = viewport ?? base
    latest.current = next
    if (refine && !viewport) return
    try { await saveLocation(next) }
    catch { setNotice(callbacks.current.t('Could not save reading position')) }
  }

  function go(target: Destination) {
    if (target.index < 0 || target.index >= epub.spine.length) return
    captureSequence.current++
    pendingNavigation.current = true
    intentional.current = false
    setSelected(null)
    setExternal(null)
    setNotice('')
    if (!target.keepProgress) {
      // Start durable storage on the user's input event. The destination must
      // survive an immediate reload even before its exact DOM range is available.
      const provisional = target.locator ?? locatorAt(epub, target.index, clamp(target.fraction ?? 0))
      const location = target.fragment
        ? {...provisional, locations: {...provisional.locations, fragments: [target.fragment]}}
        : provisional
      void saveLocation(location).catch(() => setNotice(callbacks.current.t('Could not save reading position')))
    }
    setDestination(target)
  }

  async function openHighlight(item: ReaderHighlight) {
    const saved = parseLocator(item.locator)
    const target = saved ? resourceIndex(epub, saved.href) : -1
    setOpenedPassage(item)
    if (target < 0 || !saved) {
      setPanel('passages')
      setNotice(t('Passage location unavailable'))
      return
    }
    // A changed or mismatched edition should leave the current page in place.
    const token = ++captureSequence.current
    const validation = document.implementation.createHTMLDocument('')
    let release: (() => void) | undefined
    try {
      release = mountChapter(validation, epub, epub.spine[target].href, false)
      const exact = await resolveRange(validation, saved, item.text)
      if (token !== captureSequence.current) return
      if (exact) go({ index: target, locator: saved, highlight: item, keepProgress: true })
      else {
        setPanel('passages')
        setNotice(t('Passage location unavailable'))
      }
    } catch {
      if (token === captureSequence.current) {
        setPanel('passages')
        setNotice(t('Passage location unavailable'))
      }
    } finally {
      release?.()
    }
  }

  useEffect(() => {
    if (jumpTo) void openHighlight(jumpTo)
  }, [jumpTo])

  useEffect(() => {
    if (!active || active.index !== index) return
    const { doc } = active
    let cancelled = false
    const restore = async () => {
      pendingNavigation.current = true
      intentional.current = false
      paint(doc, 'quire-active', [])
      const target = destination.locator
      const exact = target && await resolveRange(doc, target, destination.highlight?.text)
      if (cancelled) return
      // What a reflow keeps on screen: the passage navigated to, else the top of the page.
      let shown: Range | undefined
      if (destination.highlight && !exact) {
        setNotice(callbacks.current.t('Passage location unavailable'))
        setPanel('passages')
      } else if (exact) {
        displayRange(doc, exact)
        shown = exact
        if (destination.highlight) paint(doc, 'quire-active', [exact])
      } else if (destination.find) {
        const { text, chunks } = searchableText(doc.body)
        const match = findMatches(text, destination.find.query, 1000)[destination.find.occurrence]
        const range = match && matchRange(doc, chunks, match)
        if (range) {
          displayRange(doc, range)
          shown = range
          paint(doc, 'quire-active', [range])
        }
      } else {
        const element = jumpFragment(doc, destination.fragment)
        if (element) shown = startOf(doc, element)
        else if (!(target && restoreLegacy(doc, target))) {
          const fraction = clamp(target?.locations?.progression ?? destination.fraction ?? 0)
          showPage(doc, Math.round(fraction * (pageCount(doc) - 1)))
          if (target) setNotice(callbacks.current.t('Restored approximate position'))
        }
      }
      warm.current = shown ?? viewportRange(doc)
      pendingNavigation.current = false
      if (!destination.keepProgress) void report(doc, true)
    }
    const request = requestAnimationFrame(() => void restore())
    return () => { cancelled = true; cancelAnimationFrame(request) }
  }, [active, destination])

  useEffect(() => {
    if (!active) return
    let cancelled = false
    const { doc, index: chapter } = active
    const items = highlights.filter(item => {
      const saved = parseLocator(item.locator)
      return saved && resourceIndex(epub, saved.href) === chapter
    })
    void Promise.all(items.map(async item => {
      const saved = parseLocator(item.locator)!
      return { colour: item.colour, range: await resolveRange(doc, saved, item.text) }
    })).then(resolved => {
      if (cancelled) return
      for (const item of colours) {
        paint(doc, 'quire-' + item.toLowerCase(), resolved
          .filter(value => value.colour === item && value.range).map(value => value.range!))
      }
    })
    return () => {
      cancelled = true
      for (const item of colours) paint(doc, 'quire-' + item.toLowerCase(), [])
    }
  }, [active, highlights, epub])

  function page(direction: number) {
    const doc = active?.doc
    if (!doc || pendingNavigation.current) return
    const current = currentPage(doc)
    if ((direction > 0 && current >= pageCount(doc) - 1) || (direction < 0 && current <= 0)) {
      const next = index + direction
      if (next >= 0 && next < epub.spine.length) go({index: next, fraction: direction > 0 ? 0 : 1})
      else if (direction > 0 && index === epub.spine.length - 1) {
        const completion = locatorAt(epub, index, 1)
        latest.current = completion
        saveLocation(completion)
          .catch(() => setNotice(callbacks.current.t('Could not save reading position')))
      }
      return
    }
    intentional.current = true
    showPage(doc, current + direction)
    remember(doc)
  }

  function closePanel() {
    setPanel(null)
    panelTrigger.current?.focus()
  }

  function keyAction(event: globalThis.KeyboardEvent | KeyboardEvent) {
    if (event.altKey || event.ctrlKey || event.metaKey || event.defaultPrevented) return
    if (event.key === 'Escape' && (panel || external)) {
      event.preventDefault()
      if (panel) closePanel()
      setOpenedPassage(null)
      setExternal(null)
      return
    }
    const target = event.target as Element | null
    if (target?.matches?.('input,textarea,select,button,a,[contenteditable]')) return
    if (event.shiftKey && event.key !== ' ') return
    const rtl = active?.doc.defaultView?.getComputedStyle(active.doc.body).direction === 'rtl'
    const keys: Record<string, number> = {PageDown: 1, PageUp: -1, ArrowRight: rtl ? -1 : 1, ArrowLeft: rtl ? 1 : -1, ArrowDown: 1, ArrowUp: -1, ' ': event.shiftKey ? -1 : 1}
    if (event.key in keys) {
      event.preventDefault()
      page(keys[event.key])
    }
  }

  useEffect(() => {
    if (!active) return
    const { doc } = active
    let timer: number | undefined
    let resizeFrame = 0
    let width = doc.documentElement.clientWidth
    let height = doc.documentElement.clientHeight
    let touch: { x: number; y: number } | undefined
    const deliberate = () => { intentional.current = true }
    const scroll = () => {
      // Focus moves and find-in-page can scroll part way: settle on a whole page.
      if (!aligned(doc)) {
        const focused = doc.activeElement
        if (focused && focused !== doc.body && doc.body.contains(focused)) {
          showElement(doc, focused)
          remember(doc)
        } else showPage(doc, currentPage(doc)) // A resize puts the remembered passage back itself.
      }
      if (pendingNavigation.current || !intentional.current) return
      clearTimeout(timer)
      const token = captureSequence.current
      timer = window.setTimeout(() => {
        if (!intentional.current || token !== captureSequence.current) return
        intentional.current = false
        void report(doc)
      }, 140)
    }
    const resize = () => {
      const newWidth = doc.documentElement.clientWidth
      const newHeight = doc.documentElement.clientHeight
      if (width === newWidth && height === newHeight) return
      width = newWidth
      height = newHeight
      if (pendingNavigation.current) return
      intentional.current = false
      clearTimeout(timer)
      cancelAnimationFrame(resizeFrame)
      resizeFrame = requestAnimationFrame(() => {
        const range = warm.current
        if (!pendingNavigation.current && range && doc.body.contains(range.startContainer)) displayRange(doc, range)
      })
    }
    const selectionChanged = () => {
      const selection = doc.getSelection()
      const quote = selection?.toString() ?? ''
      if (selection && !selection.isCollapsed && selection.rangeCount === 1 && quote.trim()) {
        const range = selection.getRangeAt(0).cloneRange()
        const box = range.getBoundingClientRect()
        const low = box.top > doc.documentElement.clientHeight - box.bottom
        setSelected({ range, quote, low })
        setComment('')
      } else {
        // Keep the captured range when the toolbar takes focus away from the iframe.
        if (doc.hasFocus()) setSelected(null)
      }
    }
    let dismissing = false
    const press = () => {
      // A click that only puts a selection away is not a page turn.
      dismissing = !!doc.getSelection()?.toString()
      deliberate()
    }
    // In full screen the page's own edges turn it; elsewhere a click is only a click.
    const edgeTurn = (event: MouseEvent) =>
      document.fullscreenElement !== reader.current || event.button !== 0 ||
        event.ctrlKey || event.metaKey || event.shiftKey || event.altKey ||
        (event.target as Element | null)?.closest?.('a[href], button, input, select, textarea, summary, audio, video')
        ? 0 : clickTurn(event.clientX, doc.documentElement.clientWidth)
    const mouseDown = (event: MouseEvent) => {
      // Quick clicks on an edge keep turning pages: without this the second one selects a word.
      if (event.detail > 1 && edgeTurn(event)) event.preventDefault()
    }
    const clickPage = (event: MouseEvent) => {
      const turn = edgeTurn(event)
      if (!turn || dismissing || doc.getSelection()?.toString()) return
      const rtl = doc.defaultView?.getComputedStyle(doc.body).direction === 'rtl'
      page(turn * (rtl ? -1 : 1))
    }
    const link = (event: MouseEvent) => {
      const item = (event.target as Element | null)?.closest?.('[data-quire-link]')
      if (!item) {
        clickPage(event)
        return
      }
      event.preventDefault()
      const href = item.getAttribute('data-quire-link')!
      if (item.hasAttribute('data-quire-external')) {
        setExternal(href)
        return
      }
      const target = resourceIndex(epub, href)
      if (target < 0) return
      const base = baseLocator(doc)
      const range = viewportRange(doc)
      const fragment = href.includes('#') ? href.substring(href.indexOf('#') + 1) : undefined
      go({ index: target, fragment })
      history.current.push(latest.current?.href === base.href ? latest.current : base)
      if (range) {
        const entry = history.current.length - 1
        void captureRange(doc, base, range, range.toString(), true).then(position => {
          if (position && history.current.length > entry && history.current[entry]?.href === base.href) {
            history.current[entry] = position
          }
        })
      }
    }
    const startTouch = (event: TouchEvent) => {
      const first = event.touches[0]
      touch = event.touches.length === 1 && first ? { x: first.clientX, y: first.clientY } : undefined
      deliberate()
    }
    const endTouch = (event: TouchEvent) => {
      const first = event.changedTouches[0]
      if (touch && first && !doc.getSelection()?.toString()) {
        const deltaX = first.clientX - touch.x
        const deltaY = first.clientY - touch.y
        if (Math.abs(deltaX) > 65 && Math.abs(deltaY) < 35) {
          const rtl = doc.defaultView?.getComputedStyle(doc.body).direction === 'rtl'
          page((deltaX < 0 ? 1 : -1) * (rtl ? -1 : 1))
        }
      }
      touch = undefined
    }
    const wheel = (event: WheelEvent) => {
      // One wheel or trackpad gesture turns one page, however many events it sends.
      if (event.ctrlKey || scrollsInside(event.target, event.deltaX, event.deltaY)) return
      const swipe = wheelTurn.current(event.deltaX, event.deltaY, event.timeStamp)
      if (!swipe) return
      const rtl = swipe.sideways && doc.defaultView?.getComputedStyle(doc.body).direction === 'rtl'
      page(swipe.turn * (rtl ? -1 : 1))
    }
    const key = (event: globalThis.KeyboardEvent) => handleReaderKey.current(event)
    doc.addEventListener('scroll', scroll, { passive: true })
    doc.addEventListener('wheel', wheel, { passive: true })
    doc.addEventListener('pointerdown', press, { passive: true })
    doc.addEventListener('touchstart', startTouch, { passive: true })
    doc.addEventListener('touchend', endTouch, { passive: true })
    doc.addEventListener('selectionchange', selectionChanged)
    doc.addEventListener('click', link)
    doc.addEventListener('mousedown', mouseDown)
    doc.addEventListener('keydown', key)
    doc.defaultView?.addEventListener('resize', resize)
    return () => {
      clearTimeout(timer)
      cancelAnimationFrame(resizeFrame)
      doc.removeEventListener('scroll', scroll)
      doc.removeEventListener('wheel', wheel)
      doc.removeEventListener('pointerdown', press)
      doc.removeEventListener('touchstart', startTouch)
      doc.removeEventListener('touchend', endTouch)
      doc.removeEventListener('selectionchange', selectionChanged)
      doc.removeEventListener('click', link)
      doc.removeEventListener('mousedown', mouseDown)
      doc.removeEventListener('keydown', key)
      doc.defaultView?.removeEventListener('resize', resize)
    }
  }, [active, epub, index])

  useEffect(() => {
    setResult(-1)
    if (!query.trim()) {
      setSearchResults([])
      setSearchPending(false)
      return
    }
    setSearchPending(true)
    const results: typeof searchResults = []
    let chapter = 0
    let scheduled: number
    function scan() {
      const item = epub.spine[chapter]
      if (!item || results.length >= 1000) {
        setSearchResults(results)
        setSearchPending(false)
        return
      }
      try {
        const matches = findMatches(chapterSearchText(epub, item.href), query, 1000 - results.length)
        matches.forEach((_, occurrence) => results.push({index: chapter, occurrence}))
      } catch { /* A malformed chapter does not stop searching the other chapters. */ }
      chapter++
      scheduled = window.setTimeout(scan, 0)
    }
    scheduled = window.setTimeout(scan, 200)
    return () => clearTimeout(scheduled)
  }, [epub, query])

  function find(direction: number) {
    if (!searchResults.length) return
    const start = result < 0 ? (direction > 0 ? -1 : 0) : result
    const next = (start + direction + searchResults.length) % searchResults.length
    setResult(next)
    go({index: searchResults[next].index, find: { query, occurrence: searchResults[next].occurrence }, keepProgress: true})
  }

  function changePreferences(patch: Partial<ReaderPreferences>) {
    const next = { ...preferencesRef.current, ...patch }
    preferencesRef.current = next
    const doc = active?.doc
    const range = doc && (warm.current ?? remember(doc))
    intentional.current = false
    if (style.current) style.current.textContent = readerFonts.current + chapterStyles(next)
    if (range && doc?.body.contains(range.startContainer)) displayRange(doc, range)
    setPreferences(next)
    void readerStorage.setPreference('reader', next).catch(() => setNotice(t('Could not save reading preferences')))
  }

  async function copyQuote(text: string) {
    try {
      await navigator.clipboard.writeText(`“${text}”\n— ${[author, title].filter(Boolean).join(', ')}`)
      setNotice(t('Quote copied'))
    } catch { setNotice(t('Could not copy quote')) }
  }

  async function addHighlight() {
    if (!selected || !active || busy) return
    setBusy(true)
    try {
      const { doc } = active
      const captured = await captureRange(doc, baseLocator(doc), selected.range, selected.quote)
      if (!captured) throw new Error('Selection changed')
      await onCreateHighlight({ locator: JSON.stringify(captured), text: selected.quote, colour, comment: comment.trim() || null })
      doc.getSelection()?.removeAllRanges()
      setSelected(null)
      setComment('')
      frame.current?.focus()
    } catch { setNotice(t('Could not save passage')) }
    finally { setBusy(false) }
  }

  async function updatePassage(item: ReaderHighlight, remove = false) {
    if (busy) return
    setBusy(true)
    try {
      if (remove) {
        await onDeleteHighlight?.(item.id)
        if (openedPassage?.id === item.id) setOpenedPassage(null)
      } else await onEditHighlight?.(item.id, { colour, comment: comment.trim() || null })
      setEditing(null)
    } catch { setNotice(t('Could not save passage')) }
    finally { setBusy(false) }
  }

  async function updatePause(action: 'save' | 'note' | 'remove') {
    if (busy) return
    setBusy(true)
    try {
      let mark: PauseBookmark | null = null
      if (action === 'note' && pause) mark = {...pause, note: note.trim()}
      if (action === 'save' && active) {
        const { doc } = active
        const range = viewportRange(doc)
        const base = baseLocator(doc)
        const current = range ? await captureRange(doc, base, range, range.toString(), true) : undefined
        mark = {locator: JSON.stringify(current ?? base), note: note.trim(), passage: current?.text?.highlight?.trim().slice(0, 500) ?? ''}
      }
      await readerStorage.setBookmark(bookId, mark)
      setPause(mark ?? undefined)
      if (!mark) setNote('')
    } catch { setNotice(t('Could not save pause bookmark')) }
    finally { setBusy(false) }
  }

  function returnToPause() {
    const target = parseLocator(pause?.locator)
    if (!target) return
    const targetIndex = resourceIndex(epub, target.href)
    if (targetIndex < 0) {
      setNotice(t('Pause location unavailable'))
      return
    }
    go({index: targetIndex, locator: target})
    closePanel()
  }

  function backFromLink() {
    const target = history.current.pop()
    if (target) go({index: resourceIndex(epub, target.href), locator: target})
  }

  function toggle(next: Panel, trigger: HTMLButtonElement) {
    if (panel === next) {
      closePanel()
    } else {
      panelTrigger.current = trigger
      setPanel(next)
    }
    setEditing(null)
    setOpenedPassage(null)
  }

  function seek(event: ChangeEvent<HTMLInputElement>) {
    const value = Number(event.target.value) / 100
    const target = Math.min(epub.spine.length - 1, Math.floor(value * epub.spine.length))
    go({ index: target, fraction: value * epub.spine.length - target })
  }

  async function fullscreen() {
    try {
      if (document.fullscreenElement === reader.current) await document.exitFullscreen()
      else await reader.current?.requestFullscreen()
    } catch { setNotice(t('Fullscreen unavailable')) }
  }

  const toc = (items: Epub['toc']): React.ReactNode => <ol className="qr-toc">
    {items.map((item, position) => {
      const target = resourceIndex(epub, item.href)
      return <li key={position}>
        <button type="button" aria-current={target === index ? 'location' : undefined} disabled={target < 0}
          onClick={() => { go({index: target, fragment: item.href.split('#')[1]}); closePanel() }}>{item.title}</button>
        {item.children.length ? toc(item.children) : null}
      </li>
    })}
  </ol>
  const colourControl = <label>{t('Colour')} <select value={colour} onChange={event => setColour(event.target.value as ReaderColour)}>
    {colours.map(item => <option key={item} value={item}>{option(item)}</option>)}
  </select></label>
  const tools: [Panel, IconName, string][] = [
    ['contents', 'contents', 'Contents'], ['find', 'search', 'Find'], ['passages', 'passages', 'Passages'],
    ['type', 'type', 'Appearance'], ['pause', 'pause', 'Pause & return'],
  ]
  const panelName = panel === 'contents' ? 'Contents' : panel === 'find' ? 'Find' : panel === 'type' ? 'Appearance'
    : panel === 'pause' ? 'Pause & return' : 'Passages'

  return <section className="qr-reader" ref={reader} tabIndex={-1} onKeyDown={keyAction}
    style={{'--qr-page': palettes[preferences.palette].background} as CSSProperties} aria-label={t('Reader')}>
    <header className="qr-bar">
      <button type="button" className="qr-tool" aria-label={t('Close book')} title={t('Close book')} onClick={onBack}><Icon name="back" /></button>
      <h1>{title}</h1>
      <div className="qr-tools">
        {tools.map(([name, icon, label]) => <button key={name} type="button" className="qr-tool" aria-label={t(label)} title={t(label)}
          aria-pressed={panel === name} onClick={event => toggle(name, event.currentTarget)}><Icon name={icon} /><span>{t(label)}</span></button>)}
        {document.fullscreenEnabled && <button type="button" className="qr-tool" aria-label={t('Fullscreen')} title={t('Fullscreen')}
          onClick={() => void fullscreen()}><Icon name="expand" /></button>}
      </div>
    </header>
    <div className="qr-body">
      <iframe className="qr-frame" title={title} ref={frame} sandbox={readerSandbox} srcDoc={chapterShell}
        onLoad={() => setLoaded(true)} referrerPolicy="no-referrer" />
      {panel && <aside className="qr-panel" aria-label={t(panelName)}>
        <header><h2 tabIndex={-1} ref={panelHeading}>{t(panelName)}</h2><button type="button" aria-label={t('Close panel')} onClick={closePanel}>×</button></header>
        {panel === 'contents' && (epub.toc.length ? toc(epub.toc) : <ol className="qr-toc">
          {epub.spine.map((chapter, position) => <li key={chapter.href}>
            <button type="button" aria-current={position === index ? 'location' : undefined}
              onClick={() => { go({index: position}); closePanel() }}>{t('Chapter')} {position + 1}</button>
          </li>)}
        </ol>)}
        {panel === 'find' && <form onSubmit={event => { event.preventDefault(); find(1) }}>
          <label>{t('Find in book')} <input type="search" ref={findInput} value={query} onChange={event => setQuery(event.target.value)} /></label>
          <p role="status">{searchPending ? t('Searching…') : query.trim() ? `${result + 1} / ${searchResults.length}` : ''}</p>
          <div className="qr-actions">
            <button type="button" disabled={searchPending || !searchResults.length} onClick={() => find(-1)}>{t('Previous')}</button>
            <button type="submit" disabled={searchPending || !searchResults.length}>{t('Next')}</button>
          </div>
        </form>}
        {settingsReady && panel === 'type' && <div className="qr-fields">
          <label>{t('Palette')} <select value={preferences.palette} onChange={event => changePreferences({palette: event.target.value as ReaderPreferences['palette']})}>
            {Object.keys(palettes).map(item => <option key={item} value={item}>{option(item)}</option>)}
          </select></label>
          <label>{t('Font')} <select value={preferences.font} onChange={event => changePreferences({font: event.target.value as ReaderPreferences['font']})}>
            <option value="serif">{t('Serif')}</option><option value="sans">{t('Sans serif')}</option><option value="mono">{t('Monospace')}</option><option value="publisher">{t('Publisher font')}</option>
          </select></label>
          <label>{t('Text size')} <input type="range" min="14" max="36" value={preferences.size} onChange={event => changePreferences({size: +event.target.value})} /></label>
          <label>{t('Line spacing')} <input type="range" min="1.2" max="2.4" step="0.1" value={preferences.spacing} onChange={event => changePreferences({spacing: +event.target.value})} /></label>
          <label>{t('Margins')} <input type="range" min="12" max="64" value={preferences.margin} onChange={event => changePreferences({margin: +event.target.value})} /></label>
        </div>}
        {settingsReady && panel === 'pause' && <div className="qr-fields">
          {pause?.passage && <blockquote>{pause.passage}</blockquote>}
          <label>{t('Private note')} <textarea value={note} onChange={event => setNote(event.target.value)} maxLength={4000} /></label>
          <div className="qr-actions">
            <button type="button" disabled={busy || !active} onClick={() => void updatePause('save')}>{pause ? t('Replace at current position') : t('Save current position')}</button>
            {pause && <>
              <button type="button" onClick={returnToPause}>{t('Return to saved passage')}</button>
              <button type="button" disabled={busy} onClick={() => void updatePause('note')}>{t('Save note')}</button>
              <button type="button" disabled={busy} onClick={() => void updatePause('remove')}>{t('Remove bookmark')}</button>
            </>}
          </div>
        </div>}
        {panel === 'passages' && <div className="qr-passages">
          {openedPassage && <blockquote>{openedPassage.text}</blockquote>}
          {!highlights.length && <p>{t('No saved passages')}</p>}
          {[...highlights].sort((a, b) => comparePassages(epub, a, b)).map(item => <article key={item.id}>
            <blockquote>{item.text}</blockquote>
            {item.comment && <p>{item.comment}</p>}
            {editing === item.id ? <div className="qr-fields">
              {colourControl}
              <label>{t('Comment')} <textarea value={comment} onChange={event => setComment(event.target.value)} maxLength={4000} /></label>
              <div className="qr-actions">
                <button type="button" disabled={busy} onClick={() => void updatePassage(item)}>{t('Save')}</button>
                <button type="button" onClick={() => setEditing(null)}>{t('Cancel')}</button>
              </div>
            </div> : <div className="qr-actions">
              <button type="button" onClick={() => void openHighlight(item)}>{t('Go to passage')}</button>
              <button type="button" onClick={() => void copyQuote(item.text)}>{t('Copy quote')}</button>
              {onEditHighlight && <button type="button" onClick={() => {
                setEditing(item.id)
                setComment(item.comment ?? '')
                setColour(colours.find(value => value === item.colour) ?? 'YELLOW')
              }}>{t('Edit')}</button>}
              {onDeleteHighlight && <button type="button" className="danger" disabled={busy} onClick={async () => {
                if (await ask(t('Delete this passage?'), {confirm: t('Delete'), cancel: t('Cancel'), destructive: true})) void updatePassage(item, true)
              }}>{t('Delete')}</button>}
            </div>}
          </article>)}
        </div>}
      </aside>}
    </div>
    {(notice || external || (selected && editing === null)) &&
      <div className={`qr-overlay${selected?.low ? ' qr-above' : ''}`}>
      {notice && <p className="qr-notice" role="status">{notice} <button type="button" aria-label={t('Dismiss')} onClick={() => setNotice('')}>×</button></p>}
      {external && <div className="qr-notice">
        <a href={external} target="_blank" rel="noopener noreferrer">{t('Open external link')} · {new URL(external).hostname}</a>
        <button type="button" onClick={() => setExternal(null)}>{t('Cancel')}</button>
      </div>}
      {selected && editing === null && <div className="qr-selection qr-fields" role="group" aria-label={t('Selected text')}>
        <div className="qr-actions">
          {colourControl}
          <button type="button" className="primary" disabled={busy} onClick={() => void addHighlight()}>{t('Save highlight')}</button>
          <button type="button" onClick={() => void copyQuote(selected.quote)}>{t('Copy quote')}</button>
          <button type="button" aria-label={t('Dismiss')} onClick={() => {
            active?.doc.getSelection()?.removeAllRanges()
            setSelected(null)
            frame.current?.focus()
          }}>×</button>
        </div>
        <label>{t('Comment')} <input value={comment} onChange={event => setComment(event.target.value)} maxLength={4000} /></label>
      </div>}
    </div>}
    <footer className="qr-bar qr-footer">
      {history.current.length > 0 && <button type="button" className="qr-return" title={t('Back to previous position')} onClick={backFromLink}>{t('Back to previous position')}</button>}
      <button type="button" className="qr-tool" aria-label={t('Previous')} title={t('Previous')} onClick={() => page(-1)}><Icon name="back" /></button>
      <input type="range" aria-label={t('Book position')} min="0" max="100" step="1" value={Math.round(progress * 100)} onChange={seek}
        title={`${Math.round(progress * 100)}%`} />
      <span>{Math.round(progress * 100)}%</span>
      <button type="button" className="qr-tool" aria-label={t('Next')} title={t('Next')} onClick={() => page(1)}><Icon name="next" /></button>
    </footer>
  </section>
}
