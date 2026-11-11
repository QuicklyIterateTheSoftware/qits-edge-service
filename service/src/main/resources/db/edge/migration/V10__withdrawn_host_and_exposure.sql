-- What a deployment withdrawn from this edge still tells the navigation (qits-2061).
--
-- A frame outside the edge's exposure class is stored as an empty snapshot, so the application
-- leaves every route, vhost and certificate exactly as a retirement does. These two columns are the
-- only thing that row now keeps besides its frame order, and nothing that routes reads them:
--
-- withdrawn_exposure is the deployment's declared exposure (`private` on the public edge, `public`
-- on the private one). Null on every row this edge serves; set means "withdrawn, and why".
--
-- withdrawn_host is the DNS label the deployment declared, kept apart from browser_host on purpose:
-- browser_host is a routed name — it holds the unique index, resolves to the primary upstream and
-- must have a route behind it — and this one is none of those. /main-navigation names it, as an
-- origin with `"exposure": "private"`, to a caller the gate would admit.
alter table edge_deployment_snapshot
    add column withdrawn_exposure varchar(16);

alter table edge_deployment_snapshot
    add column withdrawn_host varchar(63);

-- V4's lesson, as V5 applied it: the replay is idempotent by event id, so an edge upgraded in place
-- would never re-read the frame that withdrew an application and both columns would stay null
-- until it deployed again. Deleting the snapshot rows (endpoints and placements cascade) makes the
-- next boot's replay rewrite the projection in this shape. A fresh database has nothing to delete.
delete from edge_deployment_snapshot;
