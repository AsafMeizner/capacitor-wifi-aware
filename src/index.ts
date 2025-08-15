import { registerPlugin } from '@capacitor/core';

import type { WifiAwarePlugin } from './definitions';

const WifiAware = registerPlugin<WifiAwarePlugin>('WifiAware', {
  web: () => import('./web').then((m) => new m.WifiAwareWeb()),
});

export * from './definitions';
export { WifiAware };
