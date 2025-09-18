import Foundation
import Network
import UIKit

public protocol WifiAwareDelegate: AnyObject {
    func stateChanged(available: Bool, reason: String?)
    func serviceFound(peerId: String, serviceName: String, serviceInfoBase64: String?, distanceMm: Int?)
    func serviceLost(peerId: String, serviceName: String)
    func messageReceived(peerId: String, dataBase64: String)
    func socketReady(role: String, localPort: Int?, peerIpv6: String?, peerPort: Int?)
    func socketClosed(socketId: String?)
    func fileTransferRequest(peerId: String, transferId: String, fileName: String, mimeType: String?, fileSize: Int64)
    func fileTransferProgress(peerId: String, transferId: String, fileName: String, bytesTransferred: Int64, totalBytes: Int64, direction: String, status: String)
    func fileTransferCompleted(peerId: String, transferId: String, fileName: String, filePath: String?, fileBase64: String?)
    func peerConnected(socketId: String, peerId: String, deviceInfo: [String: Any]?)
    func peerDisconnected(socketId: String, peerId: String)
}

@objc public class WifiAware: NSObject {
    private var listener: WANetworkListener?
    private var browser: WANetworkBrowser?
    private var connections: [String: WANetworkConnection] = [:]
    private var peers: [String: WANetworkEndpoint] = [:]
    private var currentServiceName: String?
    private weak var delegate: WifiAwareDelegate?
    
    // File transfer properties
    private var fileTransfers: [String: FileTransfer] = [:]
    private var fileResponses: [String: (Bool, URL?)] = [:] // transferId -> (accept, savePath)
    
    public init(delegate: WifiAwareDelegate) {
        self.delegate = delegate
    }
    
    public func checkAvailability() -> (Bool, String?) {
        // Check if Wi-Fi Aware is supported on this device
        guard WACapabilities.current.isAvailable else {
            return (false, "Wi-Fi Aware is not available on this device")
        }
        
        // Check for the appropriate entitlement
        guard WACapabilities.current.isEntitlementGranted else {
            return (false, "Wi-Fi Aware entitlement is not granted")
        }
        
        return (true, nil)
    }
    
    public func startPublishing(serviceName: String, serviceInfo: Data?, instantMode: Bool) {
        stopPublishing()
        
        // Create a listener object
        let listener = WANetworkListener(parameters: WAParameters())
        listener.delegate = self
        
        // Set service information if provided
        if let serviceInfo = serviceInfo {
            listener.serviceInfo = serviceInfo
        }
        
        // Configure instant communication mode if available and requested
        if instantMode {
            listener.instantCommunicationMode = .enabled
        }
        
        // Start listening for connections
        try? listener.startListening(forServiceType: serviceName)
        self.listener = listener
        self.currentServiceName = serviceName
    }
    
    public func stopPublishing() {
        listener?.cancel()
        listener = nil
    }
    
    public func startBrowsing(serviceName: String, instantMode: Bool) {
        stopBrowsing()
        
        // Create a browser object
        let browser = WANetworkBrowser(parameters: WAParameters())
        browser.delegate = self
        
        // Configure instant communication mode if available and requested
        if instantMode {
            browser.instantCommunicationMode = .enabled
        }
        
        // Start browsing for services
        try? browser.startBrowsing(forServiceType: serviceName)
        self.browser = browser
        self.currentServiceName = serviceName
    }
    
    public func stopBrowsing() {
        browser?.cancel()
        browser = nil
    }
    
    public func sendMessage(peerId: String, data: Data) {
        guard let endpoint = peers[peerId] else { return }
        
        // Use the appropriate session for sending messages
        if let listener = listener {
            listener.sendMessage(data, to: endpoint, completionHandler: { error in
                if let error = error {
                    print("Error sending message: \(error)")
                }
            })
        } else if let browser = browser {
            browser.sendMessage(data, to: endpoint, completionHandler: { error in
                if let error = error {
                    print("Error sending message: \(error)")
                }
            })
        }
    }
    
