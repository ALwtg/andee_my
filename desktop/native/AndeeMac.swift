// Andee's native bridge on macOS: the things a JVM cannot do cleanly, as C
// functions the Kotlin side calls through JNA. Every function returns plain
// values or a malloc'd UTF-8 JSON string the caller frees with andee_free.
//
// Built with the Command Line Tools' swiftc (no Xcode):
//   swiftc -O -target arm64-apple-macos14 -emit-library AndeeMac.swift -o libandeemac.dylib
// macOS 14 is the floor: ScreenCaptureKit's one-shot screenshot arrived there.

import AppKit
import ApplicationServices
import CoreGraphics
import Foundation
import ScreenCaptureKit

// MARK: - helpers

private func cstr(_ s: String) -> UnsafeMutablePointer<CChar> { strdup(s) }

private func json(_ o: Any) -> UnsafeMutablePointer<CChar> {
    let data = (try? JSONSerialization.data(withJSONObject: o, options: [])) ?? Data("{}".utf8)
    return cstr(String(data: data, encoding: .utf8) ?? "{}")
}

@_cdecl("andee_free")
public func andee_free(_ p: UnsafeMutablePointer<CChar>?) { free(p) }

// MARK: - permissions

@_cdecl("andee_ax_trusted")
public func andee_ax_trusted(_ prompt: Int32) -> Int32 {
    let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: prompt != 0] as CFDictionary
    return AXIsProcessTrustedWithOptions(opts) ? 1 : 0
}

@_cdecl("andee_screen_capture_allowed")
public func andee_screen_capture_allowed(_ request: Int32) -> Int32 {
    if CGPreflightScreenCaptureAccess() { return 1 }
    if request != 0 { return CGRequestScreenCaptureAccess() ? 1 : 0 }
    return 0
}

// MARK: - display / apps

@_cdecl("andee_main_display")
public func andee_main_display() -> UnsafeMutablePointer<CChar> {
    let b = CGDisplayBounds(CGMainDisplayID())
    let scale = NSScreen.main?.backingScaleFactor ?? 2
    return json(["x": b.origin.x, "y": b.origin.y, "w": b.width, "h": b.height, "scale": scale])
}

/// The app the user is looking at, never Andee itself.
///
/// Asking our own process for its accessibility tree is not a question to another
/// app: AppKit answers it inside this process, on the calling thread, by calling
/// into AWT's Java accessibility — off the main thread, which kills the JVM
/// (SIGBUS in CAccessibility, twice). And it is the wrong answer anyway: when
/// Andee is frontmost it is because the user just touched the ball, and what they
/// mean by "the screen" is the app behind it. So: the frontmost app if it is not
/// us, else the owner of the frontmost ordinary window that is not ours.
private func targetApp() -> NSRunningApplication? {
    let me = getpid()
    if let f = NSWorkspace.shared.frontmostApplication, f.processIdentifier != me { return f }
    let opts: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
    if let list = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]] {
        for w in list {
            guard (w[kCGWindowLayer as String] as? Int) == 0,
                  let pid = w[kCGWindowOwnerPID as String] as? pid_t, pid != me,
                  let app = NSRunningApplication(processIdentifier: pid),
                  app.activationPolicy == .regular else { continue }
            return app
        }
    }
    return nil
}

/// The focused element of the target app, asked of that app directly. The
/// system-wide "focused element" is Andee's own whenever Andee is frontmost.
private func targetFocused() -> AXUIElement? {
    guard let app = targetApp() else { return nil }
    let ax = AXUIElementCreateApplication(app.processIdentifier)
    guard let f = attr(ax, kAXFocusedUIElementAttribute) else { return nil }
    return (f as! AXUIElement)
}

/// The process whose window is frontmost at a point, skipping ours (at any alpha:
/// ours are faded, not gone, while a click is under way). Nil when nothing but
/// Andee is there.
private func otherAppAt(_ x: Double, _ y: Double) -> pid_t? {
    let me = getpid()
    guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID)
        as? [[String: Any]] else { return nil }
    for w in list {
        guard let pid = w[kCGWindowOwnerPID as String] as? pid_t, pid != me,
              (w[kCGWindowAlpha as String] as? Double ?? 1) > 0,
              let b = w[kCGWindowBounds as String] as? [String: Any],
              let r = CGRect(dictionaryRepresentation: b as CFDictionary),
              r.contains(CGPoint(x: x, y: y)) else { continue }
        return pid
    }
    return nil
}

