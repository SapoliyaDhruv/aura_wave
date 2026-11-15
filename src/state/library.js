import { useSyncExternalStore } from 'react';
import { library } from '../services/library.js';

/** Subscribes a component to the library store. */
export function useLibrary() {
  return useSyncExternalStore(library.subscribe, () => library.getState());
}