    public func startSocket(peerId: String, pskPassphrase: String, asServer: Bool) {
        guard let endpoint = peers[peerId] else { return }
        
        // Create connection parameters with security options
        let parameters = WAParameters()
        parameters.passcode = pskPassphrase
        
        // Create and establish connection
        let connection = WANetworkConnection(to: endpoint, using: parameters)
        connection.delegate = self
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                if asServer {
                    self.delegate?.socketReady(role: "publisher", localPort: connection.localEndpoint?.port, peerIpv6: nil, peerPort: nil)
                } else {
                    self.delegate?.socketReady(role: "subscriber", localPort: nil, 
                                             peerIpv6: connection.remoteEndpoint?.hostname, 
                                             peerPort: connection.remoteEndpoint?.port)
                }
            case .failed, .cancelled:
                self.delegate?.socketClosed()
                self.connections.removeValue(forKey: peerId)
            default:
                break
            }
        }
        
        // Start the connection
        connection.start(queue: .main)
        connections[peerId] = connection
    }
    
    public func stopSocket(peerId: String) {
        let socketId = peerId
        connections[peerId]?.cancel()
        connections.removeValue(forKey: peerId)
        delegate?.socketClosed(socketId: socketId)
    }
    
    public func stopAllSockets() {
        for (peerId, connection) in connections {
            connection.cancel()
            delegate?.socketClosed(socketId: peerId)
        }
        connections.removeAll()
    }
    
    private func generatePeerId(for endpoint: WANetworkEndpoint) -> String {
        // Check if we already have an ID for this endpoint
        for (id, existingEndpoint) in peers where existingEndpoint == endpoint {
            return id
        }
        
        // Otherwise generate a new UUID
        let newId = UUID().uuidString
        peers[newId] = endpoint
        return newId
    }
}

// MARK: - WANetworkListenerDelegate
extension WifiAware: WANetworkListenerDelegate {
    public func listener(_ listener: WANetworkListener, didReceiveMessage content: Data, from endpoint: WANetworkEndpoint) {
        let peerId = generatePeerId(for: endpoint)
        let base64Data = content.base64EncodedString()
        delegate?.messageReceived(peerId: peerId, dataBase64: base64Data)
    }
    
    public func listener(_ listener: WANetworkListener, didFindEndpoint endpoint: WANetworkEndpoint, withServiceInfo serviceInfo: Data?) {
        let peerId = generatePeerId(for: endpoint)
        let serviceInfoBase64 = serviceInfo?.base64EncodedString()
        
        delegate?.serviceFound(peerId: peerId, 
                             serviceName: currentServiceName ?? "", 
                             serviceInfoBase64: serviceInfoBase64, 
                             distanceMm: nil)
    }
    
    public func listener(_ listener: WANetworkListener, didLoseEndpoint endpoint: WANetworkEndpoint) {
        // Find the peerId associated with this endpoint
        guard let peerId = peers.first(where: { $0.value == endpoint })?.key else { return }
        
        delegate?.serviceLost(peerId: peerId, serviceName: currentServiceName ?? "")
        peers.removeValue(forKey: peerId)
    }
}

// MARK: - WANetworkBrowserDelegate
extension WifiAware: WANetworkBrowserDelegate {
    public func browser(_ browser: WANetworkBrowser, didReceiveMessage content: Data, from endpoint: WANetworkEndpoint) {
        let peerId = generatePeerId(for: endpoint)
        let base64Data = content.base64EncodedString()
        delegate?.messageReceived(peerId: peerId, dataBase64: base64Data)
    }
    
    public func browser(_ browser: WANetworkBrowser, didFindEndpoint endpoint: WANetworkEndpoint, withServiceInfo serviceInfo: Data?) {
        let peerId = generatePeerId(for: endpoint)
        let serviceInfoBase64 = serviceInfo?.base64EncodedString()
        
        delegate?.serviceFound(peerId: peerId, 
                             serviceName: currentServiceName ?? "", 
                             serviceInfoBase64: serviceInfoBase64, 
                             distanceMm: nil)
    }
    
