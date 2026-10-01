# k3s Sandbox Cluster on the Scout Mesh

**Date:** 2026-09-12
**Status:** Draft plan — adapted from the generic two-node k3s proposal to the **actual Scout Mesh** (WireGuard `scoutwg0`, `10.66.0.0/16`, hub `10.66.2.3`). Nothing here is applied yet; review before executing.

The goal is unchanged: run k3s over a **stable, dedicated node-to-node fabric**, then sandbox the untrusted user-facing client app so a compromise inside it cannot reach the base host OS, the map server, or the blackboard.

---

## 0. What gets edited vs the generic proposal

| Generic draft | Actual Scout deployment |
|---|---|
| New tunnel interface `wg0`, `10.0.0.0/24`, port `51820` | **Reuse Scout Mesh `scoutwg0`** (`10.66.0.0/16`, split tunnel, `PersistentKeepalive 25`). Keys/units already exist from `configure_scout_mesh.sh` — do **not** stand up a second tunnel. |
| Node 1 = "Optiplex State Master" | **Node 1 = the map-server hub** (`10.66.2.3`), which already hosts map server `:18080` and blackboard `:8765` (bare metal today). Becomes k3s control plane + placement for stateful services + sandbox. |
| Node 2 = "3070 Ti Compute Engine" | **Node 2 = Ollama inference node**, e.g. `10.66.2.6` (pick the real IP from `wg show`; it must already be a `scoutwg0` peer). GPU-only workloads go here. |
| Fresh `/etc/wireguard/wg0.conf` on both | Both nodes already run `wg-quick@scoutwg0`. Only changes needed: **hub must hold a `/32` [Peer] entry for Node 2** (hub-spoke mesh routes `10.66.0.0/16`); hub endpoint port stays whatever `configure_scout_mesh.sh` uses today. |
| `ping 10.0.0.1/10.0.0.2` | `ping 10.66.2.3` from Node 2 and `ping <node2-ip>` from the hub. |
| Flannel pinned to `wg0` | Flannel pinned to `scoutwg0` via `--flannel-iface=scoutwg0`. |
| Node IPs `10.0.0.x` | Node IPs = the mesh IPs (`10.66.2.3`, `10.66.2.6`). Pod/svc CIDRs (k3s defaults `10.42.0.0/16`, `10.43.0.0/16`) don't collide with `10.66`. |
| `Node 2` runs only Ollama pod | Same, plus CUDA runtimeclass; **gVisor is unacceptable for the GPU pod** — see §5. |

**Mesh topology note:** `scoutwg0` is hub-and-spoke (each box peers to the hub, traffic between peers transits the hub). With the control plane on the hub this is fine: Node 2 → `10.66.2.3` is one hub hop, and k3s/Flannel ride it as-is.

---

## 1. Network prerequisites (before k3s)

```bash
# Node 1 (hub, 10.66.2.3): confirm the cluster peer is routed
sudo wg show scoutwg0            # Node 2 must appear as a peer with its /32
sudo wg set scoutwg0 peer <NODE2_PUBKEY> allowed-ips 10.66.2.6/32   # if missing; then persist in the conf

# Both nodes: separate the k3s ports from the mesh listeners
sudo ufw allow 6443/tcp          # Node 1: k3s API
sudo ufw allow 6443/tcp          # Node 2: outbound (no rule needed with default allow-out)
# Flannel VXLAN uses UDP 4789 between the two mesh IPs; it runs inside scoutwg0,
# so no extra host firewall change unless a host firewall blocks loopback-ish flows.
```

Verification gate:
```bash
# From Node 2:
ping -c1 10.66.2.3 && curl -sfS --connect-timeout 3 http://10.66.2.3:18080/health
```

MTU note: `scoutwg0` uses MTU 1420; Flannel VXLAN adds ~50 bytes → pod MTU ≈ 1370. k3s sets this automatically per `--flannel-iface`; if you ever see odd fragmentation, check `host-gateway` MTU instead of lowering the tunnel.

---

## 2. Control plane on Node 1 (Optiplex / hub, `10.66.2.3`)

