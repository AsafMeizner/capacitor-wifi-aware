import type { PluginListenerHandle } from '@capacitor/core';

export type Role = 'publisher' | 'subscriber';

export interface AttachResult {
  available: boolean;       // true if Wi-Fi Aware stack is available & enabled
  reason?: string;          // message if not available
  androidApiLevel?: number;
  instantCommSupported?: boolean; // Android 13+ devices that support it
}

export interface PublishOptions {
  serviceName: string;          // e.g. "aware_files" (<=15 chars, a-z0-9-)
  serviceInfoBase64?: string;   // small payload (<=~255 bytes total)
  instantMode?: boolean;        // Android 13+ instant comm mode (30s)
  rangingEnabled?: boolean;     // Allow RTT-assisted distance
}

export interface SubscribeOptions {
  serviceName: string;
  instantMode?: boolean;
  minDistanceMm?: number;       // requires publisher rangingEnabled
  maxDistanceMm?: number;
}

export interface Message {
  peerId: string;   // plugin-issued ID for a PeerHandle (Android) or endpoint (iOS)
  dataBase64: string;
}

export interface StartSocketOptions {
  peerId: string;         // discovered peer
  pskPassphrase: string;  // 8..63 chars; used to encrypt the NAN datapath
  // Publisher only:
  asServer?: boolean;     // if true, publisher creates ServerSocket (recommended)
}

export interface SocketResult {
  role: Role;
  localPort?: number;         // publisher server port, if server
  peerIpv6?: string;          // subscriber learns publisher address
  peerPort?: number;          // subscriber learns publisher port
}

export interface WifiAwarePlugin {
  // Basic availability; must be called first
  attach(): Promise<AttachResult>;

  // Publisher role
  publish(options: PublishOptions): Promise<void>;
  stopPublish(): Promise<void>;

  // Subscriber role
  subscribe(options: SubscribeOptions): Promise<void>;
  stopSubscribe(): Promise<void>;

  // Lightweight L2 messages (< ~255 bytes)
  sendMessage(msg: Message): Promise<void>;

  // Open a P2P socket over Wi-Fi Aware (IPv6)
  startSocket(options: StartSocketOptions): Promise<SocketResult>;
  stopSocket(): Promise<void>;

  // Events
  addListener(eventName: 'stateChanged', listener: (s: AttachResult) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'serviceFound', listener: (ev: { peerId: string; serviceName: string; distanceMm?: number; serviceInfoBase64?: string; }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'serviceLost', listener: (ev: { peerId: string; serviceName: string; }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'messageReceived', listener: (msg: Message) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'socketReady', listener: (res: SocketResult) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'socketClosed', listener: () => void): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
}
