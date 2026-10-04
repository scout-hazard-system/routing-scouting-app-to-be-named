# Scout WireGuard mesh (scoutwg0, 10.66.0.0/16)

Hub-and-spoke; the Dell is the hub and only forwards mesh<->mesh traffic.

| node | mesh IP | endpoint used to reach the hub |
|---|---|---|
| Dell (hub) | 10.66.0.1 | listens UDP 51820 on both LANs |
| NUC nuxci7 | 10.66.2.2 | 192.168.1.100:51820 |
| Windows workstation | 10.66.2.4 | 192.168.12.231:51820 |

- Keys live only on each node (`/etc/wireguard/scoutwg0.key`, or the
  Windows tunnel config under `%USERPROFILE%\.scout-mesh`). Configs use
  `PostUp = wg set %i private-key <file>` so the conf itself holds no secret.
- Add a peer on the hub: `sudo scout-mesh-add-peer <name> <pubkey> <10.66.x.y>`.
- `scout-guard.nft` (included from `/etc/nftables.conf` on the Dell): app ports
  reachable only from loopback, the mesh, the Scout LAN and the workstation;
  forward policy drop except scoutwg0<->scoutwg0.
- Agent SSH keys (`~/.ssh/scout_agent_mesh`) are authorized with
  `from="10.66.0.0/16"`, so they only work across the mesh.
