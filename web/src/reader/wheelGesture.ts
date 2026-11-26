// A trackpad swipe arrives as a stream of wheel events: the deltas grow while the fingers
// move, then shrink through a long inertia tail that can last a second or more. A new swipe
// often starts before that tail has died out, so waiting for a quiet gap is not enough to
// tell two swipes apart: the second one would be swallowed. A swipe is new when the events
// pause, change direction, or grow again after the previous swipe has faded.

const threshold = 20
const pause = 200

export type WheelTurn = -1 | 0 | 1

export function wheelGesture() {
  let active = false
  let sign = 0
  let peak = 0
  let low = 0
  let last = 0
  let lastTime = -Infinity

  return (delta: number, time: number): WheelTurn => {
    const size = Math.abs(delta)
    const direction = Math.sign(delta)
    const fresh = time - lastTime > pause
      || (direction !== 0 && direction !== sign && size >= threshold)
      || (low < peak / 2 && size > Math.max(last, low * 2) && size >= threshold)
    lastTime = time
    last = size
    if (active && !fresh) {
      peak = Math.max(peak, size)
      low = Math.min(low, size)
      return 0
    }
    active = false
    if (size < threshold) return 0
    active = true
    sign = direction
    peak = size
    low = size
    return direction > 0 ? 1 : -1
  }
}

/** Whether the content under the pointer would scroll itself in that direction. */
export function scrollsInside(target: EventTarget | null, deltaX: number, deltaY: number): boolean {
  for (let node = target instanceof Element ? target : null; node; node = node.parentElement) {
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