/// Whether an element belongs to this process; such an element must not be queried (see targetApp).
private func isOurs(_ e: AXUIElement) -> Bool {
    var pid: pid_t = 0
    return AXUIElementGetPid(e, &pid) == .success && pid == getpid()
}

@_cdecl("andee_frontmost")
public func andee_frontmost() -> UnsafeMutablePointer<CChar> {
    guard let app = targetApp() else { return json([:]) }
    return json(["name": app.localizedName ?? "", "bundle": app.bundleIdentifier ?? "", "pid": Int(app.processIdentifier)])
}

@_cdecl("andee_running_apps")
public func andee_running_apps() -> UnsafeMutablePointer<CChar> {
    let apps = NSWorkspace.shared.runningApplications.filter { $0.activationPolicy == .regular }
    return json(apps.map { ["name": $0.localizedName ?? "", "bundle": $0.bundleIdentifier ?? "", "pid": Int($0.processIdentifier), "active": $0.isActive] })
}

// MARK: - accessibility tree

private func attr(_ e: AXUIElement, _ name: String) -> CFTypeRef? {
    var v: CFTypeRef?
    return AXUIElementCopyAttributeValue(e, name as CFString, &v) == .success ? v : nil
}

private func str(_ e: AXUIElement, _ name: String) -> String? {
    guard let v = attr(e, name) else { return nil }
    if let s = v as? String { return s.isEmpty ? nil : s }
    if let n = v as? NSNumber { return n.stringValue }
    return nil
}

private func frame(_ e: AXUIElement) -> CGRect? {
    guard let p = attr(e, kAXPositionAttribute), let s = attr(e, kAXSizeAttribute) else { return nil }
    var pt = CGPoint.zero, sz = CGSize.zero
    AXValueGetValue(p as! AXValue, .cgPoint, &pt)
    AXValueGetValue(s as! AXValue, .cgSize, &sz)
    return CGRect(origin: pt, size: sz)
}

private func actions(_ e: AXUIElement) -> [String] {
    var names: CFArray?
    guard AXUIElementCopyActionNames(e, &names) == .success, let a = names as? [String] else { return [] }
    return a
}

private var nodeBudget = 0

private func walk(_ e: AXUIElement, depth: Int) -> [String: Any]? {
    nodeBudget -= 1
    if nodeBudget < 0 || depth > 40 { return nil }
    var n: [String: Any] = [:]
    n["role"] = str(e, kAXRoleAttribute) ?? ""
    if let sub = str(e, kAXSubroleAttribute) { n["subrole"] = sub }
    if let t = str(e, kAXTitleAttribute) { n["title"] = t }
    if let d = str(e, kAXDescriptionAttribute) { n["desc"] = d }
    if let v = str(e, kAXValueAttribute) { n["value"] = String(v.prefix(200)) }
    if let h = str(e, kAXHelpAttribute) { n["help"] = String(h.prefix(120)) }
    if let id = str(e, kAXIdentifierAttribute) { n["id"] = id }
    if let p = str(e, kAXPlaceholderValueAttribute) { n["placeholder"] = p }
    if let f = frame(e) { n["frame"] = [f.origin.x, f.origin.y, f.width, f.height] }
    if let en = attr(e, kAXEnabledAttribute) as? Bool, !en { n["disabled"] = true }
    if let fo = attr(e, kAXFocusedAttribute) as? Bool, fo { n["focused"] = true }
    let acts = actions(e)
    if acts.contains(kAXPressAction as String) { n["press"] = true }
    if let settable = settableValue(e), settable { n["editable"] = true }
    if let kids = attr(e, kAXChildrenAttribute) as? [AXUIElement], !kids.isEmpty {
        let c = kids.compactMap { walk($0, depth: depth + 1) }
        if !c.isEmpty { n["children"] = c }
    }
    return n
}

