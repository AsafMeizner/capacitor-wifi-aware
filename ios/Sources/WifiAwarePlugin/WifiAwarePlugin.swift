import Foundation
import Capacitor

@objc(WifiAwarePlugin)
public class WifiAwarePlugin: CAPPlugin, CAPBridgedPlugin {
  public let identifier = "WifiAwarePlugin"
  public let jsName = "WifiAware"
  public let pluginMethods: [CAPPluginMethod] = [
    CAPPluginMethod(name: "attach", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "publish", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "stopPublish", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "subscribe", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "stopSubscribe", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "sendMessage", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "startSocket", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "stopSocket", returnType: CAPPluginReturnPromise)
  ]

  let ENABLE_WIFI_AWARE_BETA = false // set true if you added entitlement & services

  @objc func attach(_ call: CAPPluginCall) {
    #if canImport(Network) // The beta framework is separate; we keep a graceful default
    if ENABLE_WIFI_AWARE_BETA {
      // Here you would check WACapabilities and return support info
      call.resolve([
        "available": true,
        "reason": NSNull(),
      ])
      return
    }
    #endif
    call.resolve([
      "available": false,
      "reason": "iOS Wi-Fi Aware requires iOS 18+ beta, entitlement, and declared services"
    ])
  }

  @objc func publish(_ call: CAPPluginCall) {
    if !ENABLE_WIFI_AWARE_BETA {
      call.reject("Wi-Fi Aware not available on iOS")
      return
    }
    // Implement WA NetworkListener with .wifiAware when using the beta frameworks.
    call.reject("Stub: implement with WA NetworkListener once entitlement is enabled.")
  }

  @objc func stopPublish(_ call: CAPPluginCall) { call.resolve() }
  @objc func subscribe(_ call: CAPPluginCall) {
    if !ENABLE_WIFI_AWARE_BETA { call.reject("Wi-Fi Aware not available on iOS"); return }
    call.reject("Stub: implement with WA NetworkBrowser once entitlement is enabled.")
  }
  @objc func stopSubscribe(_ call: CAPPluginCall) { call.resolve() }
  @objc func sendMessage(_ call: CAPPluginCall) { call.reject("Not implemented on iOS") }
  @objc func startSocket(_ call: CAPPluginCall) { call.reject("Not implemented on iOS") }
  @objc func stopSocket(_ call: CAPPluginCall) { call.resolve() }
}
