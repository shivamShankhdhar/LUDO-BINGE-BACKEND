# Online real-time rollout

The Android app and server must be released together for protocol 2.

- Gameplay, invitations, approvals, chat and WebRTC signaling use authenticated `/ws/online` WebSockets. Commands have request IDs, acknowledgements and idempotency keys. Authoritative snapshots are published after database commit; reconnect obtains a fresh personalized snapshot.
- HTTP sync is recovery when the socket is unavailable or the server is older. The app does not run SSE or periodic HTTP sync while the protocol-2 socket is healthy. An uncertain socket mutation is not automatically replayed over HTTP.
- Voice audio uses peer-to-peer WebRTC, with TURN relay when direct connectivity fails. WebSockets carry signaling, not audio.
- Missed human turns skip without rolling dice or moving pawns; the existing three-missed-turn forfeiture rule remains. Results require a finished authoritative match.

## Deployment prerequisites

1. Deploy the updated server to `ludo-binge-backend.shivamshankhdhar.online`. Its proxy must pass WebSocket Upgrade/Connection headers and Authorization to `/ws/online`, and permit persistent connections.
2. Configure a working Coturn-compatible service. Set `TURN_URLS` to reachable TURN URLs and `TURN_SHARED_SECRET` to the matching server-side shared secret. Store the secret in the hosting secret manager. The backend issues short-lived credentials. Without these values, voice intentionally reports unavailable.
3. For multiple backend instances, enable `REDIS_ENABLED=true` and configure shared `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD`. With Redis enabled but unreachable the backend fails startup rather than running disconnected realtime instances. A single instance can use local publication.
4. Install the updated Android build. Verify two separate accounts across different networks: request/approve, ready/start, manual roll/move, reconnect, emoji/chat, microphone permission and two-way voice.

## Verification and limits

Automated integration tests use an isolated PostgreSQL database and two authenticated WebSocket clients. They exercise approval, matching, command deduplication, reconnect, room isolation, rollback publication and voice signaling. Test TURN settings validate credential/signaling flow only; they do not prove audio connectivity.

The current dispatcher coalesces committed changes and recomputes personalized snapshots for connected clients, sending only changed data. Load testing is still required before claiming capacity or latency at scale.

No production deployment or TURN provisioning is performed by this change. The live capabilities endpoint reported `voiceEnabled: false` during investigation.
