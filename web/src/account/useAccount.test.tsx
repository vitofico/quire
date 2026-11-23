import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { useAccount } from './useAccount'

const config = { version: 1, sync: true, ai: false, aiAvailable: false, login: 'basic',
  password: false, oidc: false, catalogUrl: '/opds', accountManagement: null }

afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

test('locks stale responses and keeps Calibre accounts in independent namespaces', async () => {
  let resolveOld!: (response: Response) => void
  const oldRequest = new Promise<Response>(resolve => { resolveOld = resolve })
  const request = vi.fn(async (path: string | URL, options?: RequestInit) => {
    if (path === '/quire/v1/bootstrap') return Response.json(config)
    const headers = new Headers(options?.headers)
    if (path === '/quire/v1/session') {
      const alice = headers.get('Authorization') === 'Basic ' + btoa('Alice:password')
      return Response.json({ version: 1, user: { backend: 'calibreweb', id: alice ? 'alice' : 'bob' } })
    }
    expect(String(path)).toBe(location.origin + '/sync/v1/progress')
    expect(headers.get('X-Quire-Account')).toBe('616c696365')
    expect(options?.credentials).toBe('omit')
    expect(options?.redirect).toBe('error')
    return oldRequest
  })
  vi.stubGlobal('fetch', request)

  const { result } = renderHook(() => useAccount())
  await waitFor(() => expect(result.current.status).toBe('ready'))
  const guest = result.current.active.namespace
  await act(async () => { await result.current.signInBasic('Alice', 'password', false, true) })
  const before = result.current.active
  expect(before.namespace).not.toBe(guest)
  expect(before.identity?.id).toBe('alice')
  await expect(before.request!('https://elsewhere.test/sync/v1/progress')).rejects.toThrow('Unexpected API destination.')
  await expect(before.catalogRequest!('https://elsewhere.test/opds')).rejects.toThrow('Unexpected catalog destination.')
  for (const path of ['/%71uire/v1/session', '/sections%2F..%2Fsync/v1/progress', '/quire-admin', '/agent/highlights', '/mcp']) {
    await expect(before.catalogRequest!(path)).rejects.toThrow('Unexpected catalog destination.')
  }

  const pending = before.request!('/sync/v1/progress')
  const outcome = expect(pending).rejects.toThrow('Account locked.')
  const clear = vi.fn(async () => {})
  const sync = vi.fn(async () => {})
  await act(async () => { await result.current.signOut({ pending: 1, clear, sync }) })
  expect(sync).toHaveBeenCalledOnce()
  expect(clear).not.toHaveBeenCalled()
  expect(result.current.active.namespace).toBe(before.namespace)
  await act(async () => { await result.current.signOut({ pending: 1, discard: true, clear, sync }) })
  expect(clear).toHaveBeenCalledWith(before.namespace, true)
  expect(result.current.active.namespace).toBe(guest)

  await act(async () => { await result.current.signInBasic('Bob', 'other-password') })
  expect(result.current.active.identity?.id).toBe('bob')
  expect(result.current.active.namespace).not.toBe(before.namespace)
  resolveOld(Response.json({ private: 'Alice' }))
  await outcome
  expect(result.current.active.identity?.id).toBe('bob')
  for (const value of Object.values(localStorage)) {
    expect(value).not.toContain('password')
    expect(value).not.toContain('Basic ')
  }
})

test('keeps remembered data locked after sign-out', async () => {
  vi.stubGlobal('fetch', vi.fn(async (path: string | URL) =>
    path === '/quire/v1/bootstrap'
      ? Response.json(config)
      : Response.json({ version: 1, user: { backend: 'calibreweb', id: 'reader' } })))
  const { result } = renderHook(() => useAccount())
  await waitFor(() => expect(result.current.status).toBe('ready'))
  await act(async () => { await result.current.signInBasic('reader', 'password', true) })
  const account = result.current.active.namespace
  const clear = vi.fn(async () => {})
  const sync = vi.fn(async () => {})
  await act(async () => { await result.current.signOut({ pending: 2, clear, sync }) })
  expect(result.current.active.mode).toBe('local')
  expect(result.current.rememberedIdentity).toBeUndefined()
  expect(result.current.retained[0]?.namespace).toBe(account)
  expect(clear).not.toHaveBeenCalled()
})

