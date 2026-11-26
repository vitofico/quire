/**
 * Which way a click on the page turns it: the left third goes back, the right third goes
 * on, and the middle is left alone.
 */
export function clickTurn(x: number, width: number): -1 | 0 | 1 {
  if (width <= 0) return 0
  if (x < width / 3) return -1
  return x > width * 2 / 3 ? 1 : 0
}