```bash
curl -sfL https://k3s.io | sh -s - server \
  --node-ip=10.66.2.3 \
  --node-external-ip=10.66.2.3 \
  --flannel-iface=scoutwg0 \
  --flannel-backend=vxlan \
  --disable=traefik \
  --write-kubeconfig-mode=644 \
  --cluster-cidr=10.42.0.0/16 \
  --service-cidr=10.43.0.0/16
```

- `--disable=traefik`: the stack already serves UI at `:8787`; the default `:80/:443` ingress is not needed and only risks a clash.
- Keep `service-cidr=10.43.0.0/16`/`cluster-cidr=10.42.0.0/16` explicit so they never drift onto `10.66.0.0/16`.
- Do **not** run `--flannel-iface` on any LAN iface — pinning to `scoutwg0` is what prevents cluster traffic leaking onto the LAN.

Capture the join token:
```bash
sudo cat /var/lib/rancher/k3s/server/node-token   # K3S_TOKEN below
```

---

## 3. Worker join on Node 2 (3070 Ti, `10.66.2.6`)

```bash
curl -sfL https://k3s.io | K3S_URL=https://10.66.2.3:6443 \
  K3S_TOKEN=<PASTE_TOKEN> sh -s - agent \
  --node-ip=10.66.2.6 \
  --node-external-ip=10.66.2.6 \
  --flannel-iface=scoutwg0
```

Sanity checks (run from Node 1):
```bash
sudo k3s kubectl get nodes -o wide        # both Ready, Internal-IP = mesh IPs, NOT lan IPs
sudo k3s kubectl get pods -A              # coreDNS + flannel running on both
```

---

## 4. GPU runtime for the Ollama pod (Node 2 only)

- Install NVIDIA driver + `nvidia-container-toolkit` and configure the **k3s containerd** (paths differ from system containerd):
  ```bash
  sudo nvidia-ctk runtime configure --runtime=containerd --config=/var/lib/rancher/k3s/agent/etc/containerd/config.toml
  sudo systemctl restart k3s-agent
  ```
- Label the node: `sudo k3s kubectl label node <node2> nvidia.com/gpu=true`
- Add a RuntimeClass `nvidia` (`runtimeHandler: nvidia`) **and** a RuntimeClass `gvisor` (`runtimeHandler: runsc`) on the control plane.
- Verify: `sudo k3s kubectl get nodes -o json | jq '.items[].status.allocatable["nvidia.com/gpu"]'`

> **gVisor + GPU = no.** `runsc` does not support CUDA/NVIDIA passthrough, so the **Ollama pod must use `runtimeClassName: nvidia`** (runc), never gvisor. gVisor is reserved for the CPU-only user sandbox on Node 1.

---

## 5. Workload topology (k3s, pinned to the mesh)

```
Node 1 10.66.2.3 (control plane)
├── [stateful] map-server   hostNetwork: true  (keep :18080)   — until migrated (see §8)
├── [stateful] blackboard   hostNetwork: true  (keep :8765)    — until migrated
└── [sandbox]  user-client  runtimeClassName: gvisor, labels sandbox.zone=untrusted
                            egress ONLY to ollama service per NetworkPolicy
Node 2 10.66.2.6 (worker)
└── [gpu]      ollama       runtimeClassName: nvidia, nodeSelector nvidia.com/gpu=true
                            Service type ClusterIP, name: ollama, port 11434
```

- **Do not move map server / blackboard off bare metal yet.** They are load-bearing and the harness env points at `http://10.66.2.3:18080` / `:8765`/`SCOUT_BLACKBOARD_URL`. Host-network pods that bind the same ports would collide. Migration path is §8.
- Ollama: keep the current `host.docker.internal`/mesh URL scheme working by pointing the pod at the cluster service (`http://ollama.default.svc.cluster.local:11434`) and, for external clients, keep a `NodePort` on Node 2 only.