test('separates users of the same private catalog and never forwards catalog cookies', async () => {
  const atom = '<feed xmlns="http://www.w3.org/2005/Atom"><title>Fixture</title></feed>'
  const request = vi.fn(async (path: string | URL, options?: RequestInit) => {
    if (path === '/quire/v1/bootstrap') return Response.json(config)
    expect(String(path)).toBe('https://catalog.example.test/opds')
    expect(options?.credentials).toBe('omit')
    expect(options?.redirect).toBe('error')
    expect(new Headers(options?.headers).get('X-CSRF-Token')).toBeNull()
    return new Response(atom, { headers: { 'Content-Type': 'application/atom+xml' } })
  })
  vi.stubGlobal('fetch', request)

  const { result } = renderHook(() => useAccount())
  await waitFor(() => expect(result.current.status).toBe('ready'))
  const local = result.current.active.namespace
  await act(async () => { await result.current.signInCatalog('alice', 'first', 'https://catalog.example.test/opds') })
  const first = result.current.active
  expect(first.mode).toBe('catalog')
  expect(first.namespace).not.toBe(local)
  expect(first.request).toBeUndefined()
  expect(first.catalogAuthenticated).toBe(true)
  expect(new Headers(request.mock.lastCall?.[1]?.headers).get('Authorization')).toBe('Basic ' + btoa('alice:first'))
  act(() => result.current.signOutCatalog())
  expect(result.current.active.catalogRequest).toBeUndefined()
  await expect(first.catalogRequest!('https://catalog.example.test/opds')).rejects.toThrow('Account locked.')
  await act(async () => { await result.current.signInCatalog('bob', 'second', 'https://catalog.example.test/opds') })
  expect(result.current.active.namespace).not.toBe(first.namespace)
  expect(new Headers(request.mock.lastCall?.[1]?.headers).get('Authorization')).toBe('Basic ' + btoa('bob:second'))
})

test('a native session replaced by another tab cannot be used by the old transport', async () => {
  let current = 'native:1'
  const request = vi.fn(async (path: string | URL, options?: RequestInit) => {
    if (path === '/quire/v1/bootstrap') return Response.json({ ...config, login: 'native', password: true })
    if (path === '/auth/v1/me') return Response.json({
      version: 1, user: { id: current, backend: 'native' }, csrf_token: `csrf-${current}`,
    })
    expect(options?.credentials).toBe('same-origin')
    const expected = new Headers(options?.headers).get('X-Quire-Account')
    if (expected !== Buffer.from(current).toString('hex')) {
      return Response.json({ detail: 'account_changed' }, { status: 409 })
    }
    return Response.json({ private: current })
  })
  vi.stubGlobal('fetch', request)

  const { result } = renderHook(() => useAccount())
  await waitFor(() => expect(result.current.active.identity?.id).toBe('native:1'))
  const first = result.current.active.request!
  expect(await (await first('/library/v1/items')).json()).toEqual({ private: 'native:1' })
  current = 'native:2'
  await act(async () => { await expect(first('/library/v1/items')).rejects.toThrow('Account locked.') })
  expect(result.current.active.mode).toBe('local')
  expect(result.current.active.request).toBeUndefined()
  const calls = request.mock.calls.length
  await expect(first('/library/v1/items')).rejects.toThrow('Account locked.')
  expect(request).toHaveBeenCalledTimes(calls)
})

test('remembered Basic data can reopen offline in its original sync namespace', async () => {
  const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
  const fetcher = vi.fn(async (path: string | URL) =>
    path === '/quire/v1/bootstrap'
      ? Response.json(config)
      : Response.json({ version: 1, user: { backend: 'calibreweb', id: 'reader' } }))
  vi.stubGlobal('fetch', fetcher)
  const original = renderHook(() => useAccount())
  await waitFor(() => expect(original.result.current.status).toBe('ready'))
  await act(async () => { await original.result.current.signInBasic('reader', 'private', true) })
  const namespace = original.result.current.active.namespace
  original.unmount()

  try {
    online.mockReturnValue(false)
    fetcher.mockRejectedValue(new TypeError('offline'))
    const offline = renderHook(() => useAccount())
    await waitFor(() => expect(offline.result.current.status).toBe('ready'))
    expect(offline.result.current.active.mode).toBe('local')
    act(() => offline.result.current.openRememberedOffline())
    expect(offline.result.current.active).toMatchObject({
      namespace, offline: true, remembered: true, capabilities: { progress_metadata_sync: true },
    })
    expect(offline.result.current.active.request).toBeUndefined()

    fetcher.mockImplementation(async (path: string | URL, options?: RequestInit) => {
      if (path === '/quire/v1/bootstrap') return Response.json(config)
      const credentials = new Headers(options?.headers).get('Authorization')
      const id = credentials === 'Basic ' + btoa('reader:private') ? 'reader' : 'different-reader'
      return Response.json({ version: 1, user: { backend: 'calibreweb', id } })
    })
    act(() => { online.mockReturnValue(true); window.dispatchEvent(new Event('online')) })
    await waitFor(() => expect(offline.result.current.bootstrap?.login).toBe('basic'))
    await act(async () => { await offline.result.current.signInBasic('different-reader', 'secret') })
    expect(offline.result.current.error).toBe('Sign in to the remembered account to reconnect.')
    expect(offline.result.current.active.request).toBeUndefined()
    await act(async () => { await offline.result.current.signInBasic('reader', 'private') })
    expect(offline.result.current.active).toMatchObject({ namespace, remembered: true })
    expect(offline.result.current.active.offline).toBeUndefined()
    expect(offline.result.current.active.request).toBeDefined()
  } finally { online.mockRestore() }
})
