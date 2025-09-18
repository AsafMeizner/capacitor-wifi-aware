# @asaf/wifi-aware

Capacitor plugin for Wi-Fi Aware (NAN) discovery, messaging & P2P sockets. This plugin provides cross-platform support for Wi-Fi Aware functionality on Android (API 26+) and iOS (iOS 18+).

## Requirements

- **Android**: Android 8.0 (API 26) or higher with Wi-Fi Aware hardware support
- **iOS**: iOS 18+ (beta) with Wi-Fi Aware entitlement and declared services
- **Web**: Not supported (falls back to graceful error messages)

## Install

```bash
npm install @asaf/wifi-aware
npx cap sync
```

## Platform Setup

### Android
Add required permissions to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<!-- For Android 13+ -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
```

### iOS
1. Request the Wi-Fi Aware entitlement from Apple
2. Add the entitlement to your app:
```xml
<key>com.apple.developer.wifi-aware</key>
<true/>
```
3. Set the `ENABLE_WIFI_AWARE_BETA` flag to `true` in the `WifiAwarePlugin.swift` file

## Usage Example

```typescript
import { WifiAware } from '@asaf/wifi-aware';

// Check if Wi-Fi Aware is available
async function checkWifiAware() {
  const result = await WifiAware.attach();
  console.log('Wi-Fi Aware available:', result.available);
  
  if (!result.available) {
    console.log('Reason:', result.reason);
    return false;
  }
  return true;
}

// Set up event listeners
async function setupListeners() {
  // Listen for state changes
  WifiAware.addListener('stateChanged', (state) => {
    console.log('Wi-Fi Aware state changed:', state);
  });
  
  // Listen for discovered services
  WifiAware.addListener('serviceFound', (event) => {
    console.log('Service found:', event);
    // You can now communicate with this peer
  });
  
  // Listen for lost services
  WifiAware.addListener('serviceLost', (event) => {
    console.log('Service lost:', event);
  });
  
  // Listen for incoming messages
  WifiAware.addListener('messageReceived', (msg) => {
    console.log('Message received from', msg.peerId);
    console.log('Content:', atob(msg.dataBase64));
  });
  
  // Listen for socket connections
  WifiAware.addListener('socketReady', (result) => {
    console.log('Socket ready:', result);
    // Now you can create your own socket connection using the provided info
  });
}

// Start publishing a service
async function startPublishing() {
  try {
    await WifiAware.publish({
      serviceName: 'myapp_service',
      serviceInfoBase64: btoa('Hello from my app'), // Optional info
      instantMode: true,  // Use instant communication mode if supported
      rangingEnabled: true // Enable distance estimation
    });
    console.log('Publishing started');
  } catch (error) {
    console.error('Error publishing:', error);
  }
}

// Start subscribing to find services
async function startSubscribing() {
  try {
    await WifiAware.subscribe({
      serviceName: 'myapp_service',
      instantMode: true
    });
    console.log('Subscribing started');
  } catch (error) {
    console.error('Error subscribing:', error);
  }
}

// Send a message to a discovered peer
function sendMessage(peerId) {
  const message = 'Hello peer!';
  WifiAware.sendMessage({
    peerId,
    dataBase64: btoa(message)
  });
}

// Start a socket connection with a peer
async function startSocketConnection(peerId) {
  try {
    const result = await WifiAware.startSocket({
      peerId,
      pskPassphrase: 'my-secure-passphrase',
      asServer: true // Publisher typically acts as server
    });
    console.log('Socket started:', result);
  } catch (error) {
    console.error('Error starting socket:', error);
  }
}

// File Transfer Example
async function setupFileTransferListeners() {
  // Listen for file transfer requests
  WifiAware.addListener('fileTransferRequest', (request) => {
    console.log('File transfer request:', request);
    const { peerId, transferId, fileName, fileSize, mimeType } = request;
    
    // Ask user if they want to accept the file
    const userAccepted = confirm(`Accept ${fileName} (${fileSize} bytes) from peer?`);
    
    // Respond to the file transfer request
    WifiAware.respondToFileTransfer({
      peerId,
      transferId,
      accept: userAccepted,
      savePath: userAccepted ? `/path/to/downloads/${fileName}` : undefined
    });
  });
  
  // Listen for file transfer progress
  WifiAware.addListener('fileTransferProgress', (progress) => {
    console.log(`File transfer progress: ${progress.progress}%`);
    // Update UI with progress
    updateProgressBar(progress.progress);
  });
  
  // Listen for file transfer completion
  WifiAware.addListener('fileTransferCompleted', (result) => {
    console.log('File transfer completed:', result);
    const { fileName, filePath, fileBase64 } = result;
    
    if (filePath) {
      console.log(`File saved to: ${filePath}`);
    } else if (fileBase64) {
      // Handle in-memory file data
      const fileData = atob(fileBase64);
      console.log(`Received file data: ${fileData.length} bytes`);
    }
  });
  
  // Listen for peer connections
  WifiAware.addListener('peerConnected', (event) => {
    console.log('Peer connected:', event);
    const { peerId, deviceInfo } = event;
    
    if (deviceInfo) {
      console.log(`Device name: ${deviceInfo.deviceName}`);
      console.log(`Device OS: ${deviceInfo.deviceType} ${deviceInfo.osVersion}`);
    }
  });
}

