import type { Mutation, QueueStorage } from './types'

let database: Promise<IDBDatabase> | undefined
function open(): Promise<IDBDatabase> {
  database ??= new Promise((resolve, reject) => {
    const opening = indexedDB.open('interview-flashcards-sync', 1)
    opening.onupgradeneeded = () => opening.result.createObjectStore('mutations', { keyPath: 'operationId' })
    opening.onsuccess = () => {
      opening.result.onversionchange = () => { opening.result.close(); database = undefined }
      resolve(opening.result)
    }
    opening.onerror = () => { database = undefined; reject(opening.error) }
    opening.onblocked = () => { database = undefined; reject(new Error('本地存储被其他页面占用')) }
  })
  return database
}
async function transaction<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await open()
  return new Promise((resolve, reject) => {
    const tx = db.transaction('mutations', mode)
    const request = action(tx.objectStore('mutations'))
    // Only report success after commit, including quota failures during Blob writes.
    tx.oncomplete = () => resolve(request.result)
    tx.onabort = () => reject(tx.error ?? request.error ?? new Error('本地保存失败'))
    tx.onerror = () => reject(tx.error ?? request.error)
  })
}
export const queueStorage: QueueStorage = {
  all: () => transaction<Mutation[]>('readonly', store => store.getAll()),
  put: async item => { await transaction('readwrite', store => store.put(item)) },
  remove: async id => { await transaction('readwrite', store => store.delete(id)) },
}
