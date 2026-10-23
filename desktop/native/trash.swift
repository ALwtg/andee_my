import Foundation
var failed = false
for p in CommandLine.arguments.dropFirst() {
    do { try FileManager.default.trashItem(at: URL(fileURLWithPath: p), resultingItemURL: nil) }
    catch { FileHandle.standardError.write("trash: \(p): \(error.localizedDescription)\n".data(using: .utf8)!); failed = true }
}
exit(failed ? 1 : 0)
