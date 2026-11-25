// Line icons on a 24-unit grid, drawn with the current text colour.
const paths = {
  library: 'M4 19.5V5a2 2 0 0 1 2-2h13v15H6.5A2.5 2.5 0 0 0 4 20.5 2.5 2.5 0 0 0 6.5 23H19v-5',
  catalog: 'M3 4h7a3 3 0 0 1 3 3v14a2 2 0 0 0-2-2H3zM21 4h-5a3 3 0 0 0-3 3v14a2 2 0 0 1 2-2h6z',
  highlights: 'M9 11l-5 5v4h4l5-5M14 6l4 4M15.5 4.5a2.1 2.1 0 0 1 3 3L11 15l-4-4z',
  discover: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM15.5 8.5l-2 5-5 2 2-5z',
  settings: 'M4 7h10M18 7h2M4 17h4M12 17h8M16 5v4M10 15v4',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4',
  back: 'M15 5l-7 7 7 7',
  next: 'M9 5l7 7-7 7',
  more: 'M4.2 12a.8.8 0 1 0 1.6 0a.8.8 0 1 0-1.6 0M11.2 12a.8.8 0 1 0 1.6 0a.8.8 0 1 0-1.6 0M18.2 12a.8.8 0 1 0 1.6 0a.8.8 0 1 0-1.6 0',
  grid: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  list: 'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01',
  contents: 'M4 6h16M4 12h10M4 18h13',
  passages: 'M6 4h12v17l-6-4-6 4z',
  type: 'M4 19l5-14h1l5 14M6 14h7M15 19l2.5-7h1l2.5 7M16 17h4',
  pause: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2',
  expand: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5',
  upload: 'M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3',
  scan: 'M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M8 8v8M11 8v8M14 8v8M17 8v8',
  device: 'M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2',
  cloud: 'M7 18a4 4 0 0 1-.6-8A6 6 0 0 1 18 9.5a4.3 4.3 0 0 1-.5 8.5z',
} as const

export type IconName = keyof typeof paths

export function Icon({ name }: { name: IconName }) {
  return (
    <svg className="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d={paths[name]} />
    </svg>
  )
}
