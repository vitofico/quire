import { expect, type Page } from '@playwright/test'

export async function waitForSavedChapter(page: Page, href: string, passage?: string) {
  await expect.poll(() => page.evaluate(async ({ href, passage }) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const name = `quire-web:${JSON.stringify([location.origin, 'local', ''])}`
      const request = indexedDB.open(name)
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    try {
      return await new Promise<boolean>((resolve, reject) => {
        const request = database.transaction('localProgress').objectStore('localProgress').getAll()
        request.onsuccess = () => resolve(request.result.some(item => {
          if (!item.locator) return false
          const locator = JSON.parse(item.locator)
          return locator.href === href && (passage === undefined || locator.text?.highlight?.trim() === passage)
        }))
        request.onerror = () => reject(request.error)
      })
    } finally {
      database.close()
    }
  }, { href, passage })).toBe(true)
}
