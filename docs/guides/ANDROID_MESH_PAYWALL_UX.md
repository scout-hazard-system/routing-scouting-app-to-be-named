# Android client UX state machine (mesh AP + paywall)

```
[Installed]
   -> reach hub pre-mesh (LAN/public :18080)
   -> POST /api/mesh/enroll (entry_token)     # Gate B — transport only
   -> configure scoutwg0 / join 10.66.0.0/16
   -> GET /api/health OK
   -> [MeshJoinedUnregistered]
         | missing/invalid X-Scout-Subscription on paid routes
         v
      [PaywallScreen]  # purchase / register
         | admin issues device-bound sub token OR store purchase validates
         v
      [SubscribedAP]
         -> full nav: map/bootstrap/route/gps as mesh access point
         -> still NOT admin (no admin token, no peer apply)
```

Unregistered clients remain **mesh access points candidates** only; they never receive admin capabilities.