private func settableValue(_ e: AXUIElement) -> Bool? {
    var s: DarwinBoolean = false
    return AXUIElementIsAttributeSettable(e, kAXValueAttribute as CFString, &s) == .success ? s.boolValue : nil
}

/** The frontmost app's focused window (or its first window) as a JSON tree. */
@_cdecl("andee_ax_tree")
public func andee_ax_tree(_ maxNodes: Int32) -> UnsafeMutablePointer<CChar> {
    guard let app = targetApp() else { return json(["error": "no app window on screen besides Andee"]) }
    let ax = AXUIElementCreateApplication(app.processIdentifier)
    // Chromium and Electron build their tree only when asked.
    AXUIElementSetAttributeValue(ax, "AXManualAccessibility" as CFString, kCFBooleanTrue)
    AXUIElementSetAttributeValue(ax, "AXEnhancedUserInterface" as CFString, kCFBooleanTrue)
    var win: AXUIElement? = nil
    if let w = attr(ax, kAXFocusedWindowAttribute) { win = (w as! AXUIElement) }
    else if let ws = attr(ax, kAXWindowsAttribute) as? [AXUIElement], let f = ws.first { win = f }
    nodeBudget = Int(maxNodes)
    var out: [String: Any] = ["app": app.localizedName ?? "", "bundle": app.bundleIdentifier ?? "", "pid": Int(app.processIdentifier)]
    if let w = win {
        out["window_title"] = str(w, kAXTitleAttribute) ?? ""
        out["tree"] = walk(w, depth: 0) ?? [:]
    } else {
        out["tree"] = [:]
        out["no_window"] = true
    }
    // The menu bar is part of the app a person can reach.
    if let mb = attr(ax, kAXMenuBarAttribute) {
        nodeBudget = 400
        out["menubar"] = walk(mb as! AXUIElement, depth: 0) ?? [:]
    }
    out["truncated"] = nodeBudget < 0
    return json(out)
}

/** Press the element at an index path from the last tree (child indices from the window root). */
@_cdecl("andee_ax_press_at")
public func andee_ax_press_at(_ x: Double, _ y: Double) -> Int32 {
    // Never the system-wide hit test: it reaches our own windows even while they are
    // faded out for this very click (alpha 0 still hit-tests), and asking our own
    // process kills the JVM. Ask the app that owns the point, by name.
    guard let pid = otherAppAt(x, y) else { return 0 }  // the caller falls back to a real click
    let app = AXUIElementCreateApplication(pid)
    var el: AXUIElement?
    guard AXUIElementCopyElementAtPosition(app, Float(x), Float(y), &el) == .success, let e = el, !isOurs(e) else { return 0 }
    return AXUIElementPerformAction(e, kAXPressAction as CFString) == .success ? 1 : 0
}

/** Set the focused element's value (a text field), as a person pasting it would. */
@_cdecl("andee_ax_set_focused_value")
public func andee_ax_set_focused_value(_ text: UnsafePointer<CChar>) -> Int32 {
    guard let e = targetFocused(), !isOurs(e) else { return 0 }
    return AXUIElementSetAttributeValue(e, kAXValueAttribute as CFString, String(cString: text) as CFString) == .success ? 1 : 0
}

@_cdecl("andee_ax_focused_value")
public func andee_ax_focused_value() -> UnsafeMutablePointer<CChar> {
    guard let e = targetFocused(), !isOurs(e) else { return json(["focused": false]) }
    return json(["focused": true, "role": str(e, kAXRoleAttribute) ?? "", "value": str(e, kAXValueAttribute) ?? ""])
}

// MARK: - input events (points in global display coordinates, top-left origin)

private func post(_ e: CGEvent?) { e?.post(tap: .cghidEventTap) }