    public func browser(_ browser: WANetworkBrowser, didLoseEndpoint endpoint: WANetworkEndpoint) {
        // Find the peerId associated with this endpoint
        guard let peerId = peers.first(where: { $0.value == endpoint })?.key else { return }
        
        delegate?.serviceLost(peerId: peerId, serviceName: currentServiceName ?? "")
        peers.removeValue(forKey: peerId)
    }
}

// MARK: - WANetworkConnectionDelegate
// MARK: - File Transfer
class FileTransfer {
    enum Direction {
        case sending
        case receiving
    }
    
    enum Status {
        case pending
        case inProgress
        case completed
        case failed
        case rejected
        
        var description: String {
            switch self {
            case .pending: return "pending"
            case .inProgress: return "in-progress"
            case .completed: return "completed"
            case .failed: return "failed"
            case .rejected: return "rejected"
            }
        }
    }
    
    let transferId: String
    let peerId: String
    let fileName: String
    let mimeType: String?
    let fileSize: Int64
    let direction: Direction
    var status: Status = .pending
    var filePath: URL?
    var fileData: Data?
    var bytesTransferred: Int64 = 0
    var totalBytes: Int64
    var connection: WANetworkConnection?
    
    init(transferId: String, peerId: String, fileName: String, mimeType: String?, fileSize: Int64, direction: Direction, filePath: URL? = nil, fileData: Data? = nil) {
        self.transferId = transferId
        self.peerId = peerId
        self.fileName = fileName
        self.mimeType = mimeType
        self.fileSize = fileSize
        self.direction = direction
        self.totalBytes = fileSize
        self.filePath = filePath
        self.fileData = fileData
    }
}

extension WifiAware {
    public func sendFileTransfer(peerId: String, filePath: String?, fileBase64: String?, fileName: String, mimeType: String?) -> String {
        guard let endpoint = peers[peerId] else { 
            throw NSError(domain: "com.asaf.wifiaware", code: 1, userInfo: [NSLocalizedDescriptionKey: "Peer not found"])
        }
        
        // Generate a unique transfer ID
        let transferId = UUID().uuidString
        
        // Prepare file data
        var fileData: Data? = nil
        var fileUrl: URL? = nil
        var fileSize: Int64 = 0
        
        if let filePath = filePath {
            fileUrl = URL(fileURLWithPath: filePath)
            do {
                fileData = try Data(contentsOf: fileUrl!)
                let attributes = try FileManager.default.attributesOfItem(atPath: filePath)
                if let size = attributes[FileAttributeKey.size] as? NSNumber {
                    fileSize = size.int64Value
                }
            } catch {
                throw NSError(domain: "com.asaf.wifiaware", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to read file: \(error)"])
            }
        } else if let fileBase64 = fileBase64, let data = Data(base64Encoded: fileBase64) {
            fileData = data
            fileSize = Int64(data.count)
        } else {
            throw NSError(domain: "com.asaf.wifiaware", code: 3, userInfo: [NSLocalizedDescriptionKey: "No valid file data provided"])
        }
        
        guard let data = fileData else {
            throw NSError(domain: "com.asaf.wifiaware", code: 4, userInfo: [NSLocalizedDescriptionKey: "Failed to prepare file data"])
        }
        
        // Create file transfer object
        let transfer = FileTransfer(
            transferId: transferId,
            peerId: peerId,
            fileName: fileName,
            mimeType: mimeType,
            fileSize: fileSize,
            direction: .sending,
            filePath: fileUrl,
            fileData: data
        )
        
        fileTransfers[transferId] = transfer
        
        // Create connection parameters
        let parameters = WAParameters()
        parameters.serviceInfo = createFileTransferRequest(transfer: transfer)
        
        // Create connection for file transfer
        let connection = WANetworkConnection(to: endpoint, using: parameters)
        connection.delegate = self
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                // Send the file when connection is ready
                self?.startSendingFile(transfer: transfer, connection: connection)
            case .failed, .cancelled:
                transfer.status = .failed
                self?.delegate?.fileTransferProgress(
                    peerId: transfer.peerId,
                    transferId: transfer.transferId,
                    fileName: transfer.fileName,
                    bytesTransferred: transfer.bytesTransferred,
                    totalBytes: transfer.totalBytes,
                    direction: "send",
                    status: "failed"
                )
                self?.fileTransfers.removeValue(forKey: transferId)
            default:
                break
            }
        }
        
