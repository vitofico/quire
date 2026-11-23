import { useState, type FormEvent } from 'react'
import { allowsBasic, allowsNative, pendingOnDevice, type AccountState } from './useAccount'

type Props = {
  account: AccountState
  pending: number
  sync: () => Promise<unknown>
  clear: (namespace: string, discardPending?: boolean) => Promise<void>
  t?: (label: string) => string
}

export function AccountSettings({ account, pending, sync, clear, t = label => label }: Props) {
  const { active, bootstrap, status, error } = account
  const [remember, setRemember] = useState(false)
  const [shareCatalog, setShareCatalog] = useState(false)
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
  const basic = allowsBasic(bootstrap)
  const password = allowsNative(bootstrap) && bootstrap?.password
  const oidc = allowsNative(bootstrap) && bootstrap?.oidc
  const reconnectBasic = active.offline && active.identity?.backend === 'calibreweb' && account.online && basic
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

  const signInForm = (legacy: boolean) => <form onSubmit={event => signIn(event, legacy)}>
    <label>{legacy ? t('Calibre-Web username') : t('Email')} <input type={legacy ? 'text' : 'email'} name="username" autoComplete="username" required /></label>
    <label>{t('Password')} <input type="password" name="password" autoComplete="current-password" required /></label>
    <button type="submit">{t('Sign in')}</button>
  </form>

  return <section aria-label={t('Account')}>
    <h2>{t('Account')}</h2>
    {error && <p role="alert">{t(error)}</p>}
    {account.nativeWebUrl && <p><a href={account.nativeWebUrl} target="_blank" rel="noopener noreferrer">
      {t('Open Quire sign-in')}
    </a></p>}
    {connected ? <>
      <p>{active.identity?.id} · {active.identity?.backend === 'native' ? 'Quire' : 'Calibre-Web'}</p>
      {active.offline && <p>{t('Offline account. Sign in online to reconnect.')}</p>}
      {reconnectBasic && <fieldset disabled={busy}><legend>{t('Reconnect this account')}</legend>{signInForm(true)}</fieldset>}
      <p>{active.remembered ? t('Offline data is saved in this browser profile.') : t('Shared device session.')}</p>
      {pending > 0 && <p role="status">{pendingMessage(pending)}</p>}
      {pending > 0 && !active.remembered && <label>
        <input type="checkbox" checked={discard} onChange={event => setDiscard(event.target.checked)} />
        {t('Discard unsent changes when signing out')}
      </label>}
      <button type="button" disabled={busy} onClick={() => void account.signOut({ pending, sync, clear, discard })}>
        {pending > 0 && !active.remembered && !discard ? t('Sync pending changes') : t('Sign out')}
      </button>
      {active.identity?.backend === 'native' && active.request &&
        <button type="button" disabled={busy} onClick={() => void account.exportAccount()}>{t('Export account data')}</button>}
      {bootstrap?.accountManagement && <p><a href={bootstrap.accountManagement}>{t('Manage account and devices')}</a></p>}
    </> : <>
      <p>{active.mode === 'local' ? t('Local library') : t('Catalog library')}. {t('Changes are stored in this browser.')}</p>
      {bootstrap?.catalogUrl && <>
        <button type="button" disabled={busy || active.mode === 'local'} onClick={account.openLocal}>{t('Local library')}</button>
        <button type="button" disabled={busy || active.mode === 'catalog'} onClick={account.openCatalog}>{t('Catalog library')}</button>
      </>}
      {account.rememberedIdentity && !account.online &&
        <button type="button" onClick={account.openRememberedOffline}>{t('Open remembered account offline')}</button>}
      {(basic || password || oidc) && <fieldset disabled={busy}>
        <legend>{t('Sign in')}</legend>
        <label>
          <input type="checkbox" checked={remember} onChange={event => setRemember(event.target.checked)} />
          {t('Remember offline data on this browser')}
        </label>
        <p>{t('Only use this option on a personal device. Offline data is accessible through your browser profile.')}</p>
        {basic && <fieldset>
          <legend>{t('Calibre-Web account')}</legend>
          <p>{t('Calibre-Web credentials are held for this page session. Sign in again after reloading.')}</p>
          {sameOriginCatalog && <label>
            <input type="checkbox" checked={shareCatalog} onChange={event => setShareCatalog(event.target.checked)} />
            {t('Use these credentials for the configured catalog')}
          </label>}
          {signInForm(true)}
        </fieldset>}
        {(password || oidc) && <fieldset>
          <legend>{t('Quire account')}</legend>
          {password && signInForm(false)}
          {oidc && <button type="button" onClick={() => account.signInOidc(remember)}>{t('Continue with identity provider')}</button>}
        </fieldset>}
      </fieldset>}
      {bootstrap?.ai && !bootstrap.aiAvailable && <p>{t('Insights are unavailable for browser accounts on this server.')}</p>}
      {account.retained.map(({ identity, namespace }) => <div key={namespace}>
        <p>{t('Locked offline data for {account}').replace('{account}', identity.id)}</p>
        {clearTarget?.namespace === namespace ? <>
          <p>{t('Clearing this account deletes saved books and unsent changes on this device.')}</p>
          <p>{clearTarget.pending === null
            ? t('The pending change count is unavailable.')
            : pendingMessage(clearTarget.pending)}</p>
          {clearTarget.pending !== 0 && <p>{t('Sign in again to sync these changes before clearing them.')}</p>}
          <button type="button" disabled={busy} onClick={() => {
            void account.clearDeviceData(namespace, { pending: clearTarget.pending ?? 0, sync, clear, discard: true })
            setClearTarget(undefined)
          }}>{t('Delete offline data and unsent changes')}</button>
          <button type="button" onClick={() => setClearTarget(undefined)}>{t('Cancel')}</button>
        </> : <button type="button" disabled={busy || inspecting} onClick={() => void inspect(namespace)}>{t('Clear device data')}</button>}
      </div>)}
    </>}
  </section>
}