@_cdecl("andee_click")
public func andee_click(_ x: Double, _ y: Double, _ button: Int32, _ count: Int32, _ holdMs: Int32) {
    let p = CGPoint(x: x, y: y)
    let (down, up, b): (CGEventType, CGEventType, CGMouseButton) =
        button == 1 ? (.rightMouseDown, .rightMouseUp, .right) : (.leftMouseDown, .leftMouseUp, .left)
    post(CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: b))
    usleep(30_000)
    for i in 1...max(1, Int(count)) {
        let d = CGEvent(mouseEventSource: nil, mouseType: down, mouseCursorPosition: p, mouseButton: b)
        d?.setIntegerValueField(.mouseEventClickState, value: Int64(i))
        post(d)
        usleep(useconds_t(max(30, Int(holdMs)) * 1000))
        let u = CGEvent(mouseEventSource: nil, mouseType: up, mouseCursorPosition: p, mouseButton: b)
        u?.setIntegerValueField(.mouseEventClickState, value: Int64(i))
        post(u)
        usleep(60_000)
    }
}

@_cdecl("andee_drag")
public func andee_drag(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double, _ ms: Int32) {
    let steps = max(8, Int(ms) / 16)
    post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: CGPoint(x: x1, y: y1), mouseButton: .left))
    for i in 1...steps {
        let t = Double(i) / Double(steps)
        let p = CGPoint(x: x1 + (x2 - x1) * t, y: y1 + (y2 - y1) * t)
        post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged, mouseCursorPosition: p, mouseButton: .left))
        usleep(useconds_t(max(1, Int(ms) / steps) * 1000))
    }
    usleep(120_000)
    post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: CGPoint(x: x2, y: y2), mouseButton: .left))
}

/** Scroll by [dy]/[dx] pixels at a point, in small steps so lists move as far as asked and no further. */
@_cdecl("andee_scroll")
public func andee_scroll(_ x: Double, _ y: Double, _ dx: Int32, _ dy: Int32) {
    post(CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: CGPoint(x: x, y: y), mouseButton: .left))
    usleep(30_000)
    let steps = 10
    for _ in 0..<steps {
        let e = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                        wheel1: dy / Int32(steps), wheel2: dx / Int32(steps), wheel3: 0)
        post(e)
        usleep(16_000)
    }
}

/** Type text as keystrokes carrying Unicode, which works in any field regardless of keyboard layout. */
@_cdecl("andee_type_text")
public func andee_type_text(_ text: UnsafePointer<CChar>) {
    let s = Array(String(cString: text).utf16)
    var i = 0
    while i < s.count {
        let chunk = Array(s[i..<min(i + 16, s.count)])
        let d = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true)
        chunk.withUnsafeBufferPointer { d?.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: $0.baseAddress) }
        post(d)
        let u = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: false)
        post(u)
        usleep(8_000)
        i += 16
    }
}

/** A key with modifiers: flags bit 0 cmd, 1 shift, 2 option, 3 control. */
@_cdecl("andee_key")
public func andee_key(_ keyCode: Int32, _ flags: Int32) {
    var f: CGEventFlags = []
    if flags & 1 != 0 { f.insert(.maskCommand) }
    if flags & 2 != 0 { f.insert(.maskShift) }
    if flags & 4 != 0 { f.insert(.maskAlternate) }
    if flags & 8 != 0 { f.insert(.maskControl) }
    let d = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(keyCode), keyDown: true)
    d?.flags = f
    post(d)
    let u = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(keyCode), keyDown: false)
    u?.flags = f
    post(u)
}

// MARK: - screenshots (ScreenCaptureKit, our own windows excluded)

/**
 * Capture the main display to a PNG at [path], leaving out every window of
 * this process — the ball and the cards are never in the picture.
 * Returns 1 on success.
 */
