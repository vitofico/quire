/**
 * The app theme, as the Android app holds it: a mode (follow the device, always
 * light, always dark) and the light and dark palette each mode uses.
 * It belongs to this browser, as Android keeps it on the phone.
 * index.html reads the same keys before the app loads, so the first paint
 * already has the theme: a change here belongs there too.
 */
export type ThemeMode = 'system' | 'light' | 'dark'
export const lightThemes = ['paper', 'sage'] as const
export const darkThemes = ['ink', 'midnight'] as const
export type LightTheme = (typeof lightThemes)[number]
export type DarkTheme = (typeof darkThemes)[number]
export type Theme = LightTheme | DarkTheme
export interface ThemeChoice {
  mode: ThemeMode
  light: LightTheme
  dark: DarkTheme
}

export const themeModes: ThemeMode[] = ['system', 'light', 'dark']
export const defaultTheme: ThemeChoice = { mode: 'system', light: 'paper', dark: 'ink' }
export const themeNames: Record<Theme, string> = { paper: 'Paper', sage: 'Sage', ink: 'Ink', midnight: 'Midnight' }

const pick = <T extends string>(value: string | null, options: readonly T[], fallback: T): T =>
  options.includes(value as T) ? (value as T) : fallback

/**
 * The choice stored under `theme-mode`, `theme-light` and `theme-dark`. A browser
 * that kept one theme under `theme` (Paper, Sage, Ink, Midnight or System) keeps it.
 */
export function readThemeChoice(read: (key: string) => string | null): ThemeChoice {
  const legacy = read('theme')?.toLowerCase() ?? null
  const fromLegacy: ThemeChoice = lightThemes.includes(legacy as LightTheme)
    ? { ...defaultTheme, mode: 'light', light: legacy as LightTheme }
    : darkThemes.includes(legacy as DarkTheme)
      ? { ...defaultTheme, mode: 'dark', dark: legacy as DarkTheme }
      : defaultTheme
  return {
    mode: pick(read('theme-mode'), themeModes, fromLegacy.mode),
    light: pick(read('theme-light'), lightThemes, fromLegacy.light),
    dark: pick(read('theme-dark'), darkThemes, fromLegacy.dark),
  }
}

/** The palette shown now: the dark one in dark mode, or in system mode while the device is dark. */
export function resolveTheme(choice: ThemeChoice, deviceDark: boolean): Theme {
  const dark = choice.mode === 'dark' || (choice.mode === 'system' && deviceDark)
  return dark ? choice.dark : choice.light
}