        // Start the connection
        connection.start(queue: .main)
        transfer.connection = connection
        
        return transferId
    }
    
    public func respondToFileTransfer(peerId: String, transferId: String, accept: Bool, savePath: String?) {
        // Store the response to be used when the file transfer connection is established
        var saveUrl: URL? = nil
        if accept, let savePath = savePath {
            saveUrl = URL(fileURLWithPath: savePath)
        }
        
        fileResponses[transferId] = (accept, saveUrl)
        
        // Find the transfer if it already exists
        if let transfer = fileTransfers[transferId] {
            if !accept {
                // Reject the transfer
                transfer.status = .rejected
                delegate?.fileTransferProgress(
                    peerId: transfer.peerId,
                    transferId: transfer.transferId,
                    fileName: transfer.fileName,
                    bytesTransferred: 0,
                    totalBytes: transfer.totalBytes,
                    direction: "receive",
                    status: "rejected"
                )
                transfer.connection?.cancel()
                fileTransfers.removeValue(forKey: transferId)
            } else if let connection = transfer.connection, connection.state == .ready {
                // Connection already established, start receiving
                startReceivingFile(transfer: transfer, savePath: saveUrl)
            }
        }
    }
    
    private func createFileTransferRequest(transfer: FileTransfer) -> Data {
        // Create a dictionary with file transfer information
        let info: [String: Any] = [
            "type": "file-transfer-request",
            "transferId": transfer.transferId,
            "fileName": transfer.fileName,
            "mimeType": transfer.mimeType ?? "",
            "fileSize": transfer.fileSize,
            "deviceInfo": getDeviceInfo()
        ]
        
        // Convert to JSON data
        return try! JSONSerialization.data(withJSONObject: info)
    }
    
    private func parseFileTransferRequest(data: Data) -> (String, String, String, String?, Int64, [String: Any])? {
        do {
            guard let info = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let type = info["type"] as? String,
                  type == "file-transfer-request",
                  let transferId = info["transferId"] as? String,
                  let fileName = info["fileName"] as? String,
                  let fileSize = info["fileSize"] as? Int64,
                  let deviceInfo = info["deviceInfo"] as? [String: Any] else {
                return nil
            }
            
            let mimeType = info["mimeType"] as? String
            
            return (type, transferId, fileName, mimeType, fileSize, deviceInfo)
        } catch {
            print("Failed to parse file transfer request: \(error)")
            return nil
        }
    }
    
    private func startSendingFile(transfer: FileTransfer, connection: WANetworkConnection) {
        guard let fileData = transfer.fileData else { return }
        
        transfer.status = .inProgress
        
        // Update progress before sending
        delegate?.fileTransferProgress(
            peerId: transfer.peerId,
            transferId: transfer.transferId,
            fileName: transfer.fileName,
            bytesTransferred: 0,
            totalBytes: transfer.totalBytes,
            direction: "send",
            status: "in-progress"
        )
        
        // Send the file in chunks
        let chunkSize = 65536 // 64 KB chunks
        var offset = 0
        
        func sendNextChunk() {
            let remainingBytes = fileData.count - offset
            guard remainingBytes > 0 else {
                // File transfer complete
                transfer.status = .completed
                transfer.bytesTransferred = Int64(fileData.count)
                
                delegate?.fileTransferProgress(
                    peerId: transfer.peerId,
                    transferId: transfer.transferId,
                    fileName: transfer.fileName,
                    bytesTransferred: transfer.bytesTransferred,
                    totalBytes: transfer.totalBytes,
                    direction: "send",
                    status: "completed"
                )
                
                delegate?.fileTransferCompleted(
                    peerId: transfer.peerId,
                    transferId: transfer.transferId,
                    fileName: transfer.fileName,
                    filePath: transfer.filePath?.path,
                    fileBase64: nil
                )
                
                return
            }
            
            let chunkLength = min(chunkSize, remainingBytes)
            let chunk = fileData.subdata(in: offset..<(offset + chunkLength))
            
            connection.send(content: chunk, completion: .contentProcessed { error in
                if let error = error {
                    print("Error sending chunk: \(error)")
                    transfer.status = .failed
                    
                    self.delegate?.fileTransferProgress(
                        peerId: transfer.peerId,
                        transferId: transfer.transferId,
                        fileName: transfer.fileName,
                        bytesTransferred: Int64(offset),
                        totalBytes: transfer.totalBytes,
                        direction: "send",
                        status: "failed"
                    )
                    
                    return
                }
                
                // Update progress
                offset += chunkLength
                transfer.bytesTransferred = Int64(offset)
                
                self.delegate?.fileTransferProgress(
                    peerId: transfer.peerId,
                    transferId: transfer.transferId,
                    fileName: transfer.fileName,
                    bytesTransferred: Int64(offset),
                    totalBytes: transfer.totalBytes,
                    direction: "send",
                    status: "in-progress"
                )
                
                // Continue with the next chunk
                sendNextChunk()
            })
        }
        
        // Start the chunking process
        sendNextChunk()
    }
    
    private func startReceivingFile(transfer: FileTransfer, savePath: URL?) {
        transfer.status = .inProgress
        transfer.filePath = savePath
        
        // Create a file to write to if savePath is provided
        if let savePath = savePath {
            FileManager.default.createFile(atPath: savePath.path, contents: nil)
            
            // Update file handle in the transfer object
            do {
                let fileHandle = try FileHandle(forWritingTo: savePath)
                transfer.fileData = Data()
                // Store fileHandle in a way that can be accessed later
                objc_setAssociatedObject(transfer, "fileHandle", fileHandle, .OBJC_ASSOCIATION_RETAIN)
            } catch {
                print("Failed to create file handle: \(error)")
                transfer.status = .failed
                
                delegate?.fileTransferProgress(
                    peerId: transfer.peerId,
                    transferId: transfer.transferId,
                    fileName: transfer.fileName,
                    bytesTransferred: 0,
                    totalBytes: transfer.totalBytes,
                    direction: "receive",
                    status: "failed"
                )
            }
        } else {
            // If no save path, collect data in memory
            transfer.fileData = Data()
        }
        
        // Progress reporting is handled in the connection delegate when data is received
    }
    
    private func getDeviceInfo() -> [String: Any] {
        let device = UIDevice.current
        var info: [String: Any] = [
            "model": device.model,
            "name": device.name,
            "systemName": device.systemName,
            "systemVersion": device.systemVersion
        ]
        
        // Add more device information as needed
        if let bundleIdentifier = Bundle.main.bundleIdentifier {
            info["bundleId"] = bundleIdentifier
        }
        
        if let appName = Bundle.main.infoDictionary?["CFBundleName"] as? String {
            info["appName"] = appName
        }
        
        if let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String {
            info["appVersion"] = appVersion
        }
        
        return info
    }
}

