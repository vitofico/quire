import { expect, it } from 'vitest'
import { clickTurn } from './clickTurn'

it('turns back from the left third, on from the right third, and not from the middle', () => {
  expect([0, 299, 300, 450, 600, 601, 899].map(x => clickTurn(x, 900))).toEqual([-1, -1, 0, 0, 0, 1, 1])
  expect(clickTurn(10, 0)).toBe(0)
})
