import { WebPlugin } from '@capacitor/core';

import type { 
  WifiAwarePlugin, 
  AttachResult, 
  SocketResult,
  DeviceInfo
} from './definitions';

export class WifiAwareWeb extends WebPlugin implements WifiAwarePlugin {
  private unsupported(): never {
    throw this.unavailable('Wi-Fi Aware is not available on web.');
  }
  
  async attach(): Promise<AttachResult> { 
    return { 
      available: false, 
      reason: 'Wi-Fi Aware is not supported in web environments'
    }; 
  }
  
  async getDeviceInfo(): Promise<DeviceInfo> {
    throw this.unavailable('Wi-Fi Aware is not available on web.');
  }
  
  async publish(): Promise<void> { this.unsupported(); }
  async stopPublish(): Promise<void> { this.unsupported(); }
  
  async subscribe(): Promise<void> { this.unsupported(); }
  async stopSubscribe(): Promise<void> { this.unsupported(); }
  
  async sendMessage(): Promise<void> { this.unsupported(); }
  
  async sendFile(): Promise<string> { 
    throw this.unavailable('Wi-Fi Aware is not available on web.');
  }
  
  async cancelFileTransfer(): Promise<void> {
    this.unsupported();
  }

  async sendFileTransfer(): Promise<{ transferId: string }> { 
    throw this.unavailable('Wi-Fi Aware is not available on web.');
  }
  
  async respondToFileTransfer(): Promise<void> { 
    this.unsupported();
  }
  
  async startSocket(): Promise<SocketResult> { this.unsupported(); }
  async stopSocket(): Promise<void> { this.unsupported(); }
}
