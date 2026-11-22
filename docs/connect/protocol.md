# Kasane Connect protocol v1

This file is normative. Kotlin and Rust must agree with it and with `test-vectors.json`.

## Transport
- **Connection:** TCP to the port advertised over mDNS. The default is 47650; on bind failure, an ephemeral port is used and advertised.
- **TLS:** TLS 1.3 only, and both sides present a certificate.
  - Each device has a long-lived self-signed ECDSA P-256 certificate (SHA256withECDSA).
  - **Fingerprint:** lowercase hex SHA-256 of the leaf certificate DER.
  - Normal CA/hostname validation is disabled on both sides. The peer fingerprint is captured and enforced at the application layer.
- **Framing:** one UTF-8 JSON object per line, terminated by `\n`.
  - Maximum frame size is 1 MiB; a larger frame closes the connection.
  - Unknown fields are ignored. An unknown `type` is answered with `error{code:"unknown_type"}` and the connection stays open.
- **Keepalive:** a `ping` every 10 s, answered by `pong`. With no inbound frame for 30 s, the connection is dead.
- **Roles:** the TCP server is the **player**, which exposes its own local playback. The TCP client is the **controller**. Every device runs a server and can open any number of controller connections.

## Handshake
1. Both sides send `hello` immediately: `{"type":"hello","protocol":1,"minProtocol":1,"device":{"id":"<uuid>","name":"Pixel 6 Pro","platform":"android"|"linux","app":"Kasane","appVersion":"…"}}`.
2. If the version ranges do not overlap, the side detecting it sends `error{code:"incompatible_version"}` and closes the connection.
3. The server checks whether the client's TLS fingerprint and `device.id` match a trusted record.
   - Match: it sends `{"type":"authOk"}`.
   - No match: it sends `{"type":"authRequired"}`. Only `pair*`, `ping`, `pong` and `hello` are then accepted. Anything else gets `error{code:"unauthorized"}` and is not executed.
4. A trusted `device.id` presenting a different fingerprint is treated as untrusted, and the server records that in its log.

## Pairing (numeric comparison)
1. **Commit:** the client sends `{"type":"pairRequest","commit":hex(SHA256("kasane-commit-v1" ‖ nc))}`, where `nc` is 32 random bytes.
2. **Server nonce:** the server sends `{"type":"pairNonce","ns":hex(ns)}`, with `ns` 32 random bytes.
3. **Reveal:** the client sends `{"type":"pairReveal","nc":hex(nc)}`. The server verifies the commit. On mismatch it sends `pairResult{accepted:false,reason:"commit_mismatch"}`.
4. **Code:** both sides compute `SAS = u32_be(SHA256("kasane-pair-v1" ‖ fpS ‖ fpC ‖ ns ‖ nc)[0..4]) mod 1_000_000`, zero-padded to 6 digits.
   - `fpS` and `fpC` are the raw 32-byte fingerprints of the server and client certificates as seen in this TLS session.
   - String constants are ASCII bytes.
5. **Confirm:** both UIs show the SAS and the peer name. Each user decision is sent as `{"type":"pairConfirm","accepted":true|false}`, in both directions.
6. **Result:** when the server has its own decision and the client's, it sends `{"type":"pairResult","accepted":bool,"reason":?}`.
   - On success, both sides persist `{deviceId,name,platform,fingerprint,pairedAt}` and the server then sends `authOk`.
7. **Limits:**
   - The whole pairing must finish within 120 s.
   - The server allows only one pending pairing; another `pairRequest` gets `pairResult{accepted:false,reason:"busy"}`.
   - After 3 rejected or failed pairings within 10 minutes, the server refuses pairing for 5 minutes with reason `"rate_limited"`.
8. **Revocation:**
   - Removing a trusted device deletes its record, and later connections from it get `authRequired`.
   - If connected, the remover sends `{"type":"unpair"}` before closing, and the receiver deletes its record too.

## State (player → controller, after `authOk`)
The controller sends `{"type":"subscribe"}`. The player then sends a full `state` and pushes further `state` frames on every meaningful change. Position-only changes are sent at most once per second while playing.

```json
{"type":"state","rev":12,"queueRev":3,
 "playback":"idle|buffering|playing|paused|ended|error",
 "track":TrackMeta|null,"positionMs":0,"durationMs":0,
 "volume":0.0-1.0|null,"shuffle":false,"repeat":"off|all|one",
 "queueIndex":-1,"queue":[TrackMeta]|absent,"error":string|null,
 "capabilities":["seek","volume","queue","handoff"]}
```

