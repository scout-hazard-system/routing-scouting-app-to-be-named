# encryption/

`secure_channel.java` — AES-256-GCM framed node-to-node channel with
HMAC-SHA256 key derivation from a shared registration secret.

Preserved verbatim from `scout-hazard-system/secure-mesh-navigation`
(`encryption/secure_channel.java` @ `55c1ab8`) when that superseded snapshot
was archived. It is not yet wired into `navigation/backend`; the mesh currently
relies on WireGuard (`scoutwg0`) for transport encryption.
