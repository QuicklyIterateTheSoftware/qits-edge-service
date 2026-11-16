-- The tunnel address each private edge last announced (qits-2273): the newest PrivateEdgeAnnounced
-- per application, by the frame's (occurred_at, event_id), so a replay from the epoch cannot roll a
-- newer address back. `tunnel_address` is the bare IPv4 of wg0 (any /prefix stripped). The public
-- edge reads the newest row overall as the value of every private host's A record in the public
-- zone (PrivateHostRecords); every other edge keeps the same rows and reads nothing.
create table edge_private_edge_address (
    application    text primary key,
    tunnel_address text not null,
    event_id       uuid not null,
    occurred_at    timestamp(6) with time zone not null
);
