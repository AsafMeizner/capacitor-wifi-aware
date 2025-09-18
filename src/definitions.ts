import type { PluginListenerHandle } from '@capacitor/core';

export type Role = 'publisher' | 'subscriber';
export type TransferType = 'file' | 'message';

export interface AttachResult {
  available: boolean;       // true if Wi-Fi Aware stack is available & enabled
  reason?: string;          // message if not available
  androidApiLevel?: number;
  instantCommSupported?: boolean; // Android 13+ devices that support it
  deviceName?: string;      // Local device name
  deviceId?: string;        // Unique identifier for the device
}

export interface DeviceInfo {
  peerId: string;           // Peer ID assigned by the plugin
  deviceName?: string;      // Remote device name when available
  deviceType?: string;      // Device type info (e.g. "Android", "iPhone")
  modelName?: string;       // Device model when available
  osVersion?: string;       // OS version when available
  capabilities?: string[];  // Supported capabilities
}

export interface PublishOptions {
  serviceName: string;          // e.g. "aware_files" (<=15 chars, a-z0-9-)
  serviceInfoBase64?: string;   // small payload (<=~255 bytes total)
  instantMode?: boolean;        // Android 13+ instant comm mode (30s)
  rangingEnabled?: boolean;     // Allow RTT-assisted distance
  deviceInfo?: boolean;         // Include device info in advertisement
  multicastEnabled?: boolean;   // Enable multicast transmissions
}

export interface SubscribeOptions {
  serviceName: string;
  instantMode?: boolean;
  minDistanceMm?: number;       // requires publisher rangingEnabled
  maxDistanceMm?: number;
  requestDeviceInfo?: boolean;  // Request device info when discovering peers
}

export interface Message {
  peerId: string;               // plugin-issued ID for a PeerHandle (Android) or endpoint (iOS)
  dataBase64: string;           // Message content in Base64
  multicast?: boolean;          // If true, send to all connected peers
  peerIds?: string[];           // Alternative to multicast: specify target peers
}

export interface FileTransferOptions {
  peerId: string;               // Target peer ID
  filePath?: string;            // Local file path to send (sender only)
  fileBase64?: string;          // Alternative to filePath: file content in Base64
  fileName: string;             // File name for the recipient
  mimeType?: string;            // MIME type of the file
  multicast?: boolean;          // If true, send to all connected peers
  peerIds?: string[];           // Alternative to multicast: specify target peers
}

export interface FileTransferProgress {
  peerId: string;               // Peer ID involved in transfer
  transferId: string;           // Unique ID for this transfer
  fileName: string;             // Name of the file being transferred
  bytesTransferred: number;     // Bytes transferred so far
  totalBytes: number;           // Total file size in bytes
  progress: number;             // Progress as a percentage (0-100)
  direction: 'incoming' | 'outgoing';  // Transfer direction
  status: 'in-progress' | 'completed' | 'failed' | 'cancelled';
}

export interface StartSocketOptions {
  peerId: string;               // discovered peer
  pskPassphrase: string;        // 8..63 chars; used to encrypt the NAN datapath
  asServer?: boolean;           // if true, publisher creates ServerSocket (recommended)
  multicastEnabled?: boolean;   // Enable socket for multicast operations
  maxConnections?: number;      // Maximum number of simultaneous connections (default 5)
}

export interface SocketResult {
  role: Role;
  socketId: string;             // Unique identifier for this socket connection
  localPort?: number;           // publisher server port, if server
  peerIpv6?: string;            // subscriber learns publisher address
  peerPort?: number;            // subscriber learns publisher port
  multicastEnabled?: boolean;   // Whether multicast is enabled for this socket
  connectedPeers?: string[];    // List of peer IDs connected via this socket
}

export interface WifiAwarePlugin {
  // Basic availability; must be called first
  attach(): Promise<AttachResult>;

  // Get device information
  getDeviceInfo(options: { peerId: string }): Promise<DeviceInfo>;
  
  // Publisher role
  publish(options: PublishOptions): Promise<void>;
  stopPublish(): Promise<void>;

  // Subscriber role
  subscribe(options: SubscribeOptions): Promise<void>;
  stopSubscribe(): Promise<void>;

  // Lightweight L2 messages (< ~255 bytes)
  sendMessage(msg: Message): Promise<void>;

  // File transfer operations
  sendFile(options: FileTransferOptions): Promise<string>; // Returns transferId
  cancelFileTransfer(transferId: string): Promise<void>;
  
  // Open a P2P socket over Wi-Fi Aware (IPv6)
  startSocket(options: StartSocketOptions): Promise<SocketResult>;
  stopSocket(options?: { socketId?: string }): Promise<void>; // If socketId not provided, stops all sockets

  // Events
  addListener(eventName: 'stateChanged', listener: (s: AttachResult) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'serviceFound', listener: (ev: { peerId: string; serviceName: string; distanceMm?: number; serviceInfoBase64?: string; deviceInfo?: DeviceInfo }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'serviceLost', listener: (ev: { peerId: string; serviceName: string; }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'messageReceived', listener: (msg: Message) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'fileTransferRequest', listener: (req: { peerId: string; transferId: string; fileName: string; mimeType?: string; fileSize: number }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'fileTransferProgress', listener: (progress: FileTransferProgress) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'fileTransferCompleted', listener: (result: { peerId: string; transferId: string; fileName: string; filePath?: string; fileBase64?: string }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'socketReady', listener: (res: SocketResult) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'socketClosed', listener: (data: { socketId?: string }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'peerConnected', listener: (data: { socketId: string; peerId: string; deviceInfo?: DeviceInfo }) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'peerDisconnected', listener: (data: { socketId: string; peerId: string }) => void): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
}
