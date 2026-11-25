type Options = { confirm: string; cancel: string; destructive?: boolean }

/** An in-app replacement for window.confirm: resolves true only on the confirm button. */
export function ask(message: string, { confirm, cancel, destructive }: Options) {
  const dialog = document.createElement('dialog')
  dialog.className = 'ask'
  const text = document.createElement('p')
  text.textContent = message
  const form = document.createElement('form')
  form.method = 'dialog'
  form.className = 'actions'
  const no = document.createElement('button')
  no.value = 'cancel'
  no.textContent = cancel
  const yes = document.createElement('button')
  yes.value = 'confirm'
  yes.textContent = confirm
  yes.className = destructive ? 'danger' : 'primary'
  form.append(no, yes)
  dialog.append(text, form)
  document.body.append(dialog)
  const opener = document.activeElement as HTMLElement | null
  return new Promise<boolean>((resolve) => {
    dialog.addEventListener('close', () => {
      resolve(dialog.returnValue === 'confirm')
      dialog.remove()
      opener?.focus()
    })
    dialog.showModal()
    no.focus()
  })
}
