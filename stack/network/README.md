# Dell network resilience

| File | Install to | Why |
|---|---|---|
| `60-scout-nonlocal-bind.conf` | `/etc/sysctl.d/` then `sysctl -p` it | nginx can bind interface addresses before the link is up |
| `90-scout-connectivity.conf` | `/etc/NetworkManager/conf.d/` then `systemctl reload NetworkManager` | failover between Ethernet and wifi on real connectivity, not just carrier |
| `../systemd/nginx.service.d/scout-resilience.conf` | `/etc/systemd/system/nginx.service.d/` then `systemctl daemon-reload` | nginx waits for the network and restarts on failure |

Ethernet as primary (on the Dell):

```
nmcli con mod "Wired connection 1" ipv4.never-default no ipv4.route-metric 100 ipv4.ignore-auto-dns yes
nmcli device reapply eno1
```

`ignore-auto-dns` keeps the home router's resolvers: the EdgeRouter's DHCP hands out an ESP32 DNS
(192.168.12.162) that breaks glibc's parallel A/AAAA lookups.
