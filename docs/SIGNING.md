# App identity and signing

Package: com.baruchiro.podcatcher.next. It is a separate app from the earlier v0.1/v0.2 debug builds
(package com.baruchiro.podcatcher), whose signing key could not be recovered.

- Public certificate: docs/podcatcher-signing-cert.pem (EC P-256, self-signed, valid until 2056).
  SHA-256 B3:97:4D:EA:52:25:DE:54:83:E9:1B:4C:88:1D:67:89:A2:21:0E:60:DE:C0:59:62:7C:7D:C4:C4:D1:97:5B:ED
- Private key: held in the assistant vault item "Podcatcher app signing key" (hex of the 32-byte EC private scalar,
  one line). It must never be committed, posted or put in CI.
- Rebuild a PKCS12 for a build: turn the hex into a SEC1 key
  (30 25 02 01 01 04 20 <32 bytes> a0 0a 06 08 2a 86 48 ce 3d 03 01 07), then
  openssl pkcs12 -export -inkey key.pem -in docs/podcatcher-signing-cert.pem -name podcatcher -out id.p12
  and build with PODCATCHER_KEYSTORE=/path/id.p12 PODCATCHER_KEYSTORE_PASS=... ./gradlew assembleDebug.
- Check: apksigner verify --print-certs app-debug.apk must show the SHA-256 above.