### Representative ollama deployment
```yaml
apiVersion: apps/v1
kind: Deployment
metadata: {name: ollama}
spec:
  selector: {matchLabels: {app: ollama}}
  template:
    metadata: {labels: {app: ollama}}
    spec:
      nodeSelector: {nvidia.com/gpu: "true"}
      runtimeClassName: nvidia
      containers:
        - name: ollama
          image: ollama/ollama:latest
          ports: [{containerPort: 11434}]
          volumeMounts: [{name: ollama, mountPath: /root/.ollama}]
          resources:
            limits: {nvidia.com/gpu: "1"}
      volumes: [{name: ollama, hostPath: {path: /var/lib/ollama, type: DirectoryOrCreate}}]
```

---

## 6. Sandbox isolation (NetworkPolicy)

Goal: the user/client sandbox pod may talk **only** to the Ollama service. Everything else — blackboard, map server, host base OS, the LAN — is dropped. Applied in the sandbox namespace on Node 1.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: sandbox-default-deny}
spec:
  podSelector: {matchLabels: {sandbox.zone: untrusted}}
  policyTypes: [Ingress, Egress]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: sandbox-allow-ollama-only}
spec:
  podSelector: {matchLabels: {sandbox.zone: untrusted}}
  policyTypes: [Egress]
  egress:
    - to:
        - podSelector: {matchLabels: {app: ollama}}
      ports: [{protocol: TCP, port: 11434}]
    # kube-dns for service-name resolution (10.43.0.10:53)
    - to:
        - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: kube-system}}
          podSelector: {matchLabels: {k8s-app: kube-dns}}
      ports: [{protocol: UDP, port: 53}, {protocol: TCP, port: 53}]
```

Notes:
- k3s's default flannel + kube-router/iptables honors these policies without extra CNI.
- A compromise in the sandbox therefore cannot reach the blackboard (`:8765`) or map server (`:18080`) even though they live on the same node — policy blocks pod→hostLoopback traffic to those ports.
- If the sandbox needs to resolve external hosts, replace the kube-dns allow-list with an explicit `IPBlock` (never `0.0.0.0/0`).

---

## 7. Verification & keep-harness-working checklist

- [ ] `kubectl get nodes` shows both Ready with **mesh** IPs as `Internal-IP`.
- [ ] Ollama reachable in-cluster: `kubectl run probe --rm -it --image busybox -- wget -qO- http://ollama:11434/api/tags`
- [ ] From a sandbox pod, `blackboard/map` are unreachable; from the host they still work (existing harness env untouched while services stay hostNetwork/bare-metal).
- [ ] `SCOUT_BLACKBOARD_URL`/`SCOUT_MAP_BASE_URL`/Ollama mesh URLs in `vehicle_stack.env` unchanged until §8.
- [ ] Flannel routing rides `scoutwg0`: `ip route` on both nodes shows `10.42.0.0/* via ... dev scoutwg0`.

---

## 8. Migration path (later, separate change)

1. Keep map server + blackboard as k3s **hostNetwork** deployments on Node 1 with the **same ports**, then stop the bare-metal systemd units (`install_blackboard_service.sh`, `install_map_server`…). No harness URL change needed if ports persist.
2. Frontend `:8787` can stay bare-metal or move into the cluster with hostNetwork after step 1 lands.
3. Rework `stack/config/vehicle_stack.env` to cluster-internal URLs (`http://ollama`, `http://blackboard`, `http://map-server`) only **after** all three are cluster-native.

Keep step 1–3 as a separate PR from this k3s bring-up so the cluster can be validated with the existing stack first.

---

## 9. Risks / decisions to confirm

- **Node 2 mesh IP:** use the real scoutwg0 IP from `sudo wg show` (this doc assumes `10.66.2.6`); confirm the hub's [Peer] entry routes it.
- **hub-spoke hop:** worker→API/Flannel traffic transits the hub. For 2 nodes this is negligible; if a third GPU node joins far from the hub, add a direct [Peer] between GPU nodes.
- **gVisor placement:** only the CPU-only sandbox uses `runsc`. Any pod needing CUDA must use the `nvidia` runtimeclass.
- **k3s upgrade path:** pin k3s version at install (`... | INSTALL_K3S_VERSION=v1.x sh -s -`) and keep it in lockstep across both nodes.
- **Host-firewall false sense:** NetworkPolicy is the real gate; also set `--kubelet-arg=max-pods` defaults untouched and keep kubelet bound to mesh IPs so it never advertises LAN addresses.