- `rev` increases strictly with every frame. A controller discards any frame whose `rev` is not greater than the last one it received on that connection.
- `queue` is present only when `queueRev` changed since the previous frame on this connection, and always in the first frame. If a controller sees a new `queueRev` without `queue`, it sends `{"type":"sync"}`, and the player replies with a full frame.
- The controller extrapolates position while `playback=="playing"` from its own receive time. Clocks are never compared across devices.

TrackMeta (metadata only, never stream URLs):
`{"id":"<videoId>|local:…","title":"…","artist":"display string","artists":[{"name":"…","id":"UC…"|null}],"album":{"title":"…","id":"MPRE…"|null}|null,"durationMs":0,"artworkUrl":"https://…"|null,"source":"youtube|local"}`

## Commands (controller → player)
The controller sends `{"type":"command","id":"<unique string>","seq":<u64>,"action":"…",…args}`, and the player replies `{"type":"ack","id":"…","ok":true}` or `{"type":"ack","id":"…","ok":false,"error":{"code":"…","message":"…"}}`.

- **Sequence:** `seq` increases strictly within a connection. A frame with `seq ≤ last` is rejected with `replayed` and not executed.
- **Duplicates:** the player remembers the last 256 command `id`s per peer device, across reconnects. A repeated `id` is acknowledged with the original result and not executed again. Controllers may therefore retry an unacknowledged command after a reconnect with the same `id`.
- **Stale queue:** index-based actions carry `queueRev`. If it differs from the current one, they fail with `stale_queue`.

| action | args |
|---|---|
| `play` / `pause` / `toggle` / `next` / `previous` | — |
| `seek` | `positionMs` |
| `setVolume` | `volume` (0..1) |
| `setShuffle` | `enabled` |
| `setRepeat` | `mode` (off/all/one) |
| `playQueueIndex` / `removeQueueIndex` | `index`, `queueRev` |
| `moveQueue` | `from`, `to`, `queueRev` |
| `playTrack` / `addToQueue` / `playNext` | `track` (TrackMeta) |

Error codes: `unauthorized`, `replayed`, `stale_queue`, `unsupported`, `unsupported_media`, `invalid_args`, `failed`.

## Handoff (source → target)
1. The source controller sends `{"type":"handoff","transferId":"…","queue":[TrackMeta],"index":n,"positionMs":p,"playing":true,"shuffle":false,"repeat":"off"}`. `positionMs` is computed at send time.
2. The target resolves `queue[index]` with its own provider and starts it at `positionMs` (paused if `playing` is false). It keeps the rest of the queue as metadata. The target's previous queue must not be destroyed before the new item has started.
3. The target replies `{"type":"handoffResult","transferId":"…","ok":true}`, or `ok:false` with an `error` (for example `unsupported_media` for `local:` tracks, or `failed`).
4. The source pauses its own playback only after `ok:true`. If there is no result within 30 s, the source keeps playing and reports the failure.
5. **Pull** ("play here" on the controller) uses the same semantics: the controller starts local playback from the snapshot it holds, and only after that succeeds sends `pause` to the remote player.

## Discovery
- **mDNS service** `_kasane._tcp.local.`: the instance name is the device name, and the port is the server port.
- **TXT records:** `id=<deviceId>`, `platform=android|linux`, `proto=1`, `fp=<first 16 hex chars of fingerprint>`.
  - They are hints for listing and matching only. Trust is decided by the full TLS fingerprint.
- **Online status:** a device counts as online if it was resolved within the last 120 s or is currently connected. Devices ignore their own `id`.
- **Manual entry:** `host[:port]` is persisted as an extra endpoint for that device after its first successful `hello`.
- **Reconnect:** a controller connected to a trusted device reconnects with backoff of 1, 2, 4 … 30 s. A fresh mDNS resolve of that device triggers an immediate reconnect attempt.

## Implementation notes (v1 deployments)
- **Admission control:** a server accepts at most 2 unauthenticated inbound connections per source IP and 4 unauthenticated connections in total; unauthenticated connections that send nothing for 20 s are closed, so unauthenticated peers cannot starve paired devices.
- **Manual entry:** `host[:port]` accepts private/loopback/link-local IPv4 addresses only; hostnames and public addresses are rejected.
