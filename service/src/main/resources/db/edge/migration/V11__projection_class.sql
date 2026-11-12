-- The exposure class the deployment projection was last rebuilt under (qits-2120).
--
-- Which frames this edge withdrew depends on its class, and the replay cannot undo a withdrawal by
-- itself: EdgeRoutes.replace writes only a strictly newer frame, so the empty snapshot a `private`
-- frame left under `public` survives a replay under `all` (the break-glass). At boot,
-- DeploymentProjectionBootstrap compares this row with qits.edge.exposure; when it is absent or
-- differs, it deletes the projection and writes the new class in one transaction, then replays from
-- the epoch. One row at most: the code deletes before it inserts. Absent on every edge before this
-- migration, so each one rebuilds once at its first boot on it, under the class it already had.
create table edge_projection_class (
    exposure text not null
);
