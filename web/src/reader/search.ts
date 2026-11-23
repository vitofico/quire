interface Match { start: number; end: number }
interface TextChunk { node: Text; start: number; end: number }

export function searchableText(body: HTMLElement): { text: string; chunks: TextChunk[] } {
  const chunks: TextChunk[] = []
  const pieces: string[] = []
  let offset = 0
  const block = new Set(['p', 'div', 'li', 'section', 'article', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'br', 'td', 'blockquote'])
  function walk(node: Node) {
    if (node.nodeType === 3 || node.nodeType === 4) {
      const text = node as Text
      chunks.push({node: text, start: offset, end: offset + text.length})
      pieces.push(text.data)
      offset += text.length
      return
    }
    if (node.nodeType !== 1) return
    const tag = (node as Element).localName.toLowerCase()
    if (tag === 'script' || tag === 'style' || tag === 'noscript' || tag === 'template') return
    const separator = block.has(tag)
    if (separator) { pieces.push('\n'); offset++ }
    node.childNodes.forEach(walk)
    if (separator) { pieces.push('\n'); offset++ }
  }
  walk(body)
  return {text: pieces.join(''), chunks}
}

export function findMatches(text: string, query: string, limit: number): Match[] {
  if (!query.trim()) return []
  const escaped = query.trim().split(/\s+/).map(word => word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s+')
  const pattern = new RegExp(escaped, 'giu')
  const result: Match[] = []
  for (const match of text.matchAll(pattern)) {
    result.push({start: match.index!, end: match.index! + match[0].length})
    if (result.length === limit) break
  }
  return result
}

export function matchRange(doc: Document, chunks: TextChunk[], match: Match): Range | undefined {
  const start = chunks.find(chunk => match.start >= chunk.start && match.start < chunk.end)
  const end = chunks.find(chunk => match.end > chunk.start && match.end <= chunk.end)
  if (!start || !end) return
  const range = doc.createRange()
  range.setStart(start.node, match.start - start.start)
  range.setEnd(end.node, match.end - end.start)
  return range
}
