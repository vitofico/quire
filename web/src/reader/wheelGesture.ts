// A trackpad swipe arrives as a stream of wheel events: the deltas grow while the fingers
// move, then shrink through a long inertia tail that can last a second or more. A new swipe
// often starts before that tail has died out, so waiting for a quiet gap is not enough to
// tell two swipes apart: the second one would be swallowed. A swipe is new when the events
// pause, change direction, or grow again once the previous swipe has clearly faded. Each
// swipe keeps the axis it started on, so a wobble towards the other axis is not a reversal.

const threshold = 20
const pause = 200

export type WheelSwipe = { turn: -1 | 1, sideways: boolean }

export function wheelGesture() {
  let axis: 'x' | 'y' | undefined
  let sign = 0
  let peak = 0
  let low = 0
  let last = 0
  let falling = 0
  let faded = false
  let lastTime = -Infinity

  return (deltaX: number, deltaY: number, time: number): WheelSwipe | undefined => {
    const paused = time - lastTime > pause
    lastTime = time
    if (axis && !paused) {
      const delta = axis === 'x' ? deltaX : deltaY
      const size = Math.abs(delta)
      const direction = Math.sign(delta)
      const reversed = direction !== 0 && direction !== sign && size >= threshold
      const regrown = faded && low < peak / 2 && size > Math.max(last, low * 2) && size >= threshold
      if (!reversed && !regrown) {
        falling = size <= last ? falling + 1 : 0
        faded ||= falling >= 3 || size < threshold / 2
        peak = Math.max(peak, size)
        low = Math.min(low, size)
        last = size
        return undefined
      }
    } else {
      axis = Math.abs(deltaX) > Math.abs(deltaY) ? 'x' : 'y'
    }
    const delta = axis === 'x' ? deltaX : deltaY
    const size = Math.abs(delta)
    if (size < threshold) {
      axis = undefined
      return undefined
    }
    sign = Math.sign(delta)
    peak = size
    low = size
    last = size
    falling = 0
    faded = false
    return { turn: sign > 0 ? 1 : -1, sideways: axis === 'x' }
  }
}

/** Whether the content under the pointer would scroll itself in that direction. */
export function scrollsInside(target: EventTarget | null, deltaX: number, deltaY: number): boolean {
  // The book is laid out in an iframe, whose nodes are not instances of this window's Element.
  for (let node = (target as Node | null)?.nodeType === 1 ? target as Element : null; node; node = node.parentElement) {
    if (node === node.ownerDocument.documentElement || node === node.ownerDocument.body) return false
    const style = node.ownerDocument.defaultView?.getComputedStyle(node)
    const vertical = Math.abs(deltaY) >= Math.abs(deltaX)
    const overflow = vertical ? style?.overflowY : style?.overflowX
    if (overflow !== 'auto' && overflow !== 'scroll') continue
    const room = vertical
      ? (deltaY > 0 ? node.scrollHeight - node.clientHeight - node.scrollTop : node.scrollTop)
      : (deltaX > 0 ? node.scrollWidth - node.clientWidth - node.scrollLeft : node.scrollLeft)
    if (room > 0) return true
  }
  return false
}