@_cdecl("andee_screenshot")
public func andee_screenshot(_ path: UnsafePointer<CChar>, _ maxSide: Int32) -> Int32 {
    let out = URL(fileURLWithPath: String(cString: path))
    let sem = DispatchSemaphore(value: 0)
    final class Box: @unchecked Sendable { var ok: Int32 = 0 }
    let box = Box()
    Task {
        defer { sem.signal() }
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            guard let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) ?? content.displays.first else { return }
            let mine = content.applications.filter { $0.processID == getpid() }
            let filter = SCContentFilter(display: display, excludingApplications: mine, exceptingWindows: [])
            let cfg = SCStreamConfiguration()
            let scale = NSScreen.main?.backingScaleFactor ?? 2
            var w = Double(display.width) * scale, h = Double(display.height) * scale
            let longSide = max(w, h)
            if maxSide > 0 && longSide > Double(maxSide) { let k = Double(maxSide) / longSide; w *= k; h *= k }
            cfg.width = Int(w); cfg.height = Int(h)
            cfg.showsCursor = false
            let img = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: cfg)
            let rep = NSBitmapImageRep(cgImage: img)
            if let png = rep.representation(using: .png, properties: [:]) { try png.write(to: out); box.ok = 1 }
        } catch {
            FileHandle.standardError.write("screenshot: \(error)\n".data(using: .utf8)!)
        }
    }
    _ = sem.wait(timeout: .now() + 10)
    return box.ok
}

// MARK: - workspace

@_cdecl("andee_open")
public func andee_open(_ target: UnsafePointer<CChar>) -> Int32 {
    let t = String(cString: target)
    if let u = URL(string: t), u.scheme != nil { return NSWorkspace.shared.open(u) ? 1 : 0 }
    if let u = NSWorkspace.shared.urlForApplication(withBundleIdentifier: t) {
        let sem = DispatchSemaphore(value: 0); var ok: Int32 = 0
        NSWorkspace.shared.openApplication(at: u, configuration: NSWorkspace.OpenConfiguration()) { app, _ in ok = app != nil ? 1 : 0; sem.signal() }
        _ = sem.wait(timeout: .now() + 10); return ok
    }
    return NSWorkspace.shared.open(URL(fileURLWithPath: t)) ? 1 : 0
}

// MARK: - the floating ball's window

/// Lifts our own window titled [title] above everything, the phone's
/// TYPE_APPLICATION_OVERLAY: above normal and floating windows, on every Space,
/// and over other apps' full-screen Spaces. A JVM window can only ask for
/// "always on top", which is the floating level and stays on one Space.
@_cdecl("andee_float_window")
public func andee_float_window(_ title: UnsafePointer<CChar>) -> Int32 {
    let t = String(cString: title)
    // Async on purpose: AWT's main thread may itself be waiting on the JVM's
    // event thread, and a sync hop from there would deadlock the app.
    DispatchQueue.main.async {
        guard let w = NSApp?.windows.first(where: { $0.title == t }) else { return }
        w.level = .statusBar
        w.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        w.hidesOnDeactivate = false
        w.hasShadow = false
    }
    return NSApp == nil ? 0 : 1
}

/// Fade every window of ours in or out, for the instant of a click aimed at
/// another app. Native because AWT refuses `setOpacity` on a decorated window
/// (the chat), and a refusal half-way left the ball invisible for good.
/// Waits for the main thread at most half a second, never forever: the main
/// thread can itself be waiting on the JVM.
@_cdecl("andee_set_own_alpha")
public func andee_set_own_alpha(_ alpha: Double) -> Int32 {
    let done = DispatchSemaphore(value: 0)
    DispatchQueue.main.async {
        NSApp?.windows.forEach { $0.alphaValue = CGFloat(alpha) }
        done.signal()
    }
    return done.wait(timeout: .now() + 0.5) == .success ? 1 : 0
}

/// Whether a visible window of ours covers this point — only then does a click need us out of the way.
@_cdecl("andee_own_window_at")
public func andee_own_window_at(_ x: Double, _ y: Double) -> Int32 {
    let me = getpid()
    guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] else { return 1 }
    for w in list where (w[kCGWindowOwnerPID as String] as? pid_t) == me {
        if (w[kCGWindowAlpha as String] as? Double ?? 1) == 0 { continue }
        if let b = w[kCGWindowBounds as String] as? [String: Any],
           let r = CGRect(dictionaryRepresentation: b as CFDictionary),
           r.insetBy(dx: -4, dy: -4).contains(CGPoint(x: x, y: y)) { return 1 }
    }
    return 0
}
