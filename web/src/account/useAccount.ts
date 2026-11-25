import { useCallback, useEffect, useRef, useState } from 'react'
import { makeCatalogClient } from '../books/opds'
import { openReplica } from '../data'

export type AccountRequest = (path: string, init?: RequestInit) => Promise<Response>
export type Bootstrap = {
  version: 1
  sync: boolean
  ai: boolean
  aiAvailable: boolean
  login: 'none' | 'native' | 'basic' | 'transition'
  password: boolean
  oidc: boolean
  migration?: boolean
  catalogUrl: string | null
  guestReading?: boolean
  /** Catalogs saved to a Quire account, and whether the server opens them for the browser. */
  catalogSync?: boolean
  catalogProxy?: boolean
  accountManagement: string | null
  nativeWebUrl?: string | null
}
export function allowsBasic(config?: Bootstrap) {
  return config?.login === 'basic' || config?.login === 'transition'
}
export function allowsNative(config?: Bootstrap) {
  return config?.login === 'native' || config?.login === 'transition'
}
function parseNativeWebUrl(value?: string | null): URL | undefined {
  if (!value) return
  try {
    const url = new URL(value)
    if (url.protocol === 'https:' && url.origin !== location.origin && !url.username && !url.password &&
        !url.search && !url.hash && url.pathname === '/quire/') return url
  } catch { /* Ignore misconfigured destinations. */ }
}
const nativeLoginRequired = 'This Calibre-Web account now uses Quire sign-in.'
const migrationErrors: Record<string, string> = {
  already_migrated: nativeLoginRequired,
  email_unavailable: 'This email already has a Quire account.',
  invalid_email: 'Enter a valid email address.',
  invalid_password: 'Use a new password of at least 12 characters.', // pragma: allowlist secret
}

type Identity = { id: string, backend: 'native' | 'calibreweb' }
type Session = {
  version: 1
  user: Identity
  csrf_token?: string
  capabilities?: { progress_metadata_sync?: boolean, ai_enabled?: boolean, book_details?: boolean }
}
type RememberedIdentity = Identity & { capabilities?: Session['capabilities'] }
export type ActiveAccount = {
  namespace: string
  mode: 'local' | 'catalog' | 'connected'
  identity?: Identity
  remembered: boolean
  offline?: boolean
  request?: AccountRequest
  catalogRequest?: AccountRequest
  catalogUrl?: string
  catalogAuthenticated?: boolean
  catalogRevision?: number
  capabilities?: Session['capabilities']
}
type ExitOptions = {
  pending: number
  sync: () => Promise<unknown>
  clear: (namespace: string, discardPending?: boolean) => Promise<void>
  discard?: boolean
}
export async function pendingOnDevice(namespace: string): Promise<number> {
  const replica = await openReplica(namespace)
  try { return (await replica.snapshot()).pending }
  finally { replica.close() }
}
const rememberKey = 'quire-web:v1:remembered-account'
const oidcKey = 'quire-web:v1:oidc-remember'
const logoutKey = 'quire-web:v1:native-locked'
const retainedKey = 'quire-web:v1:locked-accounts'
const changeKey = 'quire-web:v1:account-changed'

