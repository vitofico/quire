import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Icon } from './icons'

/** A small overflow menu that closes on an outside click, Escape or a chosen item. */
export function Menu({ label, children }: { label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  const menu = useRef<HTMLDivElement>(null)
  const toggle = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!open) return
    const outside = (event: Event) => {
      if (!menu.current?.contains(event.target as Node)) setOpen(false)
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      // Used here: a panel listening for Escape further out stays open.
      event.preventDefault()
      setOpen(false)
      toggle.current?.focus()
    }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('keydown', escape, true)
    return () => {
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('keydown', escape, true)
    }
  }, [open])
  return (
    <div className="menu" ref={menu}>
      <button
        type="button"
        ref={toggle}
        className="menu-toggle"
        aria-label={label}
        title={label}
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <Icon name="more" />
      </button>
      {open && (
        <div
          className="menu-items"
          onClick={(event) => {
            if ((event.target as HTMLElement).closest('button')) setOpen(false)
          }}
        >
          {children}
        </div>
      )}
    </div>
  )
}
