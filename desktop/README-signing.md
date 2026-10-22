# Signing the Mac app

macOS remembers Accessibility and Screen Recording against an app's code
signature. An ad-hoc signed `Andee.app` is a new app on every build, so every
grant lapses after each rebuild (the switch in System Settings still shows *on*
for the old build). Signing with one fixed identity keeps grants across builds.

Only the person who **packages** Andee needs this. Anyone who just uses the
built `Andee.app` needs nothing.

## What is set up

- `~/Andee-signing/andee.p12` — a self-signed code-signing certificate,
  "Andee Local Signing" (10 years). **Back this file up**: a new certificate is
  a new app to macOS, and every user would have to grant permissions again.
- `~/Library/Keychains/andee-signing.keychain-db` — a keychain holding only
  that identity, unlocked by `~/Andee-signing/keychain-password`, so builds
  sign without a password prompt.
- `./gradlew :desktop:createDistributable` runs `signApp` after packaging. With
  no such keychain it skips signing and the app stays ad-hoc.

Override with `-Pandee.signing.identity=…` / `-Pandee.signing.keychain=…`
(e.g. a real "Developer ID Application" identity later).

## On a new packaging machine

```sh
mkdir -p ~/Andee-signing && cp andee.p12 ~/Andee-signing/   # the backed-up file
cd ~/Andee-signing
PW=$(openssl rand -hex 16); echo -n "$PW" > keychain-password; chmod 600 keychain-password
KC=~/Library/Keychains/andee-signing.keychain-db
security create-keychain -p "$PW" $KC
security set-keychain-settings $KC
security unlock-keychain -p "$PW" $KC
security import andee.p12 -k $KC -P andee -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$PW" $KC
security list-keychains -d user -s $(security list-keychains -d user | tr -d '"') $KC
```

## What users see

The certificate is not from Apple, so Gatekeeper still blocks the first open of
a downloaded copy: right-click Andee → Open, or System Settings → Privacy &
Security → Open Anyway. Once. After that, and after every update signed with
the same certificate, Accessibility and Screen Recording stay granted.

Removing that first-open step needs an Apple Developer ID ($99/year) and
notarization; pass its identity with `-Pandee.signing.identity`.
