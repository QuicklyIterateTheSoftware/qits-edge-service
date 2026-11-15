-- The route table qits-deployments pushes (epic qits-2174, D2a): ONE row, the newest table this edge
-- accepted, whole. `edge` is public or private, `version` only ever goes up (a lower or equal push is
-- answered with the held version and changes nothing), `body` is the table exactly as it was
-- received, and `received_at` is when this edge stored it.
--
-- D2a only stores and reports it (PUT/GET /edge-route-table, RouteTableRoute): routing is still the
-- DeploymentActive projection's, and D2b builds the View from this row and drops that projection.
create table edge_route_table (
  id smallint primary key default 1 check (id = 1),
  edge text not null,
  version bigint not null,
  body jsonb not null,
  received_at timestamptz not null);
