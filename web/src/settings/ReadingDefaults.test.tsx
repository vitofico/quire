import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { ReadingDefaults } from './ReadingDefaults'

afterEach(cleanup)

it('says storage is unavailable instead of loading for ever', () => {
  const { rerender } = render(<ReadingDefaults lang="en" t={(label) => label} />)
  expect(screen.getByText('Loading…')).toBeTruthy()
  rerender(<ReadingDefaults unavailable lang="en" t={(label) => label} />)
  expect(screen.getByRole('alert').textContent).toBe('Storage is unavailable.')
})
