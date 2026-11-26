import { useState, type FormEvent } from 'react'
import { allowsBasic, allowsNative, pendingOnDevice, type AccountState } from './useAccount'

type Props = {
  account: AccountState
  pending: number
  sync: () => Promise<unknown>
  clear: (namespace: string, discardPending?: boolean) => Promise<void>
  t?: (label: string) => string
}

type SignInProps = { account: AccountState, t?: (label: string) => string, calibreOnly?: boolean }

/** Every sign-in route the server offers, the main one first and the rest folded away. */
export function SignIn({ account, t = label => label, calibreOnly }: SignInProps) {
  const { bootstrap, status } = account
  const [remember, setRemember] = useState(false)
  const [shareCatalog, setShareCatalog] = useState(false)
  const busy = status !== 'ready'
  const basic = allowsBasic(bootstrap)
  const password = allowsNative(bootstrap) && bootstrap?.password
  const oidc = allowsNative(bootstrap) && bootstrap?.oidc
  const native = password || oidc
  const sameOriginCatalog = basic && !!bootstrap?.catalogUrl &&
    new URL(bootstrap.catalogUrl, location.origin).origin === location.origin

  function signIn(event: FormEvent<HTMLFormElement>, legacy: boolean) {
    event.preventDefault()
    const body = new FormData(event.currentTarget)
    const username = String(body.get('username') ?? '')
    const password = String(body.get('password') ?? '')
    event.currentTarget.reset()
    if (legacy) void account.signInBasic(username, password, remember, shareCatalog)
    else void account.signInPassword(username, password, remember)
  }

  function migrate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const body = new FormData(event.currentTarget)
    const field = (name: string) => String(body.get(name) ?? '')
    event.currentTarget.reset()
    void account.migrateCalibre(field('username'), field('calibre-password'), field('email'), field('password'), remember)
  }

  const signInForm = (legacy: boolean) => <form className="stack" onSubmit={event => signIn(event, legacy)}>
    <label>{legacy ? t('Calibre-Web username') : t('Email')} <input type={legacy ? 'text' : 'email'} name="username" autoComplete="username" required /></label>
    <label>{t('Password')} <input type="password" name="password" autoComplete="current-password" required /></label>
    {legacy && sameOriginCatalog && <label className="check">
      <input type="checkbox" checked={shareCatalog} onChange={event => setShareCatalog(event.target.checked)} />
      {t('Use these credentials for the configured catalog')}
    </label>}
    {legacy && <p className="hint">{t('Calibre-Web credentials are held for this page session. Sign in again after reloading.')}</p>}
    <button type="submit" className="primary">{t('Sign in')}</button>
  </form>

  if (!basic && !native) return null
  if (calibreOnly) return <fieldset className="sign-in" disabled={busy}>{signInForm(true)}</fieldset>
  return <fieldset className="sign-in" disabled={busy}>
    <legend className="visually-hidden">{t('Sign in')}</legend>
    {password ? signInForm(false) : !native && signInForm(true)}
    {oidc && <button type="button" className={password ? undefined : 'primary'} onClick={() => account.signInOidc(remember)}>
      {t('Continue with identity provider')}
    </button>}
    <label className="check">
      <input type="checkbox" checked={remember} onChange={event => setRemember(event.target.checked)} />
      {t('Remember offline data on this browser')}
    </label>
    <p className="hint">{t('Only use this option on a personal device. Offline data is accessible through your browser profile.')}</p>
    {basic && native && <details className="disclosure">
      <summary>{t('Calibre-Web account')}</summary>
      {signInForm(true)}
    </details>}
    {bootstrap?.migration && password && <details className="disclosure">
      <summary>{t('Move a Calibre-Web account to Quire')}</summary>
      <p className="hint">{t('Create your Quire account with your Calibre-Web login. Your reading progress and highlights move with it.')}</p>
      <form className="stack" onSubmit={migrate}>
        <label>{t('Calibre-Web username')} <input type="text" name="username" autoComplete="off" required /></label>
        <label>{t('Calibre-Web password')} <input type="password" name="calibre-password" autoComplete="off" required /></label>
        <label>{t('Email')} <input type="email" name="email" autoComplete="email" required /></label>
        <label>{t('New password')} <input type="password" name="password" autoComplete="new-password" minLength={12} required /></label>
        <button type="submit">{t('Create Quire account')}</button>
      </form>
    </details>}
  </fieldset>
}

