import { WebPlugin } from '@capacitor/core';
import type { WifiAwarePlugin, AttachResult, PublishOptions, SubscribeOptions, Message, StartSocketOptions, SocketResult } from './definitions';

export class WifiAwareWeb extends WebPlugin implements WifiAwarePlugin {
  private unsupported(): never {
    throw this.unavailable('Wi-Fi Aware is not available on web.');
  }
  async attach(): Promise<AttachResult> { return { available: false, reason: 'Web not supported' }; }
  async publish(_: PublishOptions): Promise<void> { this.unsupported(); }
  async stopPublish(): Promise<void> { this.unsupported(); }
  async subscribe(_: SubscribeOptions): Promise<void> { this.unsupported(); }
  async stopSubscribe(): Promise<void> { this.unsupported(); }
  async sendMessage(_: Message): Promise<void> { this.unsupported(); }
  async startSocket(_: StartSocketOptions): Promise<SocketResult> { this.unsupported(); }
  async stopSocket(): Promise<void> { this.unsupported(); }
}