function stored<T>(key: string): T | undefined {
  try { return JSON.parse(localStorage.getItem(key) ?? 'null') ?? undefined } catch { return undefined }
}
function store<T>(key: string, value?: T) {
  try {
    if (value === undefined) localStorage.removeItem(key)
    else localStorage.setItem(key, JSON.stringify(value))
  } catch { /* The active account continues in memory if storage is unavailable. */ }
}
function namespace(...parts: string[]) {
  return JSON.stringify([window.location.origin, ...parts])
}
function guest(mode: 'local' | 'catalog', catalogUrl?: string | null): ActiveAccount {
  return { mode, namespace: namespace(mode, catalogUrl ?? ''), remembered: false }
}
function connectedNamespace(identity: Identity) {
  return namespace('connected', identity.backend, identity.id)
}
function validIdentity(value: Identity | undefined): value is Identity {
  return !!value && typeof value.id === 'string' && !!value.id &&
    ['native', 'calibreweb'].includes(value.backend)
}
function retainedAccounts(): Identity[] {
  const accounts = stored<Identity[]>(retainedKey)
  return Array.isArray(accounts) ? accounts.filter(validIdentity) : []
}
function accountHeader(user: Identity) {
  return Array.from(new TextEncoder().encode(user.id), byte =>
    byte.toString(16).padStart(2, '0')).join('')
}
function encodeBasic(username: string, password: string) {
  if (!username || username.includes(':') || !password) throw new Error('Enter a valid username and password.')
  const bytes = new TextEncoder().encode(username + ':' + password)
  return 'Basic ' + btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''))
}
function secureOrigin() {
  return location.protocol === 'https:' || ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
}
function fetchPrivate(path: string | URL, init?: RequestInit) {
  return fetch(path, { ...init, cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer' })
}
function reservedCatalogPath(url: URL): boolean {
  try {
    const path = decodeURIComponent(url.pathname)
    if (path.startsWith('//') || path.includes('\\') || path.includes('%')) return true
    const normalized = new URL(path, location.origin).pathname
    return /^\/(?:auth|ai|sync|library|internal|quire|quire-admin|cloud|agent|mcp|health|healthz|readyz)(?:\/|$)/i.test(normalized)
  } catch { return true }
}
function catalogTransport(base: URL, authorization: string, active: () => boolean, nativeOrigin?: string): AccountRequest {
  return async (path, init) => {
    if (!active()) throw new Error('Account locked.')
    const url = new URL(path, base)
    if (url.origin !== base.origin || url.username || url.password ||
        ((url.origin === location.origin || url.origin === nativeOrigin) && reservedCatalogPath(url)) ||
        !['GET', 'HEAD'].includes((init?.method ?? 'GET').toUpperCase())) {
      throw new Error('Unexpected catalog destination.')
    }
    const headers = new Headers(init?.headers)
    headers.set('Authorization', authorization)
    const response = await fetchPrivate(url, { ...init, headers, credentials: 'omit' })
    if (!active()) throw new Error('Account locked.')
    return response
  }
}
function requiresNativeLogin(response: Response) {
  return response.clone().json().then(value => value?.detail === 'native_login_required', () => false)
}
async function getSession(path: string, backend: Identity['backend'], authorization?: string, signal?: AbortSignal): Promise<Session> {
  const response = await fetchPrivate(path, {
    credentials: authorization ? 'omit' : 'same-origin',
    signal,
    headers: authorization ? { Authorization: authorization } : undefined,
  })
  if (response.status === 401) {
    if (backend === 'calibreweb' && await requiresNativeLogin(response)) throw new Error(nativeLoginRequired)
    throw new Error('Sign-in is required.')
  }
  if (!response.ok) throw new Error('The server could not verify the account.')
  const session = await response.json() as Session
  if (session.version !== 1 || !validIdentity(session.user) || session.user.backend !== backend ||
      (backend === 'native' && !session.csrf_token)) throw new Error('Invalid account response.')
  return session
}

async function passwordSession(email: string, password: string, remember: boolean): Promise<Session> {
  const response = await fetchPrivate('/auth/v1/browser/login', {
    method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, destination: 'quire', remember_browser: remember }),
  })
  if (!response.ok) throw new Error('Sign-in failed. Check your credentials and try again.')
  const issued = await response.json() as { account_id: string }
  const session = await getSession('/auth/v1/me', 'native')
  if (issued.account_id !== session.user.id) throw new Error('The account changed in another tab.')
  store(logoutKey)
  return session
}

