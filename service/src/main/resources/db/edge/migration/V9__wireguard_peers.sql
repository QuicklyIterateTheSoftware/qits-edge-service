-- The private edge's view of which WireGuard peers qits-network-access has registered (epic
-- qits-1977). PeerRegistered writes a row active, PeerRevoked writes it inactive; LivePeers serves
-- the active ones on wg0, merged with any peers stated by hand in qits.edge.wireguard.peers. The
-- public edge keeps the same rows and applies nothing.
--
-- `active = false` is a TOMBSTONE rather than a delete, for the reason edge_project's `present` is
-- one: this consumer replays from the epoch, so a register can arrive after the revoke that ended
-- it, and only a kept row with the revoke's (occurred_at, event_id) makes that register the older
-- frame, which last-writer-wins refuses. Keyed by peer_id, the registration's identity; the public
-- key is what wg0 knows a peer by and is carried, never logged.
create table edge_wireguard_peer (
    peer_id     uuid primary key,
    name        text not null,
    public_key  text not null,
    tunnel_ip   text not null,
    active      boolean not null,
    event_id    uuid not null,
    occurred_at timestamp(6) with time zone not null
);