extension WifiAware: WANetworkConnectionDelegate {
    public func connection(_ connection: WANetworkConnection, didReceive content: Data) {
        // Check if this is a file transfer connection
        if let transferId = findTransferId(for: connection) {
            handleFileTransferData(transferId: transferId, data: content)
        } else {
            // Check if this is a file transfer request
            if let (_, transferId, fileName, mimeType, fileSize, deviceInfo) = parseFileTransferRequest(data: content) {
                // Find associated peer
                guard let peerId = peers.first(where: { $0.value == connection.endpoint })?.key else { 
                    return
                }
                
                // Create a new file transfer
                let transfer = FileTransfer(
                    transferId: transferId,
                    peerId: peerId,
                    fileName: fileName,
                    mimeType: mimeType,
                    fileSize: fileSize,
                    direction: .receiving
                )
                
                fileTransfers[transferId] = transfer
                transfer.connection = connection
                
                // Notify delegate about the file transfer request
                delegate?.fileTransferRequest(
                    peerId: peerId,
                    transferId: transferId,
                    fileName: fileName,
                    mimeType: mimeType,
                    fileSize: fileSize
                )
                
                // Also notify about peer connection with device info
                delegate?.peerConnected(
                    socketId: transferId,
                    peerId: peerId,
                    deviceInfo: deviceInfo
                )
                
                // Check if we already have a response for this transfer
                if let (accept, savePath) = fileResponses[transferId] {
                    if accept {
                        startReceivingFile(transfer: transfer, savePath: savePath)
                    } else {
                        transfer.status = .rejected
                        delegate?.fileTransferProgress(
                            peerId: transfer.peerId,
                            transferId: transfer.transferId,
                            fileName: transfer.fileName,
                            bytesTransferred: 0,
                            totalBytes: transfer.totalBytes,
                            direction: "receive",
                            status: "rejected"
                        )
                        connection.cancel()
                        fileTransfers.removeValue(forKey: transferId)
                    }
                    fileResponses.removeValue(forKey: transferId)
                }
            } else {
                // Handle normal data
                guard let peerId = peers.first(where: { $0.value == connection.endpoint })?.key else { 
                    return
                }
                
                let base64Data = content.base64EncodedString()
                delegate?.messageReceived(peerId: peerId, dataBase64: base64Data)
            }
        }
    }
    