export function useAccount() {
  const [active, setActive] = useState<ActiveAccount>(() => guest('local'))
  const [bootstrap, setBootstrap] = useState<Bootstrap>()
  const [status, setStatus] = useState<'loading' | 'ready' | 'busy'>('loading')
  const [error, setError] = useState('')
  const [online, setOnline] = useState(() => navigator.onLine)
  const [rememberedIdentity, setRememberedIdentity] = useState(() => {
    const identity = stored<RememberedIdentity>(rememberKey)
    return validIdentity(identity) ? identity : undefined
  })
  const [retained, setRetained] = useState(retainedAccounts)
  const current = useRef(active)
  const epoch = useRef(0)
  const busy = useRef(false)
  const configuration = useRef<Bootstrap | undefined>(undefined)
  const signing = useRef(false)
  const catalogEpoch = useRef(0)

  const update = useCallback((next: ActiveAccount) => {
    ++epoch.current
    ++catalogEpoch.current
    current.current = next
    setActive(next)
  }, [])

  const lock = useCallback((message: string) => {
    update(guest('local'))
    if (message) setError(message)
  }, [update])

  const install = useCallback((session: Session, remember: boolean, authorization?: string, shareCatalog = false) => {
    const { user, csrf_token } = session
    const capabilities = session.capabilities ?? {
      progress_metadata_sync: configuration.current?.sync,
      ai_enabled: configuration.current?.ai,
    }
    const generation = epoch.current + 1
    const catalogUrl = configuration.current?.catalogUrl
    const matchesCurrent = () => generation === epoch.current
    const catalog = catalogUrl ? new URL(catalogUrl, location.origin) : undefined
    const expected = accountHeader(user)
    const request: AccountRequest = async (path, init) => {
      if (!matchesCurrent()) throw new Error('Account locked.')
      const url = new URL(path, location.origin)
      const privateRoute = /^\/(?:sync|library|ai|details|catalogs)\/v1\//.test(url.pathname) ||
        user.backend === 'native' && url.pathname === '/auth/v1/account/export' &&
        (init?.method ?? 'GET').toUpperCase() === 'GET'
      if (url.origin !== location.origin || url.username || url.password || !privateRoute) {
        throw new Error('Unexpected API destination.')
      }
      const headers = new Headers(init?.headers)
      headers.delete('Authorization')
      headers.set('X-Quire-Account', expected)
      if (csrf_token) headers.set('X-CSRF-Token', csrf_token)
      else headers.delete('X-CSRF-Token')
      if (authorization) headers.set('Authorization', authorization)
      const response = await fetchPrivate(url, {
        ...init, headers, credentials: authorization ? 'omit' : 'same-origin',
      })
      if (!matchesCurrent()) throw new Error('Account locked.')
      const expired = response.status === 401 || [403, 409].includes(response.status) &&
        await response.clone().json().then(
          value => ['account_changed', 'invalid_csrf'].includes(value?.detail), () => false)
      if (!matchesCurrent()) throw new Error('Account locked.')
      if (expired) {
        const message = user.backend === 'calibreweb' && response.status === 401 && await requiresNativeLogin(response)
          ? nativeLoginRequired : 'Sign in again to access this account.'
        if (matchesCurrent()) lock(message)
        throw new Error('Account locked.')
      }
      return response
    }
    const catalogGeneration = catalogEpoch.current + 1
    const catalogRequest = shareCatalog && authorization && catalog?.origin === location.origin
      ? catalogTransport(catalog, authorization, () => matchesCurrent() && catalogGeneration === catalogEpoch.current)
      : undefined
    update({ mode: 'connected', namespace: connectedNamespace(user), identity: user, capabilities,
      remembered: remember, request, catalogRequest, catalogAuthenticated: !!catalogRequest,
      catalogUrl: catalogRequest ? catalog?.href : undefined, catalogRevision: catalogGeneration })
    const accounts = retainedAccounts()
    if (!accounts.some(item => connectedNamespace(item) === connectedNamespace(user))) {
      store(retainedKey, [...accounts, user])
      setRetained([...accounts, user])
    }
    if (remember) {
      const remembered = { ...user, capabilities }
      store(rememberKey, remembered)
      setRememberedIdentity(remembered)
    } else {
      store(rememberKey)
      setRememberedIdentity(undefined)
    }
  }, [lock, update])

  const announce = useCallback(() => {
    store(changeKey, { time: Date.now(), random: Math.random() })
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    let loading = false
    async function load() {
      if (loading || configuration.current) return
      loading = true
      const startingEpoch = epoch.current
      try {
        const response = await fetchPrivate('/quire/v1/bootstrap', {
          signal: controller.signal, credentials: 'omit',
        })
        if (!response.ok) throw new Error('Unable to load server settings.')
        const config = await response.json() as Bootstrap
        if (config.version !== 1) throw new Error('Unsupported server settings.')
        if (controller.signal.aborted) return
        configuration.current = config
        setBootstrap(config)
        if (!allowsNative(config) ||
            (stored<boolean>(logoutKey) && sessionStorage.getItem(oidcKey) === null)) return
        const login = await getSession('/auth/v1/me', 'native').catch(() => undefined)
        if (!login || controller.signal.aborted || epoch.current !== startingEpoch || signing.current ||
            current.current.mode === 'connected') return
        const remember = sessionStorage.getItem(oidcKey)
        sessionStorage.removeItem(oidcKey)
        install(login, remember === 'yes' || (remember === null &&
          stored<Identity>(rememberKey)?.id === login.user.id))
        if (remember !== null) {
          store(logoutKey)
          announce()
        }
      } catch (reason) {
        if (!controller.signal.aborted && navigator.onLine) setError(reason instanceof Error ? reason.message : 'Unable to load server settings.')
      } finally {
        loading = false
        if (!controller.signal.aborted && !busy.current) setStatus('ready')
      }
    }
    const connectivity = () => {
      setOnline(navigator.onLine)
      if (navigator.onLine) void load()
    }
    window.addEventListener('online', connectivity)
    window.addEventListener('offline', connectivity)
    void load()
    return () => {
      controller.abort()
      window.removeEventListener('online', connectivity)
      window.removeEventListener('offline', connectivity)
    }
  }, [announce, install])

  const revalidate = useCallback(async () => {
    const previous = current.current
    if (signing.current || previous.identity?.backend !== 'native' || !navigator.onLine) return
    const revision = epoch.current
    try {
      const login = await getSession('/auth/v1/me', 'native')
      if (revision !== epoch.current) return
      if (login.user.id !== previous.identity.id) lock('Your account changed in another tab. Sign in again.')
      else if (previous.offline) install(login, previous.remembered)
    } catch (reason) {
      // An intermittent network failure does not change an already-verified namespace.
      if (revision === epoch.current && reason instanceof Error &&
          reason.message === 'Sign-in is required.') lock('Your session expired. Sign in again.')
    }
  }, [install, lock])

  useEffect(() => {
    const changed = (event: StorageEvent) => {
      if (event.key !== changeKey) return
      setRetained(retainedAccounts())
      const identity = stored<RememberedIdentity>(rememberKey)
      setRememberedIdentity(validIdentity(identity) ? identity : undefined)
      if (current.current.mode === 'connected') lock('The account changed in another tab.')
      else ++epoch.current
    }
    window.addEventListener('storage', changed)
    window.addEventListener('focus', revalidate)
    window.addEventListener('online', revalidate)
    const timer = window.setInterval(revalidate, 60_000)
    return () => {
      window.removeEventListener('storage', changed)
      window.removeEventListener('focus', revalidate)
      window.removeEventListener('online', revalidate)
      window.clearInterval(timer)
    }
  }, [lock, revalidate])

  async function action(run: () => Promise<void>) {
    if (busy.current) return
    busy.current = true
    setStatus('busy')
    setError('')
    try { await run() }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'The account operation failed.') }
    finally { setStatus('ready'); busy.current = false }
  }
  async function signIn(run: () => Promise<Session>, remember: boolean, authorization?: string, shareCatalog?: boolean) {
    await action(async () => {
      const previous = current.current
      const reconnect = previous.offline && previous.identity?.backend === 'calibreweb' && !!authorization
      if (previous.mode === 'connected' && !reconnect) throw new Error('Sign out before switching accounts.')
      if (!secureOrigin()) throw new Error('Sign in over HTTPS.')
      signing.current = true
      try {
        const revision = epoch.current
        const session = await run()
        if (revision !== epoch.current) throw new Error('The account changed in another tab.')
        if (reconnect && connectedNamespace(session.user) !== previous.namespace) {
          throw new Error('Sign in to the remembered account to reconnect.')
        }
        install(session, reconnect || remember, authorization, shareCatalog)
        announce()
      } finally { signing.current = false }
    })
  }

  function openGuest(mode: 'local' | 'catalog') {
    if (current.current.mode === 'connected' || busy.current) return
    update(guest(mode, mode === 'catalog' ? configuration.current?.catalogUrl : undefined))
  }

  return {
    active, bootstrap, status, error, online, revalidate, rememberedIdentity,
    nativeWebUrl: !allowsNative(bootstrap) ? parseNativeWebUrl(bootstrap?.nativeWebUrl)?.href : undefined,
    retained: retained.map(identity => ({ identity, namespace: connectedNamespace(identity) })),
    openLocal: () => openGuest('local'),
    openCatalog: () => openGuest('catalog'),
    openRememberedOffline: () => {
      const remembered = stored<RememberedIdentity>(rememberKey)
      if (!validIdentity(remembered) || navigator.onLine || current.current.mode === 'connected' || busy.current) return
      update({ mode: 'connected', identity: remembered, remembered: true, offline: true,
        namespace: connectedNamespace(remembered), capabilities: remembered.capabilities })
    },
    signInBasic: async (username: string, password: string, remember = false, shareCatalog = false) => {
      if (!allowsBasic(configuration.current)) return
      let authorization: string
      try { authorization = encodeBasic(username, password) }
      catch (reason) { setError((reason as Error).message); return }
      await signIn(() => getSession('/quire/v1/session', 'calibreweb', authorization), remember, authorization, shareCatalog)
    },
    signInCatalog: async (username: string, password: string, selectedUrl?: string) => {
      if (busy.current) throw new Error('Another account operation is in progress.')
      const input = selectedUrl ?? configuration.current?.catalogUrl
      if (!input) throw new Error('Enter a catalog URL.')
      if (!secureOrigin()) throw new Error('Sign in over HTTPS.')
      const catalog = new URL(input, location.origin)
      if (catalog.username || catalog.password || catalog.hash ||
          (catalog.protocol !== 'https:' && catalog.origin !== location.origin)) {
        throw new Error('Enter a secure catalog URL.')
      }
      const authorization = encodeBasic(username, password)
      const nativeOrigin = parseNativeWebUrl(configuration.current?.nativeWebUrl)?.origin
      const revision = epoch.current
      const selection = catalogEpoch.current
      const probing = () => revision === epoch.current && selection === catalogEpoch.current
      const previous = current.current
      busy.current = true
      setStatus('busy')
      try {
        const catalogRequest = catalogTransport(catalog, authorization, probing, nativeOrigin)
        await makeCatalogClient({ url: catalog.href, request: catalogRequest }).feed()
        if (!probing()) throw new Error('Account locked.')
        if (previous.mode === 'connected') {
          const generation = ++catalogEpoch.current
          const next = { ...previous, catalogUrl: catalog.href, catalogAuthenticated: true, catalogRevision: generation,
            catalogRequest: catalogTransport(catalog, authorization,
              () => revision === epoch.current && generation === catalogEpoch.current, nativeOrigin) }
          current.current = next
          setActive(next)
        } else {
          const next = { ...guest('catalog', catalog.href), catalogUrl: catalog.href, catalogAuthenticated: true, catalogRevision: selection + 1,
            namespace: namespace('catalog', catalog.href, username) }
          const connectedCatalog = catalogTransport(catalog, authorization,
            () => current.current.namespace === next.namespace && catalogEpoch.current === selection + 1, nativeOrigin)
          update({ ...next, catalogRequest: connectedCatalog })
        }
      } finally {
        busy.current = false
        setStatus('ready')
      }
    },
    signOutCatalog: () => {
      if (busy.current || !current.current.catalogAuthenticated) return
      if (current.current.mode === 'connected') {
        ++catalogEpoch.current
        const next = { ...current.current, catalogUrl: undefined, catalogAuthenticated: false, catalogRevision: catalogEpoch.current,
          catalogRequest: undefined }
        current.current = next
        setActive(next)
      } else openGuest('catalog')
    },
    signInPassword: async (email: string, password: string, remember = false) => {
      const config = configuration.current
      if (!allowsNative(config) || !config?.password) return
      await signIn(() => passwordSession(email, password, remember), remember)
    },
    // Proves ownership with the Calibre-Web login, then signs in to the new Quire account.
    migrateCalibre: async (username: string, calibrePassword: string, email: string, password: string, remember = false) => {
      const config = configuration.current
      if (!config?.migration || !config.password) return
      let authorization: string
      try { authorization = encodeBasic(username, calibrePassword) }
      catch (reason) { setError((reason as Error).message); return }
      await signIn(async () => {
        const response = await fetchPrivate('/auth/v1/migrate', {
          method: 'POST', credentials: 'omit',
          headers: { Authorization: authorization, 'Content-Type': 'application/json' },
          body: JSON.stringify({ email, password }),
        })
        if (!response.ok) {
          const detail = await response.json().then(value => value?.detail, () => undefined)
          throw new Error(response.status === 401 ? 'Check your Calibre-Web username and password.'
            : migrationErrors[detail] ?? 'The account could not be moved. Try again.')
        }
        return passwordSession(email, password, remember)
      }, remember)
    },
    signInOidc: (remember = false) => {
      if (!allowsNative(configuration.current) || !configuration.current?.oidc ||
          busy.current || current.current.mode === 'connected') return
      if (!secureOrigin()) { setError('Sign in over HTTPS.'); return }
      sessionStorage.setItem(oidcKey, remember ? 'yes' : 'no')
      location.assign('/auth/v1/oidc/start?destination=quire&remember_browser=' + remember)
    },
    signOut: (options?: ExitOptions) => action(async () => {
      const previous = current.current
      if (previous.mode !== 'connected') return
      if (!options) throw new Error('Check pending changes before signing out.')
      if (!previous.remembered && !options.discard) {
        const pending = await pendingOnDevice(previous.namespace).catch(() => options.pending)
        if (pending || options.pending) {
          await options.sync()
          throw new Error('Check the updated pending count, then sign out again.')
        }
      }
      if (previous.identity?.backend === 'native') store(logoutKey, true)
      lock('')
      store(rememberKey)
      setRememberedIdentity(undefined)
      announce()
      let logoutWarning = false
      if (previous.identity?.backend === 'native') {
        if (navigator.onLine) {
          const signal = AbortSignal.timeout(8_000)
          try {
            const session = await getSession('/auth/v1/me', 'native', undefined, signal)
            if (session.user.id === previous.identity.id) {
              const response = await fetchPrivate('/auth/v1/logout', {
                method: 'POST', credentials: 'same-origin', signal,
                headers: { 'X-CSRF-Token': session.csrf_token ?? '', 'X-Quire-Account': accountHeader(session.user) },
              })
              logoutWarning = ![204, 401, 409].includes(response.status)
            }
          } catch (reason) {
            logoutWarning = !(reason instanceof Error && reason.message === 'Sign-in is required.')
          }
        } else logoutWarning = true
      }
      if (logoutWarning) setError('Signed out in Quire Web. The server session could not be revoked.')
      if (!previous.remembered) {
        await options.clear(previous.namespace, options.discard === true)
        const accounts = retainedAccounts().filter(item => connectedNamespace(item) !== previous.namespace)
        store(retainedKey, accounts)
        setRetained(accounts)
        announce()
      }
    }),
    exportAccount: () => action(async () => {
      const { identity, request } = current.current
      if (identity?.backend !== 'native' || !request) return
      const revision = epoch.current
      const response = await request('/auth/v1/account/export')
      if (!response.ok) throw new Error('Could not export account data.')
      const blob = await response.blob()
      if (revision !== epoch.current) throw new Error('Account locked.')
      const source = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = source
      anchor.download = 'quire-account.json'
      anchor.click()
      setTimeout(() => URL.revokeObjectURL(source), 1_000)
    }),
    clearDeviceData: (target: string, options: ExitOptions) => action(async () => {
      if (options.pending && !options.discard) throw new Error('Sync or discard pending changes first.')
      if (current.current.mode === 'connected' && target === current.current.namespace) {
        throw new Error('Sign out before clearing this account’s offline data.')
      }
      await options.clear(target, options.discard === true)
      const accounts = retainedAccounts().filter(item => connectedNamespace(item) !== target)
      store(retainedKey, accounts)
      setRetained(accounts)
      if (rememberedIdentity && target === connectedNamespace(rememberedIdentity)) {
        store(rememberKey)
        setRememberedIdentity(undefined)
      }
      announce()
    }),
  }
}
export type AccountState = ReturnType<typeof useAccount>
