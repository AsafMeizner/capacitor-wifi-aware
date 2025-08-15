# @asaf/wifi-aware

Capacitor plugin for Wi-Fi Aware (NAN) discovery, messaging & P2P sockets

## Install

```bash
npm install @asaf/wifi-aware
npx cap sync
```

## API

<docgen-index>

* [`attach()`](#attach)
* [`publish(...)`](#publish)
* [`stopPublish()`](#stoppublish)
* [`subscribe(...)`](#subscribe)
* [`stopSubscribe()`](#stopsubscribe)
* [`sendMessage(...)`](#sendmessage)
* [`startSocket(...)`](#startsocket)
* [`stopSocket()`](#stopsocket)
* [`addListener('stateChanged', ...)`](#addlistenerstatechanged-)
* [`addListener('serviceFound', ...)`](#addlistenerservicefound-)
* [`addListener('serviceLost', ...)`](#addlistenerservicelost-)
* [`addListener('messageReceived', ...)`](#addlistenermessagereceived-)
* [`addListener('socketReady', ...)`](#addlistenersocketready-)
* [`addListener('socketClosed', ...)`](#addlistenersocketclosed-)
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


### startSocket(...)

```typescript
startSocket(options: StartSocketOptions) => Promise<SocketResult>
```

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code><a href="#startsocketoptions">StartSocketOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#socketresult">SocketResult</a>&gt;</code>

--------------------


### stopSocket()

```typescript
stopSocket() => Promise<void>
```

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
addListener(eventName: 'serviceFound', listener: (ev: { peerId: string; serviceName: string; distanceMm?: number; serviceInfoBase64?: string; }) => void) => Promise<PluginListenerHandle>
```

| Param           | Type                                                                                                                    |
| --------------- | ----------------------------------------------------------------------------------------------------------------------- |
| **`eventName`** | <code>'serviceFound'</code>                                                                                             |
| **`listener`**  | <code>(ev: { peerId: string; serviceName: string; distanceMm?: number; serviceInfoBase64?: string; }) =&gt; void</code> |

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
addListener(eventName: 'socketClosed', listener: () => void) => Promise<PluginListenerHandle>
```

| Param           | Type                        |
| --------------- | --------------------------- |
| **`eventName`** | <code>'socketClosed'</code> |
| **`listener`**  | <code>() =&gt; void</code>  |

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


#### PublishOptions

| Prop                    | Type                 |
| ----------------------- | -------------------- |
| **`serviceName`**       | <code>string</code>  |
| **`serviceInfoBase64`** | <code>string</code>  |
| **`instantMode`**       | <code>boolean</code> |
| **`rangingEnabled`**    | <code>boolean</code> |


#### SubscribeOptions

| Prop                | Type                 |
| ------------------- | -------------------- |
| **`serviceName`**   | <code>string</code>  |
| **`instantMode`**   | <code>boolean</code> |
| **`minDistanceMm`** | <code>number</code>  |
| **`maxDistanceMm`** | <code>number</code>  |


#### Message

| Prop             | Type                |
| ---------------- | ------------------- |
| **`peerId`**     | <code>string</code> |
| **`dataBase64`** | <code>string</code> |


#### SocketResult

| Prop            | Type                                  |
| --------------- | ------------------------------------- |
| **`role`**      | <code><a href="#role">Role</a></code> |
| **`localPort`** | <code>number</code>                   |
| **`peerIpv6`**  | <code>string</code>                   |
| **`peerPort`**  | <code>number</code>                   |


#### StartSocketOptions

| Prop                | Type                 |
| ------------------- | -------------------- |
| **`peerId`**        | <code>string</code>  |
| **`pskPassphrase`** | <code>string</code>  |
| **`asServer`**      | <code>boolean</code> |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


### Type Aliases


#### Role

<code>'publisher' | 'subscriber'</code>

</docgen-api>
