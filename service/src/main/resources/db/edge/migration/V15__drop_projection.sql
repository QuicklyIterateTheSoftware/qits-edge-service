-- The DeploymentActive projection is gone (epic qits-2174, D2b): the edge serves the route table
-- qits-deployments pushes (edge_route_table, V13), and nothing reads or writes these four tables any
-- more. Named V14 in the epic's plan; V14 went to edge_private_edge_address (qits-2273) first.
drop table if exists edge_navigation_entry;
drop table if exists edge_endpoint;
drop table if exists edge_deployment_snapshot;
drop table if exists edge_projection_class;