// Send a file to a peer
async function sendFile(peerId, filePath) {
  try {
    const result = await WifiAware.sendFileTransfer({
      peerId,
      filePath,
      fileName: filePath.split('/').pop(),
      mimeType: 'application/octet-stream'
    });
    
    console.log('File transfer initiated with ID:', result.transferId);
  } catch (error) {
    console.error('Error sending file:', error);
  }
}

// Alternatively, send file content directly
function sendFileContent(peerId, fileName, fileContent) {
  const fileBase64 = btoa(fileContent);
  
  WifiAware.sendFileTransfer({
    peerId,
    fileBase64,
    fileName,
    mimeType: 'text/plain'
  });
}

// Clean up
function cleanup() {
  WifiAware.stopPublish();
  WifiAware.stopSubscribe();
  WifiAware.stopSocket();
  WifiAware.removeAllListeners();
}
```

## API

<docgen-index>

* [`attach()`](#attach)
* [`getDeviceInfo(...)`](#getdeviceinfo)
* [`publish(...)`](#publish)
* [`stopPublish()`](#stoppublish)
* [`subscribe(...)`](#subscribe)
* [`stopSubscribe()`](#stopsubscribe)
* [`sendMessage(...)`](#sendmessage)
* [`sendFile(...)`](#sendfile)
* [`cancelFileTransfer(...)`](#cancelfiletransfer)
* [`startSocket(...)`](#startsocket)
* [`stopSocket(...)`](#stopsocket)
* [`addListener('stateChanged', ...)`](#addlistenerstatechanged-)
* [`addListener('serviceFound', ...)`](#addlistenerservicefound-)
* [`addListener('serviceLost', ...)`](#addlistenerservicelost-)
* [`addListener('messageReceived', ...)`](#addlistenermessagereceived-)
* [`addListener('fileTransferRequest', ...)`](#addlistenerfiletransferrequest-)
* [`addListener('fileTransferProgress', ...)`](#addlistenerfiletransferprogress-)
* [`addListener('fileTransferCompleted', ...)`](#addlistenerfiletransfercompleted-)
* [`addListener('socketReady', ...)`](#addlistenersocketready-)
* [`addListener('socketClosed', ...)`](#addlistenersocketclosed-)
* [`addListener('peerConnected', ...)`](#addlistenerpeerconnected-)
* [`addListener('peerDisconnected', ...)`](#addlistenerpeerdisconnected-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### attach()

```typescript
attach() => Promise<AttachResult>
```

**Returns:** <code>Promise&lt;<a href="#attachresult">AttachResult</a>&gt;</code>

--------------------


### getDeviceInfo(...)

```typescript
getDeviceInfo(options: { peerId: string; }) => Promise<DeviceInfo>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ peerId: string; }</code> |

**Returns:** <code>Promise&lt;<a href="#deviceinfo">DeviceInfo</a>&gt;</code>

--------------------


### publish(...)

```typescript
publish(options: PublishOptions) => Promise<void>
```

| Param         | Type                                                      |
| ------------- | --------------------------------------------------------- |
| **`options`** | <code><a href="#publishoptions">PublishOptions</a></code> |

--------------------


### stopPublish()

```typescript
stopPublish() => Promise<void>
```

--------------------


### subscribe(...)

```typescript
subscribe(options: SubscribeOptions) => Promise<void>
```

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#subscribeoptions">SubscribeOptions</a></code> |

--------------------


### stopSubscribe()

```typescript
stopSubscribe() => Promise<void>
```

--------------------


### sendMessage(...)

```typescript
sendMessage(msg: Message) => Promise<void>
```

| Param     | Type                                        |
| --------- | ------------------------------------------- |
| **`msg`** | <code><a href="#message">Message</a></code> |

--------------------


### sendFile(...)

```typescript
sendFile(options: FileTransferOptions) => Promise<string>
```

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#filetransferoptions">FileTransferOptions</a></code> |

