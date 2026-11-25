import { SignIn, type AccountState } from './account'
import { Icon } from './icons'
import { Mark } from './Mark'
import noticesUrl from './legal/notices.txt?url'

type Props = {
  account: AccountState
  t: (s: string) => string
  /** Present when the server lets signed-out visitors read their own files. */
  readAsGuest?: () => void
}

/** The front door for signed-out visitors on a server that offers accounts. */
export function Welcome({ account, t, readAsGuest }: Props) {
  return (
    <div className="welcome">
      <main className="welcome-main" id="content">
        <div className="brand">
          <Mark />
          <span>Quire</span>
        </div>
        <h1>{t('Your books, and your place in them.')}</h1>
        <p className="lede">
          {t('Read EPUBs in the browser, keep the passages that matter and continue on your phone where you stopped.')}
        </p>
        <section className="panel welcome-card" aria-label={t('Sign in')}>
          <h2>{t('Sign in')}</h2>
          {account.error && <p role="alert" className="notice">{t(account.error)}</p>}
          {account.nativeWebUrl && (
            <p>
              <a href={account.nativeWebUrl} target="_blank" rel="noopener noreferrer">
                {t('Open Quire sign-in')}
              </a>
            </p>
          )}
          <SignIn account={account} t={t} />
          {account.rememberedIdentity && !account.online && (
            <button type="button" onClick={account.openRememberedOffline}>
              {t('Open remembered account offline')}
            </button>
          )}
        </section>
        {readAsGuest && (
          <section className="guest" aria-label={t('Read without an account')}>
            <button type="button" className="ghost" onClick={readAsGuest}>
              <Icon name="device" />
              {t('Read on this device without an account')}
            </button>
            <p className="hint">{t('Your books stay in this browser. Nothing is uploaded.')}</p>
          </section>
        )}
        <footer className="welcome-footer">
          <a href="https://github.com/vitofico/quire">{t('Source code')}</a>
          <a href={noticesUrl}>{t('Open-source licenses')}</a>
        </footer>
      </main>
    </div>
  )
}