export function AccountSettings({ account, pending, sync, clear, t = label => label }: Props) {
  const { active, bootstrap, status, error } = account
  const [discard, setDiscard] = useState(false)
  const [clearTarget, setClearTarget] = useState<{ namespace: string, pending: number | null }>()
  const [inspecting, setInspecting] = useState(false)
  async function inspect(namespace: string) {
    setInspecting(true)
    try {
      setClearTarget({ namespace, pending: await pendingOnDevice(namespace) })
    } catch {
      setClearTarget({ namespace, pending: null })
    } finally { setInspecting(false) }
  }
  function pendingMessage(count: number) {
    return t(count === 1 ? '{count} pending change.' : '{count} pending changes.').replace('{count}', String(count))
  }
  const busy = status !== 'ready'
  const connected = active.mode === 'connected'
  const reconnectBasic = active.offline && active.identity?.backend === 'calibreweb' && account.online && allowsBasic(bootstrap)

  return <>
    {error && <p role="alert" className="notice">{t(error)}</p>}
    {account.nativeWebUrl && <p><a href={account.nativeWebUrl} target="_blank" rel="noopener noreferrer">
      {t('Open Quire sign-in')}
    </a></p>}
    {connected ? <>
      <p className="identity">{active.identity?.id} · {active.identity?.backend === 'native' ? 'Quire' : 'Calibre-Web'}</p>
      {active.offline && <p>{t('Offline account. Sign in online to reconnect.')}</p>}
      {reconnectBasic && <section aria-label={t('Reconnect this account')}><h3>{t('Reconnect this account')}</h3><SignIn account={account} t={t} calibreOnly /></section>}
      <p className="hint">{active.remembered ? t('Offline data is saved in this browser profile.') : t('Shared device session.')}</p>
      {pending > 0 && <p role="status">{pendingMessage(pending)}</p>}
      {pending > 0 && !active.remembered && <label className="check">
        <input type="checkbox" checked={discard} onChange={event => setDiscard(event.target.checked)} />
        {t('Discard unsent changes when signing out')}
      </label>}
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => void account.signOut({ pending, sync, clear, discard })}>
          {pending > 0 && !active.remembered && !discard ? t('Sync pending changes') : t('Sign out')}
        </button>
        {active.identity?.backend === 'native' && active.request &&
          <button type="button" disabled={busy} onClick={() => void account.exportAccount()}>{t('Export account data')}</button>}
      </div>
      {bootstrap?.accountManagement && <p><a href={bootstrap.accountManagement}>{t('Manage account and devices')}</a></p>}
    </> : <>
      <p>{active.mode === 'local' ? t('Local library') : t('Catalog library')}. {t('Changes are stored in this browser.')}</p>
      {bootstrap?.catalogUrl && <div className="segmented" role="group" aria-label={t('Library')}>
        <button type="button" aria-pressed={active.mode === 'local'} disabled={busy} onClick={account.openLocal}>{t('Local library')}</button>
        <button type="button" aria-pressed={active.mode === 'catalog'} disabled={busy} onClick={account.openCatalog}>{t('Catalog library')}</button>
      </div>}
      {account.rememberedIdentity && !account.online &&
        <button type="button" onClick={account.openRememberedOffline}>{t('Open remembered account offline')}</button>}
      <SignIn account={account} t={t} />
      {bootstrap?.ai && !bootstrap.aiAvailable && <p className="hint">{t('Insights are unavailable for browser accounts on this server.')}</p>}
      {account.retained.map(({ identity, namespace }) => <div key={namespace} className="notice">
        <p>{t('Locked offline data for {account}').replace('{account}', identity.id)}</p>
        {clearTarget?.namespace === namespace ? <>
          <p>{t('Clearing this account deletes saved books and unsent changes on this device.')}</p>
          <p>{clearTarget.pending === null
            ? t('The pending change count is unavailable.')
            : pendingMessage(clearTarget.pending)}</p>
          {clearTarget.pending !== 0 && <p>{t('Sign in again to sync these changes before clearing them.')}</p>}
          <div className="actions">
            <button type="button" className="danger" disabled={busy} onClick={() => {
              void account.clearDeviceData(namespace, { pending: clearTarget.pending ?? 0, sync, clear, discard: true })
              setClearTarget(undefined)
            }}>{t('Delete offline data and unsent changes')}</button>
            <button type="button" onClick={() => setClearTarget(undefined)}>{t('Cancel')}</button>
          </div>
        </> : <button type="button" disabled={busy || inspecting} onClick={() => void inspect(namespace)}>{t('Clear device data')}</button>}
      </div>)}
    </>}
  </>
}