**Returns:** <code>Promise&lt;string&gt;</code>

--------------------


### cancelFileTransfer(...)

```typescript
cancelFileTransfer(transferId: string) => Promise<void>
```

| Param            | Type                |
| ---------------- | ------------------- |
| **`transferId`** | <code>string</code> |

--------------------


### startSocket(...)

```typescript
startSocket(options: StartSocketOptions) => Promise<SocketResult>
```

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code><a href="#startsocketoptions">StartSocketOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#socketresult">SocketResult</a>&gt;</code>

--------------------


### stopSocket(...)

```typescript
stopSocket(options?: { socketId?: string | undefined; } | undefined) => Promise<void>
```

| Param         | Type                                |
| ------------- | ----------------------------------- |
| **`options`** | <code>{ socketId?: string; }</code> |

--------------------


### addListener('stateChanged', ...)

```typescript
addListener(eventName: 'stateChanged', listener: (s: AttachResult) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                  |
| --------------- | --------------------------------------------------------------------- |
| **`eventName`** | <code>'stateChanged'</code>                                           |
| **`listener`**  | <code>(s: <a href="#attachresult">AttachResult</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('serviceFound', ...)

```typescript
addListener(eventName: 'serviceFound', listener: (ev: { peerId: string; serviceName: string; distanceMm?: number; serviceInfoBase64?: string; deviceInfo?: DeviceInfo; }) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                                                                                                                       |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`eventName`** | <code>'serviceFound'</code>                                                                                                                                                |
| **`listener`**  | <code>(ev: { peerId: string; serviceName: string; distanceMm?: number; serviceInfoBase64?: string; deviceInfo?: <a href="#deviceinfo">DeviceInfo</a>; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('serviceLost', ...)

```typescript
addListener(eventName: 'serviceLost', listener: (ev: { peerId: string; serviceName: string; }) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                   |
| --------------- | ---------------------------------------------------------------------- |
| **`eventName`** | <code>'serviceLost'</code>                                             |
| **`listener`**  | <code>(ev: { peerId: string; serviceName: string; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('messageReceived', ...)

```typescript
addListener(eventName: 'messageReceived', listener: (msg: Message) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                          |
| --------------- | ------------------------------------------------------------- |
| **`eventName`** | <code>'messageReceived'</code>                                |
| **`listener`**  | <code>(msg: <a href="#message">Message</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('fileTransferRequest', ...)

```typescript
addListener(eventName: 'fileTransferRequest', listener: (req: { peerId: string; transferId: string; fileName: string; mimeType?: string; fileSize: number; }) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                                                                          |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| **`eventName`** | <code>'fileTransferRequest'</code>                                                                                            |
| **`listener`**  | <code>(req: { peerId: string; transferId: string; fileName: string; mimeType?: string; fileSize: number; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('fileTransferProgress', ...)

```typescript
addListener(eventName: 'fileTransferProgress', listener: (progress: FileTransferProgress) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                                         |
| --------------- | -------------------------------------------------------------------------------------------- |
| **`eventName`** | <code>'fileTransferProgress'</code>                                                          |
| **`listener`**  | <code>(progress: <a href="#filetransferprogress">FileTransferProgress</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('fileTransferCompleted', ...)

```typescript
addListener(eventName: 'fileTransferCompleted', listener: (result: { peerId: string; transferId: string; fileName: string; filePath?: string; fileBase64?: string; }) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                                                                                |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| **`eventName`** | <code>'fileTransferCompleted'</code>                                                                                                |
| **`listener`**  | <code>(result: { peerId: string; transferId: string; fileName: string; filePath?: string; fileBase64?: string; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('socketReady', ...)

```typescript
addListener(eventName: 'socketReady', listener: (res: SocketResult) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                    |
| --------------- | ----------------------------------------------------------------------- |
| **`eventName`** | <code>'socketReady'</code>                                              |
| **`listener`**  | <code>(res: <a href="#socketresult">SocketResult</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('socketClosed', ...)

```typescript
addListener(eventName: 'socketClosed', listener: (data: { socketId?: string; }) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                   |
| --------------- | ------------------------------------------------------ |
| **`eventName`** | <code>'socketClosed'</code>                            |
| **`listener`**  | <code>(data: { socketId?: string; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('peerConnected', ...)

```typescript
addListener(eventName: 'peerConnected', listener: (data: { socketId: string; peerId: string; deviceInfo?: DeviceInfo; }) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                                                                     |
| --------------- | ------------------------------------------------------------------------------------------------------------------------ |
| **`eventName`** | <code>'peerConnected'</code>                                                                                             |
| **`listener`**  | <code>(data: { socketId: string; peerId: string; deviceInfo?: <a href="#deviceinfo">DeviceInfo</a>; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('peerDisconnected', ...)

```typescript
addListener(eventName: 'peerDisconnected', listener: (data: { socketId: string; peerId: string; }) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                  |
| --------------- | --------------------------------------------------------------------- |
| **`eventName`** | <code>'peerDisconnected'</code>                                       |
| **`listener`**  | <code>(data: { socketId: string; peerId: string; }) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

--------------------


### Interfaces


#### AttachResult

| Prop                       | Type                 |
| -------------------------- | -------------------- |
| **`available`**            | <code>boolean</code> |
| **`reason`**               | <code>string</code>  |
| **`androidApiLevel`**      | <code>number</code>  |
| **`instantCommSupported`** | <code>boolean</code> |
| **`deviceName`**           | <code>string</code>  |
| **`deviceId`**             | <code>string</code>  |


#### DeviceInfo

| Prop               | Type                  |
| ------------------ | --------------------- |
| **`peerId`**       | <code>string</code>   |
| **`deviceName`**   | <code>string</code>   |
| **`deviceType`**   | <code>string</code>   |
| **`modelName`**    | <code>string</code>   |
| **`osVersion`**    | <code>string</code>   |
| **`capabilities`** | <code>string[]</code> |


#### PublishOptions

| Prop                    | Type                 |
| ----------------------- | -------------------- |
| **`serviceName`**       | <code>string</code>  |
| **`serviceInfoBase64`** | <code>string</code>  |
| **`instantMode`**       | <code>boolean</code> |
| **`rangingEnabled`**    | <code>boolean</code> |
| **`deviceInfo`**        | <code>boolean</code> |
| **`multicastEnabled`**  | <code>boolean</code> |


#### SubscribeOptions

| Prop                    | Type                 |
| ----------------------- | -------------------- |
| **`serviceName`**       | <code>string</code>  |
| **`instantMode`**       | <code>boolean</code> |
| **`minDistanceMm`**     | <code>number</code>  |
| **`maxDistanceMm`**     | <code>number</code>  |
| **`requestDeviceInfo`** | <code>boolean</code> |


#### Message

| Prop             | Type                  |
| ---------------- | --------------------- |
| **`peerId`**     | <code>string</code>   |
| **`dataBase64`** | <code>string</code>   |
| **`multicast`**  | <code>boolean</code>  |
| **`peerIds`**    | <code>string[]</code> |


#### FileTransferOptions

| Prop             | Type                  |
| ---------------- | --------------------- |
| **`peerId`**     | <code>string</code>   |
| **`filePath`**   | <code>string</code>   |
| **`fileBase64`** | <code>string</code>   |
| **`fileName`**   | <code>string</code>   |
| **`mimeType`**   | <code>string</code>   |
| **`multicast`**  | <code>boolean</code>  |
| **`peerIds`**    | <code>string[]</code> |


#### SocketResult

| Prop                   | Type                                  |
| ---------------------- | ------------------------------------- |
| **`role`**             | <code><a href="#role">Role</a></code> |
| **`socketId`**         | <code>string</code>                   |
| **`localPort`**        | <code>number</code>                   |
| **`peerIpv6`**         | <code>string</code>                   |
| **`peerPort`**         | <code>number</code>                   |
| **`multicastEnabled`** | <code>boolean</code>                  |
| **`connectedPeers`**   | <code>string[]</code>                 |


#### StartSocketOptions

| Prop                   | Type                 |
| ---------------------- | -------------------- |
| **`peerId`**           | <code>string</code>  |
| **`pskPassphrase`**    | <code>string</code>  |
| **`asServer`**         | <code>boolean</code> |
| **`multicastEnabled`** | <code>boolean</code> |
| **`maxConnections`**   | <code>number</code>  |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### FileTransferProgress

| Prop                   | Type                                                                 |
| ---------------------- | -------------------------------------------------------------------- |
| **`peerId`**           | <code>string</code>                                                  |
| **`transferId`**       | <code>string</code>                                                  |
| **`fileName`**         | <code>string</code>                                                  |
| **`bytesTransferred`** | <code>number</code>                                                  |
| **`totalBytes`**       | <code>number</code>                                                  |
| **`progress`**         | <code>number</code>                                                  |
| **`direction`**        | <code>'incoming' \| 'outgoing'</code>                                |
| **`status`**           | <code>'in-progress' \| 'completed' \| 'failed' \| 'cancelled'</code> |


### Type Aliases


#### Role

<code>'publisher' | 'subscriber'</code>

</docgen-api>
