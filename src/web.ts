import { WebPlugin } from '@capacitor/core';

import type { WifiAwarePlugin } from './definitions';

export class WifiAwareWeb extends WebPlugin implements WifiAwarePlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
