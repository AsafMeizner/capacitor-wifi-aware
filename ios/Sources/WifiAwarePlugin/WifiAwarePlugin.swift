import Foundation
import Capacitor
import Network

@objc(WifiAwarePlugin)
public class WifiAwarePlugin: CAPPlugin, CAPBridgedPlugin, WifiAwareDelegate {
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
    CAPPluginMethod(name: "stopSocket", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "sendFileTransfer", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "respondToFileTransfer", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "removeAllListeners", returnType: CAPPluginReturnPromise)
  ]

  // Set this to true once you've added the entitlement and declared services
  let ENABLE_WIFI_AWARE_BETA = false
  
  private var wifiAware: WifiAware?
  
  override public func load() {
    // Initialize the WiFi Aware implementation if enabled
    if ENABLE_WIFI_AWARE_BETA {
      wifiAware = WifiAware(delegate: self)
    }
  }
  
  @objc func attach(_ call: CAPPluginCall) {
    #if canImport(Network)
    if ENABLE_WIFI_AWARE_BETA, let wifiAware = wifiAware {
      let (available, reason) = wifiAware.checkAvailability()
      notifyStateChanged(available: available, reason: reason)
      call.resolve([
        "available": available,
        "reason": reason as Any
      ])
      return
    }
    #endif
    
    let reason = "iOS Wi-Fi Aware requires iOS 18+, entitlement, and declared services"
    notifyStateChanged(available: false, reason: reason)
    call.resolve([
      "available": false,
      "reason": reason
    ])
  }

  @objc func publish(_ call: CAPPluginCall) {
    guard ENABLE_WIFI_AWARE_BETA, let wifiAware = wifiAware else {
      call.reject("Wi-Fi Aware not available on iOS")
      return
    }
    
    guard let serviceName = call.getString("serviceName") else {
      call.reject("serviceName is required")
      return
    }
    
    // Extract optional parameters
    var serviceInfo: Data? = nil
    if let serviceInfoBase64 = call.getString("serviceInfoBase64") {
      serviceInfo = Data(base64Encoded: serviceInfoBase64)
    }
    
    let instantMode = call.getBool("instantMode") ?? false
    
    // Start publishing
    wifiAware.startPublishing(serviceName: serviceName, serviceInfo: serviceInfo, instantMode: instantMode)
    call.resolve()
  }

  @objc func stopPublish(_ call: CAPPluginCall) { 
    wifiAware?.stopPublishing()
    call.resolve() 
  }
  
  @objc func subscribe(_ call: CAPPluginCall) {
    guard ENABLE_WIFI_AWARE_BETA, let wifiAware = wifiAware else {
      call.reject("Wi-Fi Aware not available on iOS")
      return
    }
    
    guard let serviceName = call.getString("serviceName") else {
      call.reject("serviceName is required")
      return
    }
    
    let instantMode = call.getBool("instantMode") ?? false
    
    // Start browsing
    wifiAware.startBrowsing(serviceName: serviceName, instantMode: instantMode)
    call.resolve()
  }
  
  @objc func stopSubscribe(_ call: CAPPluginCall) { 
    wifiAware?.stopBrowsing()
    call.resolve() 
  }
  
  @objc func sendMessage(_ call: CAPPluginCall) {
    guard ENABLE_WIFI_AWARE_BETA, let wifiAware = wifiAware else {
      call.reject("Wi-Fi Aware not available on iOS")
      return
    }
    
    guard let peerId = call.getString("peerId"), 
          let dataBase64 = call.getString("dataBase64"),
          let data = Data(base64Encoded: dataBase64) else {
      call.reject("peerId and dataBase64 are required")
      return
    }
    
    wifiAware.sendMessage(peerId: peerId, data: data)
    call.resolve()
  }
  
  @objc func startSocket(_ call: CAPPluginCall) {
    guard ENABLE_WIFI_AWARE_BETA, let wifiAware = wifiAware else {
      call.reject("Wi-Fi Aware not available on iOS")
      return
    }
    
    guard let peerId = call.getString("peerId"), 
          let pskPassphrase = call.getString("pskPassphrase") else {
      call.reject("peerId and pskPassphrase are required")
      return
    }
    
    let asServer = call.getBool("asServer") ?? false
    
    wifiAware.startSocket(peerId: peerId, pskPassphrase: pskPassphrase, asServer: asServer)
    call.resolve()
  }
  
  @objc func stopSocket(_ call: CAPPluginCall) {
    if let peerId = call.getString("peerId") {
      wifiAware?.stopSocket(peerId: peerId)
    } else {
      wifiAware?.stopAllSockets()
    }
    call.resolve()
  }
  
  @objc func sendFileTransfer(_ call: CAPPluginCall) {
    guard ENABLE_WIFI_AWARE_BETA, let wifiAware = wifiAware else {
      call.reject("Wi-Fi Aware not available on iOS")
      return
    }
    
    guard let peerId = call.getString("peerId") else {
      call.reject("peerId is required")
      return
    }
    
    let filePath = call.getString("filePath")
    let fileBase64 = call.getString("fileBase64")
    
    if (filePath == nil && fileBase64 == nil) || (filePath != nil && fileBase64 != nil) {
      call.reject("Either filePath OR fileBase64 must be provided")
      return
    }
    
    var fileName = call.getString("fileName")
    
    if fileName == nil && filePath != nil {
      // Extract filename from path
      let url = URL(fileURLWithPath: filePath!)
      fileName = url.lastPathComponent
    }
    
    if fileName == nil {
      call.reject("fileName is required when using fileBase64")
      return
    }
    
    let mimeType = call.getString("mimeType")
    
    do {
      let transferId = try wifiAware.sendFileTransfer(
        peerId: peerId,
        filePath: filePath,
        fileBase64: fileBase64,
        fileName: fileName!,
        mimeType: mimeType
      )
      
      call.resolve(["transferId": transferId])
    } catch {
      call.reject("Failed to initiate file transfer: \(error.localizedDescription)")
    }
  }
  
  @objc func respondToFileTransfer(_ call: CAPPluginCall) {
    guard ENABLE_WIFI_AWARE_BETA, let wifiAware = wifiAware else {
      call.reject("Wi-Fi Aware not available on iOS")
      return
    }
    
    guard let peerId = call.getString("peerId"),
          let transferId = call.getString("transferId") else {
      call.reject("peerId and transferId are required")
      return
    }
    
    let accept = call.getBool("accept") ?? false
    let savePath = call.getString("savePath")
    
    wifiAware.respondToFileTransfer(peerId: peerId, transferId: transferId, accept: accept, savePath: savePath)
    call.resolve()
  }
  
  @objc override func removeAllListeners(_ call: CAPPluginCall) {
    super.removeAllListeners(call)
    call.resolve()
  }
  
  // MARK: - WifiAwareDelegate Methods
  
  public func stateChanged(available: Bool, reason: String?) {
    notifyStateChanged(available: available, reason: reason)
  }
  
  public func serviceFound(peerId: String, serviceName: String, serviceInfoBase64: String?, distanceMm: Int?) {
    var data: [String: Any] = [
      "peerId": peerId,
      "serviceName": serviceName
    ]
    
    if let serviceInfoBase64 = serviceInfoBase64 {
      data["serviceInfoBase64"] = serviceInfoBase64
    }
    
    if let distanceMm = distanceMm {
      data["distanceMm"] = distanceMm
    }
    
    notifyListeners("serviceFound", data: data)
  }
  
  public func serviceLost(peerId: String, serviceName: String) {
    let data: [String: Any] = [
      "peerId": peerId,
      "serviceName": serviceName
    ]
    
    notifyListeners("serviceLost", data: data)
  }
  
  public func messageReceived(peerId: String, dataBase64: String) {
    let data: [String: Any] = [
      "peerId": peerId,
      "dataBase64": dataBase64
    ]
    
    notifyListeners("messageReceived", data: data)
  }
  
  public func socketReady(role: String, localPort: Int?, peerIpv6: String?, peerPort: Int?) {
    var data: [String: Any] = ["role": role]
    
    if let localPort = localPort {
      data["localPort"] = localPort
    }
    
    if let peerIpv6 = peerIpv6 {
      data["peerIpv6"] = peerIpv6
    }
    
    if let peerPort = peerPort {
      data["peerPort"] = peerPort
    }
    
    notifyListeners("socketReady", data: data)
  }
  
  public func socketClosed(socketId: String?) {
    var data: [String: Any] = [:]
    if let socketId = socketId {
      data["socketId"] = socketId
    }
    notifyListeners("socketClosed", data: data)
  }
  
  public func fileTransferRequest(peerId: String, transferId: String, fileName: String, mimeType: String?, fileSize: Int64) {
    var data: [String: Any] = [
      "peerId": peerId,
      "transferId": transferId,
      "fileName": fileName,
      "fileSize": fileSize
    ]
    
    if let mimeType = mimeType {
      data["mimeType"] = mimeType
    }
    
    notifyListeners("fileTransferRequest", data: data)
  }
  
  public func fileTransferProgress(peerId: String, transferId: String, fileName: String, bytesTransferred: Int64, totalBytes: Int64, direction: String, status: String) {
    let progress = totalBytes > 0 ? Int(bytesTransferred * 100 / totalBytes) : 0
    
    let data: [String: Any] = [
      "peerId": peerId,
      "transferId": transferId,
      "fileName": fileName,
      "bytesTransferred": bytesTransferred,
      "totalBytes": totalBytes,
      "progress": progress,
      "direction": direction,
      "status": status
    ]
    
    notifyListeners("fileTransferProgress", data: data)
  }
  
  public func fileTransferCompleted(peerId: String, transferId: String, fileName: String, filePath: String?, fileBase64: String?) {
    var data: [String: Any] = [
      "peerId": peerId,
      "transferId": transferId,
      "fileName": fileName
    ]
    
    if let filePath = filePath {
      data["filePath"] = filePath
    }
    
    if let fileBase64 = fileBase64 {
      data["fileBase64"] = fileBase64
    }
    
    notifyListeners("fileTransferCompleted", data: data)
  }
  
  public func peerConnected(socketId: String, peerId: String, deviceInfo: [String: Any]?) {
    var data: [String: Any] = [
      "socketId": socketId,
      "peerId": peerId
    ]
    
    if let deviceInfo = deviceInfo {
      data["deviceInfo"] = deviceInfo
    }
    
    notifyListeners("peerConnected", data: data)
  }
  
  public func peerDisconnected(socketId: String, peerId: String) {
    let data: [String: Any] = [
      "socketId": socketId,
      "peerId": peerId
    ]
    
    notifyListeners("peerDisconnected", data: data)
  }
  
  // MARK: - Helper Methods
  
  private func notifyStateChanged(available: Bool, reason: String?) {
    var data: [String: Any] = ["available": available]
    
    if let reason = reason {
      data["reason"] = reason
    }
    
    notifyListeners("stateChanged", data: data)
  }
}
