const stores = ['books', 'epubs', 'library', 'progress', 'highlights', 'localProgress', 'localHighlights', 'queue', 'settings'] as const
export type Store = (typeof stores)[number]

export function result<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

export async function openStorage(namespace: string): Promise<IDBDatabase> {
  const request = indexedDB.open(`quire-web:${namespace}`, 1)
  request.onupgradeneeded = () => {
    for (const store of stores) request.result.createObjectStore(store)
  }
  return result(request)
}

export async function transaction<T>(
  db: IDBDatabase,
  names: Store[],
  mode: IDBTransactionMode,
  action: (store: (name: Store) => IDBObjectStore) => Promise<T>,
): Promise<T> {
  const tx = db.transaction(names, mode)
  const completion = new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onabort = () => reject(tx.error ?? new Error('Storage transaction aborted'))
  })
  try {
    const [value] = await Promise.all([action((name) => tx.objectStore(name)), completion])
    return value
  } catch (error) {
    try {
      tx.abort()
    } catch {
      // A failed request can already have aborted the transaction.
    }
    throw error
  }
}

export function clearStorage(db: IDBDatabase, discardPending: boolean): Promise<void> {
  return transaction(db, [...stores], 'readwrite', async (store) => {
    if (!discardPending && await result(store('queue').count()) > 0) {
      throw new Error('Changes are waiting to sync')
    }
    await Promise.all(stores.map((name) => result(store(name).clear())))
  })
}
