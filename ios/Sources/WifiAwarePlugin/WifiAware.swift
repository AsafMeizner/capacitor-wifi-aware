import Foundation

@objc public class WifiAware: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