    private func findTransferId(for connection: WANetworkConnection) -> String? {
        return fileTransfers.first { $0.value.connection === connection }?.key
    }
    
    private func handleFileTransferData(transferId: String, data: Data) {
        guard let transfer = fileTransfers[transferId], transfer.status == .inProgress else { return }
        
        // Append data to the file or memory buffer
        if let fileHandle = objc_getAssociatedObject(transfer, "fileHandle") as? FileHandle {
            fileHandle.seekToEndOfFile()
            fileHandle.write(data)
        } else if transfer.fileData != nil {
            transfer.fileData?.append(data)
        } else {
            transfer.fileData = data
        }
        
        // Update progress
        transfer.bytesTransferred += Int64(data.count)
        
        // Notify about progress
        delegate?.fileTransferProgress(
            peerId: transfer.peerId,
            transferId: transfer.transferId,
            fileName: transfer.fileName,
            bytesTransferred: transfer.bytesTransferred,
            totalBytes: transfer.totalBytes,
            direction: "receive",
            status: "in-progress"
        )
        
        // Check if transfer is complete
        if transfer.bytesTransferred >= transfer.totalBytes {
            transfer.status = .completed
            
            // Close file handle if it exists
            if let fileHandle = objc_getAssociatedObject(transfer, "fileHandle") as? FileHandle {
                try? fileHandle.close()
            }
            
            // Notify about completion
            let fileBase64 = transfer.filePath == nil ? transfer.fileData?.base64EncodedString() : nil
            
            delegate?.fileTransferProgress(
                peerId: transfer.peerId,
                transferId: transfer.transferId,
                fileName: transfer.fileName,
                bytesTransferred: transfer.bytesTransferred,
                totalBytes: transfer.totalBytes,
                direction: "receive",
                status: "completed"
            )
            
            delegate?.fileTransferCompleted(
                peerId: transfer.peerId,
                transferId: transfer.transferId,
                fileName: transfer.fileName,
                filePath: transfer.filePath?.path,
                fileBase64: fileBase64
            )
            
            // Clean up
            fileTransfers.removeValue(forKey: transferId)
        }
    }
}
