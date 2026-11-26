// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { scrollsInside, wheelGesture } from './wheelGesture'

// A two-finger trackpad swipe: deltas grow while the fingers move, then an inertia tail
// fades out over about a second, one event every 16 ms.
function swipe(sign: number, start: number): Array<[number, number]> {
  const moving = [4, 12, 28, 46, 60, 58]
  const tail = Array.from({ length: 70 }, (_, i) => Math.max(1, Math.round(55 * 0.94 ** i)))
  return [...moving, ...tail].map((size, i) => [sign * size, start + i * 16])
}

function turns(events: Array<[number, number]>, gesture = wheelGesture()) {
  return events.map(([delta, time]) => gesture(delta, time)).filter(turn => turn !== 0)
}

describe('wheelGesture', () => {
  it('turns one page for a swipe and its whole inertia tail', () => {
    expect(turns(swipe(1, 0))).toEqual([1])
  })

  it('turns again for a second swipe that starts while the first tail is still fading', () => {
    const first = swipe(1, 0)
    // The fingers land again 700 ms in: the tail is cut short and a new swipe begins.
    const second = swipe(1, 700)
    const events = [...first.filter(([, time]) => time < 700), ...second]
    expect(turns(events)).toEqual([1, 1])
  })

  it('turns once per swipe for a run of quick swipes', () => {
    const events = [0, 600, 1200, 1800].flatMap((start, i, all) =>
      swipe(1, start).filter(([, time]) => i === all.length - 1 || time < all[i + 1]))
    expect(turns(events)).toEqual([1, 1, 1, 1])
  })

  it('turns back when the next swipe goes the other way', () => {
    const events = [...swipe(1, 0).filter(([, time]) => time < 500), ...swipe(-1, 500)]
    expect(turns(events)).toEqual([1, -1])
  })

  it('turns again after a pause', () => {
    expect(turns([...swipe(-1, 0), ...swipe(-1, 3000)])).toEqual([-1, -1])
  })

  it('ignores a tiny nudge', () => {
    expect(turns([[3, 0], [5, 16], [2, 32]])).toEqual([])
  })
})

describe('scrollsInside', () => {
  it('leaves wheel events to a box that can still scroll that way', () => {
    document.body.innerHTML = '<pre style="overflow-y: auto"><span>code</span></pre>'
    const box = document.querySelector('pre')!
    Object.defineProperties(box, {
      scrollHeight: { value: 400 }, clientHeight: { value: 100 }, scrollTop: { value: 0, writable: true },
    })
    const inner = document.querySelector('span')!
    expect(scrollsInside(inner, 0, 40)).toBe(true)
    expect(scrollsInside(inner, 0, -40)).toBe(false)
    box.scrollTop = 300
    expect(scrollsInside(inner, 0, 40)).toBe(false)
    expect(scrollsInside(document.body, 0, 40)).toBe(false)
  })
})
