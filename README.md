# qits-edge-platform-service

**The platform's L7 edge.** It binds the host's only public port, reads the `Host` name of every
request, and streams the request unchanged to the service that name selects. An environment's own
name is a door: it answers `GET /` with a redirect to the projects host and serves nothing else.

A small Quarkus 3 (Java 25) application that compiles to a **GraalVM native binary**. It holds no
browser session of its own: the browser gate reads idp's, caches what idp said, and forgets it.

## Deployment routes

`qits-deployments` builds **one route table per edge** and pushes it (epic qits-2174): `public` for
this edge's public variant, `private` for the private one — which the overlay edge serves too
(qits-2304, see *The overlay edge*). The table is the whole of what an edge
routes — the edge keeps no projection of deployment events any more. Each entry is one application in
one environment:

- **`endpoints`** — `path`, `upstreamHost`, `upstreamPort`, in declaration order. The **first** is the
  application's primary route: the segment its SPA is served under, and the upstream its own name
  resolves to;
- **`browserHost`** — the DNS label the service answers to, `ci` for `ci.dev.example.com`;
- **`apiDocsPath`** — where its browsable API document lives, under one of its own routes;
- **`navigation`** — placements, at most one per `(slot, label)` pair:
  `{"slot":"services.details","label":"CI","position":2}`. The slot vocabulary is closed and lives
  in `EdgeRoutes.SLOTS`. **One application may fill several entries of one slot**, so the slot
  alone was never the claim; the same pair twice is one row asked for twice and is refused. A repeated
  `position` is not: the document breaks that tie by label, whether the two entries belong to one
  application or to two.

The public table also lists **`withdrawn`** applications — the private ones, with the host they
answer to on the private edge. They route nothing here; `/main-navigation` names them to an admitted
caller, a request for one is a `421` (qits-2223), and the public DNS zone points them at the tunnel
(qits-2273). The private table's `withdrawn` is empty.

**A table is accepted whole or refused whole** (`RouteTables`): it must name this edge, carry a
positive `version`, and publish no path another application in that environment publishes, no host
another one publishes, no host that is not a DNS label or that is an environment name, no unknown
slot, no `(slot, label)` placement twice, no api-docs path outside the application's own routes, and
— on the public edge — no host that is also a platform app label (`PlatformApps`) pointing somewhere
else. The last one is a match rather than a ban: `registry` is both a platform vhost and the name
qits-artifacts publishes, and they are the same service exactly when the derived alias is the address
the deployer published. The reserved `landing` label is held to the same rule as every host: two
claimants refuse the table.

- **`PUT /edge-route-table`**, on every vhost, answered by the edge (`RouteTableRoute`). Only the
  deployer may push: a bearer JWT the edge accepts, carrying `qits:system`, whose `sub` (the idp
  client id) is `qits.edge.routes.publisher` (default `${QITS_ENVIRONMENT:dev}-qits-deployments`);
  anything else is a `403`. A valid table whose `version` is above the held one is stored in
  `edge_route_table` **and served from that moment**: `204`, and INFO `route table <edge> <old> ->
  <new>: <n> applications`. A version that is not above it is `200 {"held":<v>}` and changes
  nothing; an invalid table is `422 {"error":"<reason>"}`, ERROR `refused route table <version>:
  <reason>`, and the held table stays served.
- **`GET /edge-route-table`** answers the deployer or any `qits:system` service bearer with
  `{"edge":"public|private","version":<v>|null,"receivedAt":"…"|null}`. The deployer compares that
  version on every observation pass and pushes again when the edge is behind. Both tasks of a
  start-first update share the one `edge_route_table` row, and a push lands on whichever the ingress
  picked: a task serving an older table than the row loads the row on the next `GET` or repeated
  `PUT`, so it never stays behind a table its sibling stored.

The served copy is one immutable `View` in `EdgeRoutes`, swapped whole when a table is stored, so a
request reads one table or the next and never half of each, and never touches PostgreSQL on the
event loop. `/main-navigation` (GET/HEAD) is derived from the same view, carries `Cache-Control:
no-store`, and is never proxied.

The deployed edge needs two provisioned PostgreSQL resources: `edge` for the route table and the
edge's own projections (`QITS_RESOURCE_EDGE_URL`, `_USERNAME`, `_PASSWORD`) and `eventstream` for the
durable consumers' claim ledger (the variables named by the qits-eventstream library).
`qits.eventstream.enabled` is left on in deployments; development and test profiles turn it off
while Flyway still migrates both stores.

### Startup serves the cached table; the private edge also fetches

**The boot serves the stored table first** (`RouteTables.load`), before the HTTP server takes a
request and before anything is pushed or fetched: a restarted or redeployed edge routes exactly what
it routed when it stopped, and logs `route table <edge> <v> served from the cache`. A newer table
replaces it the moment it is stored.

**The private edge fetches at start** (`RouteTableFetch`): `GET
${qits.edge.routes.url}/deployments/api/edge-routes/private` with the `qits` oidc client's bearer,
on a virtual thread, retried after 1, 2, 4 … 30 s until the deployer answers, with one WARN `route
table fetch failed: <reason>`. `qits.edge.routes.url` (`http://<env>-qits-deployments:8080`) is
stated by `PrivateVariantConfigSource` alone, because both sit on `qits-private`. The overlay edge
(qits-2304) fetches the same way and the same table, `private`: the fetch runs for any edge whose
class is `private` and that states the url, and the variant source states it for both. **The public edge
never fetches**: it sits on `qits-net`, the deployer on `qits-private`, and nothing bridges them, so
it serves its cached table and waits for a push — which the deployer sends after every public-edge
deploy, and on each observation pass while the edge reports an older version.

**Readiness** (`/q/health/ready`, `EdgeRoutes.ready`, reported as `route-table: <version|none>`):

- **the private edge** is ready once a table is loaded — from its cache at boot, or from the fetch or
  a push on an empty database. Until then every request but the edge's own `/q` (on its own names —
  see below) and `/main-navigation` answers `503 Retry-After: 1`, and the tunnel DNS answers
  SERVFAIL, so a private name is never sent anywhere on the strength of nothing.
- **the public edge** is ready with a table, **or with none ever received**. The deployer pushes its
  first table only once the edge reports healthy, so a public edge that waited for that push would
  wait forever. A cold start — an empty database — therefore serves the **platform app labels**
  (`PlatformApps`: `registry`, `mirror`, `idp`) from code, every other name as the `404`
  it is until the first push a few seconds later, and an empty `/main-navigation`.

The platform labels are code for a second reason (qits-1403): the edge's own project and peer
catch-ups dial qits-events, and qits-events fetches the keys it checks that call's token with from
`https://idp.qits.<domain>/idp/jwks` — through the edge — so `idp` can never wait for anything the
edge has not got yet. `HostRecords` decides nothing until a table is actually loaded: a public
edge that has never received one knows of no host, and deleting their DNS records on that
word would be a wrong answer published to the internet.

**Swarm's healthcheck is liveness, `/q/health/live`, not readiness** (qits-2160). A not-ready edge
must still receive traffic: the barrier lives inside the router, not in swarm's routing mesh.

The durable consumer `edge-project-sans` rebuilds the project-slug projection from qits-events at
every start (`ProjectSansBootstrap`, retried after `qits.edge.catchup.retry`), and it is
deliberately not part of readiness: holding the whole edge down would refuse every request over a
projection that decides two spellings out of four. It catches up
in the background and, once it reaches the head, requests one reconcile — which closes the boot race
where the certificate manager's own startup reconcile runs before any slug is known.

That set is a **routing** input as well as a certificate one, though, so being outside the barrier
is not being outside the problem. While it is behind, a name whose reading could still change once
more slugs arrive — a would-be project door, a would-be four-label name, the short spelling of
either — is answered `503 Retry-After: 1` instead of a 404 that would name the wrong host. Nothing
else is: a configured vhost, a published host, the apex and an environment's own door never consult
the set, and `edge_project` is persisted, so a restarted edge whose rows survived serves normally
throughout. The window is a genuinely behind projection — a wiped database, or a first boot.

**The platform's own labels on the platform's own project are the exception** (qits-1403):
`<label>.qits.<domain>`, for a `PlatformApps` label, is that application in the default environment
even while `qits` has not been read yet. On a fresh database `qits` is unknown until this consumer
has read qits-projects' log through qits-events, and qits-events checks that read's token against
keys from `idp.qits.<domain>` — so answering idp's name with the unknown-project 503 would be a boot
that waits for itself. Only the env-less spelling is read this way (whether `qits` has environments
is exactly what is not known yet), and every other name in an unread `qits` still waits.

**This is the only proxy tier there is.** There was a second one — `qits-gateway`, one per
environment, demultiplexing services by PATH inside its tier — and it is gone: every service is
reached on a name of its own now, so a per-environment hop would have been a second address for
something that already has one. What that repository knew came here with it, and the two places it
shows are `EdgeCacheControl` (a port of its class, restored after the retirement dropped it) and
the surefire split in `pom.xml` (its workaround for a WebSocket-after-restart harness bug).
Historical mentions of it below are exactly that; nothing on the platform runs one.

```
                          ┌────────────────────────────────────────┐
  client ──:8080──▶       │ qits-edge  (the only                   │
  Host: registry.dev.…    │ published port on the host, and the    │
                          │ only proxy tier)                       │
                          │   Host name → service · idp · /q       │
                          └──┬──────────────┬──────────────┬───────┘
                             │ docker networks, nothing published
       dev's registry        │  prod's registry            │  dev's ci, published
        ┌────────────────────▼┐  ┌──────────▼─────────┐  ┌─▼──────────────────┐
        │ dev-qits-artifacts  │  │ prod-qits-artifacts│  │ dev-qits-ci        │
        │  :8080              │  │  :8080             │  │  :8080             │
        └─────────────────────┘  └────────────────────┘  └────────────────────┘
         platform app label, in code                      route table
```

## The routing model

A `Host` name selects an environment, and optionally a project and an application inside it:

| Host                        | Goes to                                             |
| --------------------------- | --------------------------------------------------- |
| `prod.example.com`          | the `prod` **door** — `$env.$domain`, and it serves nothing |
| `registry.prod.example.com` | `prod`'s `registry` upstream — `$app.$env.$domain`  |
| `registry.dev.example.com`  | `dev`'s `registry` upstream — same entry, other tier |
| `acme.prod.example.com`     | the `acme` **project's door** in `prod` — `$project.$env.$domain` |
| `ci.acme.prod.example.com` | ci, for `acme`, in `prod` — `$app.$project.$env.$domain` |
| `example.com`               | the **default** environment's door — the apex, and the one name with no environment label |
| `localhost`, `127.0.0.1`, `[::1]` | the **default** door — one label, or an address |
| no `Host` at all            | the **default** door                                |
| `registry.dev.localhost`, `githost.dev.internal`, `mirror.localhost` | **404** — a name outside the domain; the machine-name reading is retired (qits-1433) |
| `ci.dev.example.com`, published by a deployment | `dev`'s ci service — its SPA at `/` and every route it owns |
| `mirror.dev.example.com`, `mirror` unconfigured and unpublished | **404** — see below |
| `registry.example.com`, `acme.example.com`, `ci.acme.example.com`, `staging.example.com` | **404** naming the explicit spelling — see below |

Only the **first three labels** are read; everything to the right of them is the **stated domain**,
`qits.edge.domain` (`QITS_DOMAIN`). It is stated rather than derived because it cannot be derived —
`example.co.uk` is two labels of domain and `localhost` is one — and it is stated **once**: the
certificate, the return authorities and the canonical origin are all this value. An environment name at position 1
wins over everything, so `staging.prod.example.com` is *application `staging` in environment
`prod`* — an application may be called anything, whereas a domain whose first label happens to be an
environment name is a coincidence nobody arranges. At position 0 a configured **service** beats a
project of the same name; the platform refuses both collisions on its own side rather than relying
on either tie-break.

**The environment label is not optional.** It used to be, for the default environment: the apex is
that environment's door, so `ci.example.com` was its ci service. The **project tier** ended that.
With a project label in the middle, `ci.acme.example.com` and `ci.acme.prod.example.com`
would be one place whose middle label is read as a project in one spelling and an environment in the
other, and `acme.example.com` would be a project door or an application vhost depending on which set
a label happened to be in. So a name of more than one label that does not state its environment is
**404, with the spelling that works in the body**, and the miss is logged at INFO once per request so
the callers that have not moved can be found. There is no redirect, deliberately: a short name is a
bookmark, a link or a hard-coded string, and a 302 would keep every one of them working and
invisible.

**The apex is a door like the others, and the one that can compose nothing.** `example.com` is the
name that IS the stated domain, so it is recognised positionally like every other. It carries no
project label, and every application address does — so it composes nothing of its own and falls back
to the **canonical origin**, which is the platform project's own door (`https://qits.example.com`),
derived from the stated domain. Where this edge knows that project, that tier is what the apex
composes on; where it does not, `GET /` redirects nowhere and it answers the grammar instead.

**A name outside the stated domain is a 404, and nothing reads it** (qits-1433). The edge used
to read such a name as a MACHINE NAME — `<app>[.<env>].<machine-suffix>`, so
`registry.<env>.localhost`, `mirror.<env>.localhost`, `githost.<env>.localhost` and
`githost.<env>.internal` reached the platform's registry, mirror and git host — because docker,
maven and git once dialled those aliases of this container. Nothing does now: every image
reference, every maven resolution and every clone uses the public names
(`registry.qits.<domain>` and so on), and the bootstrap pushes its seed images there once the edge
holds that name's real certificate. So the reading is gone, and such a name is answered as any
name outside the domain is: a flat **404** that neither the platform app labels nor the deployment
projection can rescue. A name of a single label (`localhost`, this container's own service alias)
carries no position at all and is a door, as an address literal and a missing `Host` are.

**A project's slug is a label the edge learns from the event stream.** `EdgeProjects` projects
qits-projects' `ProjectCreated`/`ProjectDeleted` into the set that both certificate names and these
two readings are built from, so a project created on Tuesday is routable on Tuesday. A slug nobody
has created is just another label, and its name states no environment.

**An app-shaped name is 404 rather than a fall-through, and it is deliberate.** A first label in
front of a *known* environment — or in front of a known project in front of one — was aimed at a
service, and services are the names this edge authenticates, so falling it through to anything else
would hand exactly those requests to a hop that does not check them. An unconfigured, unpublished
app label is therefore a **404**, and the answer names the environment and project it did read.
`SessionlessWallIT` and the door story are what pin it.

**A project's own name is a door too.** `acme.dev.example.com` answers `GET /` with the same
redirect the environment door gives — to qits-projects' host — and 404s every other path, naming
`<app>.acme.dev.example.com`. The one thing decided outside `HostEnvironments` is which of the two a
single label in front of an environment is: a **published service claims it first**, and the door is
what is left, because a slug and a published browser host are the same shape and only the deployment
projection knows the second set.

**A name reaches a service two ways.** The platform app labels are the first — `registry`,
`mirror` and `idp` under the platform project (`githost` left them in qits-2743: it is private, so
only the route table serves it), and the auth attributes that go with them,
in code (`PlatformApps`). The route table is the other: a service
publishes the name it answers to, and that name then serves its SPA at `/` and every wire route it
owns. The two are the same kind of vhost, so everything below treats them alike.

**The hostname alone picks the application.** On a service's name only that service's routes are
routed, longest prefix first among them — `mirror.dev` resolves between the mirror's own `/mirror`
and `/v2`, `githost.dev/git/…` reaches the git host. Another application's route, primary or
secondary, means nothing there: it falls to the host's own service exactly like a path nobody
declared, so `ci.dev/projects/api/…` reaches ci, `mirror.dev/artifacts/…` reaches the mirror, and
`ci.dev/git/…` reaches ci and 404s there. A
bare `/` is the same rule: it is the catch-all of whichever application declared it, so on a
service's own name it is that service. A cross-application call from an SPA is therefore a
cross-origin call to the other application's own name, with the session cookie, and the edge
answers its CORS.

**The edge owns CORS** on every service host (`EdgeCors`). A request whose
`Origin` is under the stated domain — `https://<anything, any depth>.<QITS_DOMAIN>` or the apex
`https://<QITS_DOMAIN>`, and locally `http://<…>.localhost:<edge port>` — gets that origin echoed in
`Access-Control-Allow-Origin` with `Access-Control-Allow-Credentials: true` and a short
`Access-Control-Expose-Headers` list. The rule is the same domain anchor the login return is checked
against, and deliberately not narrowed to the hosts the route table publishes. A foreign origin gets
no `Access-Control-Allow-Origin`. A preflight (`OPTIONS` with `Origin` and
`Access-Control-Request-Method`) from an admitted origin is answered `204` by the edge itself,
before the gate and without touching any session, echoing the requested method and headers with
`Access-Control-Max-Age: 600`. Every `Access-Control-*` header an upstream sends is removed, and
every service-host response carries `Vary: Origin`.

Nothing in a request ever contributes a character to an address: a `Host` selects an *index into a
fixed list* or a *row of the route table*, which is the whole SSRF guard.

### The environment vhost is a door, and a door serves nothing

Every service is on its own name, so the environment's own name routes nothing: no `/<seg>`, no
`/<seg>/api`, no `/v2`, no `/git`, no `/idp/`. A second address for something that already has one
is a second origin, a second cookie scope and a second thing to keep in step.

What the door answers is:

- `GET /` (and `HEAD /`) — `302` to qits-projects' host once the route table names one: the `system`
  placement qits-projects publishes, or a host called `projects`. An anonymous visitor lands on the
  login through the host that owns it. `404` while no such host is known;
- `/main-navigation` and `/upstream-pools` — the edge's own surfaces. `/q` is NOT one of them on a
  door (qits-2222): the edge answers it only on its own names — no `Host`, an address, a single
  label such as `localhost` or the wire alias, the bare domain — so a door answers it `404` like
  any other path;
- everything else — `404` in plain text, naming `<app>.<authority>`, logged at INFO so anything
  still dialling the door can be found.

There is no gate on the door, because there is nothing behind it to gate: a session cookie and a
machine token are both answered `404` like any other request.

### The exposure class, and the listener in front of it

One image runs as two edges (epic qits-1976) — three since qits-2304, the third being a second
`private` one, *The overlay edge*. **`qits.edge.exposure`** (`QITS_EDGE_EXPOSURE`) is
`public` — the default — or `private`, the second deployment behind the WireGuard tunnel. It decides
which route table the edge accepts and serves: qits-deployments builds one per edge (epic qits-2174)
from what each deployment declares, and a table named for the other edge is refused.

| deployment declares | public table | private table |
| --- | --- | --- |
| `public`, or nothing | served | — |
| `private` | `withdrawn` | served |
| `both` | served | served |

**Navigation follows on its own**, because it is built from the table each edge serves. A deployment
that changes class moves from one table to the other at the deployer's next build, and each edge
serves the change the moment its new table is stored.

**A withdrawal keeps the name, and nothing that routes** (qits-2061). The public table lists each
private application's host and exposure under `withdrawn`. Neither is a vhost, a route, a host claim
or a certificate SAN, and `EdgeRoutes.applicationHosts` does not list it. `/main-navigation` reads
them, through `EdgeRoutes.withdrawnHosts` — see below — and so does one refusal:

**A private deployment's name on the public edge is `421 Misdirected Request`** (qits-2223), not
the 404 an unknown label gets. Since qits-2273 the public zone answers a private name with the
tunnel address too (see [Host records in public DNS](#host-records-in-public-dns-qits-2273-qits-2292)),
so a request only lands here on an OLD or FILTERED answer: a resolver that cached the wildcard's
public address from before the record existed, or one whose DNS-rebind protection dropped the
`100.x` answer and fell back. The name does exist — on the other edge — so the
answer says so: plain text naming the private edge and `100.96.0.1`, no `Retry-After`, logged at
INFO, and on HTTP/1.x `Connection: close` with the connection actually closed, so a keep-alive
client's next request dials and resolves again rather than reusing a socket to the wrong edge
(RFC 9110 §15.5.20: another connection may succeed). On HTTP/2 the status alone is sent and the
connection kept — a connection header is malformed there, and the connection carries other
streams. Only a public edge answers it, and only for a withdrawal whose declared exposure is
`private`; a label nobody publishes or withdraws keeps the 404. `EdgeRouter.reach`, and so the
tunnel DNS, is unchanged.

**The platform app labels are public-edge code.** On a private edge `registry`, `mirror`
and `idp` are ordinary unknown names — a 404 unless a private or `both` deployment publishes one —
and the qits-1403 exception that answers them with no table does not apply: the private edge
is off `qits-net` (qits-2174) and dials qits-events and idp by their PUBLIC names, over the apex the
public edge routes, and never waits for itself.

**The listener allow-list.** `qits.edge.listen.allowed-local-cidrs` lists the LOCAL addresses a
connection may arrive on — the interface it reached, not the caller. Empty, the default, accepts
everything. Set, a connection outside every block is closed with nothing written back, by
`EdgeListener`: the first route on the router (`Integer.MIN_VALUE`), ahead of `/q`, the edge's
catch-all and every WebSocket upgrade it splices. Quarkus exposes no connection handler, so this is
the earliest hook there is: the TLS handshake and the request head have been read, nothing else has.
`127.0.0.0/8` and `::1` are always admitted, because the deployer's health check is `curl
http://localhost…` inside the container, and an IPv4-mapped address (`::ffff:100.96.0.2`) is read
as its IPv4 address. A refusal is logged at DEBUG. The private edge states
`100.96.0.0/24,10.231.0.0/20` — the tunnel and the `qits-private` overlay, the only two networks this
edge sits on (qits-2174) — which is what keeps anything else from walking in. The overlay edge has
no tunnel and states `10.231.0.0/20` alone.

**The peer log.** On a private edge every request this process answers or proxies writes one INFO
line on the logger `eu.wohlben.qits.edge.peer`:

    peer=proof-workspace ip=100.96.0.2 method=GET host=ci.dev.acme.example.com path=/ci/api/runs status=200

`peer` is the name a tunnel address is configured under (see *The private edge* below), `overlay`
for a remote inside `qits.edge.peer-log.overlay-cidr`, and `unknown` otherwise. The address is the
socket's, never `X-Forwarded-For`, which the edge documents as untrusted. Values off the wire are
percent-encoded where they could end the line or forge a field. A public edge writes none.

**The forwarding guard.** On a private edge with a tunnel the readiness check `edge forwarding guard`
reads `/proc/sys/net/ipv4/ip_forward`: anything but `0` is DOWN with the reason, because the tunnel
must never route a peer onward. A file that cannot be read is UP with a note. A public edge reports
UP without checking, and so does the overlay edge (qits-2304): it is private but terminates no
tunnel (`qits.edge.wireguard.enabled=false`), declares no `sysctls`, and its inherited
`ip_forward=1` is every other `qits-private` container's.

## The private edge

`qits-edge-private` is a second application from this same image and release:
`.config/qits/deployments.yml` says `variants: private`, and `.config/qits/deployments.private.yml`
declares it — `exposure: private`, its own databases, `cap_add: NET_ADMIN`, `publish_mode: host`
(`update_order: stop-first`, because a host-mode port has one binder), `47812:47812/udp` published
and nothing else, its own `private:wireguard` volume and the public edge's
`qits-edge-letsencrypt` read-only.

**`qits-private` alone, not `qits-net` too** (owner ruling, epic qits-2174): nothing private and
nothing a private service uses sits on `qits-net`. `qits-private` is an attachable overlay without
`--internal`, so egress still works through `docker_gwbridge`; idp, qits-events and
qits-observability are dialled by their PUBLIC names rather than the internal `<env>-qits-<app>:8080`
alias (see `PrivateVariantConfigSource` below), and this application's own databases live on the
private Postgres, `qits-oci-postgresql-private`, moved there by qits-deployments when the exposure
changed (qits-2175).

**No settings: `QITS_VARIANT=private` is the whole configuration.** qits-deployments puts that
variable in a variant's container and in nothing else, and `PrivateVariantConfigSource` — a
MicroProfile `ConfigSource` registered in `META-INF/services`, ordinal 275 — derives everything from
it. Above `application.properties` (250, including any `%prod` line there) and below environment
variables (300), so one value can still be overridden by hand. It reads the variable at every call,
never at build or static-init time, so the native image cannot bake the build's (absent) value in.
Without the variable it is empty and the edge is the public one, unchanged.

| key | private value |
| --- | --- |
| `qits.edge.exposure` | `private` |
| `qits.edge.wireguard.enabled` | `true` |
| `qits.edge.wireguard.listen-port` | `47812` |
| `qits.edge.dns.enabled` | `true` |
| `qits.edge.dns.overlay-cidr` | `10.231.0.0/20` — the tunnel DNS responder answers on `qits-private` too |
| `qits.edge.listen.allowed-local-cidrs` | `100.96.0.0/24,10.231.0.0/20` |
| `qits.edge.acme.enabled` | `false` — it orders nothing, it serves the public edge's certificate |
| `qits.edge.idp.dial-url` | `https://idp.qits.<domain>/idp` — idp's public name; this edge is off `qits-net` |
| `qits.events.url` | `https://events.qits.<domain>` — qits-events' public name, same reason |
| `qits.observability.url` | `https://observability.qits.<domain>` — qits-observability's public name, same reason; `qits.telemetry.endpoint` derives from this key, so the exporter follows it there too, now carrying the `qits` oidc client's bearer (qits-2188) |
| `qits.edge.routes.url` | `http://<env>-qits-deployments:8080` — where it fetches its route table at start; both are on `qits-private` (qits-2174, see *The pushed route table*) |
| `quarkus.http.port` / `quarkus.http.ssl-port` | `80` / `443` — a peer dials `https://<app>.qits.<domain>` with no port |
| `quarkus.tls.key-store.pem.acme.cert` / `.key` | `/work/.letsencrypt/current/lets-encrypt.crt` / `.key` |
| `quarkus.tls.reload-period` | `1m` — follows the public edge's renewals |
| `quarkus.shutdown.timeout` | `30s` — stop-first, so the drain is downtime for every private name; the public edge's 9m30s is for a start-first update (qits-2223) |

The TLS keys are the public edge's own deployment settings (`QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT`,
`…_KEY`, `QUARKUS_TLS_RELOAD_PERIOD`) in dotted form: the default TLS configuration's PEM pair named
`acme`, which the HTTP server uses because it names no `quarkus.http.tls-configuration-name`.

**Addresses.** The tunnel is `100.96.0.0/24`; the edge is `100.96.0.1/24` on `wg0`, MTU 1380; the
proof peer is `100.96.0.2`. WireGuard listens on UDP **47812**, host mode on `0.0.0.0`. The tunnel
DNS responder answers on `100.96.0.1:53` (UDP and TCP) for the zone `qits.edge.domain`: a name this
edge routes gets `100.96.0.1`, every other in-zone name the public edge's address — the zone apex's
A record, looked up through the container's resolver at start and every five minutes
(`qits.edge.dns.public-address` overrides it), and SERVFAIL while none is known. Private upstreams
are reached on `qits-private`, `10.231.0.0/20`.

**A name outside the zone is forwarded on the tunnel** (qits-2262). A WireGuard phone app sets one
`DNS = 100.96.0.1` for the whole device — there is no split DNS — so the tunnel socket passes any
other name to the container's own resolver, the `nameserver` lines of `/etc/resolv.conf`
(`qits.edge.dns.upstream`, a comma list of `address[:port]`, overrides them), and relays the
upstream's message as it came: its records, flags and rcode, NXDOMAIN included, under the client's
id. UDP is forwarded over UDP and TCP over TCP. An upstream that fails, or does not answer within
`qits.edge.dns.upstream-timeout` (`2s`), is SERVFAIL; one that is this responder's own socket is
never used. Forwarding does not wait for the routes view: an outside name owes nothing to it, and a
peer keeps the internet while the edge catches up. With no usable upstream such names are REFUSED,
logged as a WARN at start. The start line says which:

    … ttl 30s; other names on the tunnel forwarded to /127.0.0.11:53

A workspace that names `100.96.0.1` first now gets its outside names answered here too, rather than
by its next resolver.

**The responder answers the overlay too** (qits-1991). A host container on `qits-private` — a
DIRECT admin workspace — names the private edge's swarm VIP there as its first `--dns` (until
qits-deployments names the overlay edge's instead, see *The overlay edge*), and
`100.96.0.1` is not on that network. So `qits.edge.dns.overlay-cidr` (`10.231.0.0/20` on the
private edge, unset and inert on the public one) makes `TunnelDns` read this container's IPv4
addresses at start and bind one more UDP and TCP socket on port 53 for each address inside the
block — the edge's task address on the overlay — beside the tunnel's:

    tunnel DNS responder on 100.96.0.1:53 and overlay 10.231.0.5:53 (UDP and TCP): zone …

One socket per address rather than `0.0.0.0`, because netty reads no `IP_PKTINFO`: a UDP socket on
the wildcard cannot say which address a datagram was sent to, and a private name would be answered
with the tunnel's `100.96.0.1`, which the overlay cannot route. Bound to one address, a routed name
is answered with that address, the one the caller asked. The rules are otherwise the tunnel's — but
a name outside the zone is REFUSED, never forwarded, which sends docker's embedded resolver on to
the container's next `--dns`. The addresses are read once: swarm attaches a task's networks before the entrypoint,
and a task's address changes only with the task. An address that cannot be bound is an ERROR and is
skipped, a block with none of this container's addresses a WARN; either way the tunnel is served.

**Root brings up `wg0`, and nothing else runs as root.** The image starts as root and
`docker/entrypoint.sh` hands every start to `qits-edge-init` (`docker/edge-init`, Go, built in the
Dockerfile's `wireguard` stage beside wireguard-go `0.0.20250522`). With `QITS_VARIANT=private` it
runs `qits-edge-init --wireguard`, which:

1. refuses to start (exit 1, the reason on stderr) unless `/proc/sys/net/ipv4/ip_forward` is `0` —
   the tunnel must never route a peer onward;
2. creates `/dev/net/tun` (`c 10 200`; swarm services have no `--device`);
3. starts `wireguard-go wg0`, which daemonises and stays root, holding the TUN, and waits up to 10 s
   for `/var/run/wireguard/wg0.sock`;
4. gives `wg0` its address (`QITS_EDGE_WIREGUARD_ADDRESS`, default `100.96.0.1/24`), MTU
   (`QITS_EDGE_WIREGUARD_MTU`, default `1380`) and IFF_UP by ioctl;
5. chowns the UAPI socket and `/work/.wireguard` to `1001:0`.

Then, on either edge, it drops to uid 1001, gid 0, no supplementary groups — and so no capabilities
— and execs the edge with the image's historical flags. The public edge therefore runs exactly as it
did under `USER 1001`. The private edge needs root only for the device: everything after it is the
UAPI socket, which uid 1001 now owns.

**The edge configures `wg0` itself** (`WireguardDevice`). Its private key is
`qits.edge.wireguard.key-file` (`/work/.wireguard/private.key`, on the private volume), generated on
first start (X25519, base64 of the raw 32 bytes, mode 0600) and never overwritten; the public key a
peer configures is logged at INFO as `wireguard public key: <base64>` on every start. At start it
sends one UAPI `set=1` — the key, the listen port and `replace_peers=true`, with no peers — and
readiness (`edge wireguard device`) is DOWN until that is answered `errno=0`, while the peers have
not been given to the device (`peers not caught up`, or the failure of the last operation), and
whenever `get=1` does not answer, reports another listen port, or `wg0` is not up.

**The peers come from qits-network-access** (epic qits-1977). It publishes `PeerRegistered` and
`PeerRevoked`; `PeerEventsSubscriber` (consumer `edge-wireguard-peers`, replayed from the epoch,
frames of this edge's own `QITS_ENVIRONMENT` only) keeps the newest frame per peer in
`edge_wireguard_peer`, a revoke as a tombstone, so a replayed register can never revive a revoked
peer. `LivePeers` catches that consumer up to the log head at start and then sends ONE full set
(`replace_peers=true`) of the registered active peers plus any stated by hand in
`qits.edge.wireguard.peers` (`name:base64PublicKey:tunnelIp`, comma separated, an operator
override); after that every frame is one UAPI operation — `public_key` + `replace_allowed_ips` +
its `/32` to add, `public_key` + `remove=true` to remove — logged at INFO as `wireguard peer added:
<name> <tunnelIp>` / `wireguard peer removed: <name> <tunnelIp> (<reason>)`, never with a key. An
operation that is not answered `errno=0` is a WARN and readiness DOWN, and the full set is resent
every 30 s until it is taken. The peer log names a caller from the same live set. The public edge
keeps the same rows and applies nothing. The static-peers file of qits-1976 is gone.

**The edge announces itself.** Once `wg0` took its startup set and the tunnel DNS knows the public
address, `PrivateEdgeAnnouncer` publishes `PrivateEdgeAnnounced` — `application`
(`QITS_APPLICATION`), `publicKey`, `publicAddress`, `listenPort`, `tunnelAddress`, `tunnelCidr`,
`dnsAddress`, `mtu`, `domain` — and again whenever the public address, looked up every five minutes,
changes. qits-network-access builds every peer's config from it. The address and MTU are
`qits.edge.wireguard.address` (`QITS_EDGE_WIREGUARD_ADDRESS`, `100.96.0.1/24`) and
`qits.edge.wireguard.mtu` (`QITS_EDGE_WIREGUARD_MTU`, `1380`), the same variables and defaults the
init helper gives `wg0`; `WireguardDefaultsTest` holds the two sets of defaults equal. The outcome
is one INFO line, `private edge announced: key <publicKey> at <publicAddress>:<port>`, once the bus
took the event; while it cannot announce, one line per change of reason says what is missing (no
usable settings, no key yet, no public address yet). Publishing is the edge's only write to
qits-events, and it needs `EventEnvelope` and `CanonicalJson$QitsEventMixin` in
`EventWireReflection`: without them the native binary cannot serialize the PUT body, and the library
reports that only at DEBUG while the outbox retries it forever (qits-edge-private 2026.1110.173435).
**Only an edge with a tunnel announces**: the gate is `qits.edge.wireguard.enabled`, so the overlay
edge — private, no tunnel — never publishes `PrivateEdgeAnnounced`, whatever it is told, and says
`private edge not announced yet: wireguard is off` once (qits-2304).

## The overlay edge

`qits-edge-overlay` (qits-2304) is the third application from this image and release:
`.config/qits/deployments.yml` says `variants: private, overlay`, and
`.config/qits/deployments.overlay.yml` declares it — `exposure: private`, its own databases
(`qits_edge_overlay`, `qits_edge_overlay_eventstream`) and idp client, the public edge's
`qits-edge-letsencrypt` read-only, and nothing published, no capability, no sysctl and no
`update_order`, so **start-first**.

**Three edges, three jobs.**

| edge | serves | update |
| --- | --- | --- |
| `qits-edge` | the public internet, on the node's published ports; the `public` table | start-first (ingress) |
| `qits-edge-private` | WireGuard peers on `100.96.0.1` — DNS, HTTP, forwarding of outside names — and its own `qits-private` sockets; the `private` table | stop-first (host-mode UDP port) |
| `qits-edge-overlay` | containers on `qits-private` — DNS and HTTP on its overlay address and VIP; the `private` table | start-first |

**Why it exists.** The private edge used to be the overlay clients' only private-aware resolver, and
it must update stop-first: every redeploy left them for 90-180 s with no resolver that knew a private
name, so docker's embedded resolver fell through to public DNS, which answers a private name with
`100.96.0.1` — unroutable on the overlay. The overlay edge holds no host port, so its successor starts
while its predecessor still answers, and the overlay never goes without. qits-edge-private is
unchanged: it keeps its tunnel and its own overlay sockets, and **tunnel peers keep using it** — the
overlay edge has no `wg0`, no `100.96.0.1`, and announces nothing.

**Which clients use which.** A `private` service's container gets the private edge's VIP as its first
`--dns` from qits-deployments today (qits-2117); moving that to the overlay edge's VIP is the
deployer's half of qits-2304. HTTP to a private name follows the DNS answer, so a container whose
first resolver is the overlay edge is also served by it.

**`QITS_VARIANT=overlay` is the whole configuration.** `PrivateVariantConfigSource` answers per
variant; the overlay's values are the private table above but for these:

| key | overlay value |
| --- | --- |
| `qits.edge.wireguard.enabled` | `false` — no `wg0`, no UAPI, no announcement, no forwarding guard |
| `qits.edge.wireguard.listen-port` | not stated: nothing listens |
| `qits.edge.listen.allowed-local-cidrs` | `10.231.0.0/20` — the overlay alone |
| `quarkus.shutdown.timeout` | not stated: `application.properties`' 9m30s stands, start-first makes a long drain free; `stop_grace_period: 10m` covers it and the 10s linger |
| `qits.edge.dns.service-alias` | `<env>-<QITS_APPLICATION>`, `dev-qits-edge-overlay` — its own wire alias, see below |

The entrypoint brings `wg0` up for `private` alone, so `overlay` starts as the public edge does.

**DNS on the overlay alone.** With wireguard off `TunnelDns` binds no tunnel socket: the overlay
addresses (`qits.edge.dns.overlay-cidr`) are bound and decide alone — none bound, nothing runs, said
at ERROR. The answers are the overlay sockets' everywhere: a name outside the zone REFUSED (never
forwarded), an unrouted in-zone name the public address, SERVFAIL until the routes view is ready.

    overlay DNS responder on 10.231.0.7:53 (UDP and TCP), no tunnel: zone …, routed names the VIP of dev-qits-edge-overlay, looked up, …

**A routed name is the service VIP, not the task.** Start-first replaces the task a caller asked
while the caller still holds its answer; the service's VIP on `qits-private` outlives every task and
swarm balances it onto whichever one serves. So the overlay edge resolves its own wire alias
(`qits.edge.dns.service-alias`) through its container's resolver at start and every five minutes, off
the event loop, and answers a routed name with that address — only an IPv4 address inside
`10.231.0.0/20` is accepted, a failed lookup keeps the last one, and until one is known the address
the query arrived on is the answer, as before. qits-edge-private states no alias and keeps answering
its task address on the overlay and `100.96.0.1` on the tunnel.

### Host records in public DNS (qits-2273, qits-2292)

**The edge owns every public name.** Until qits-2292 the public names resolved only because a person
had typed wildcards into the zone (`@`, `*`, `*.dev`, `*.*`, `*.*.*`, `*.*.*.*`, and since qits-2280
`qits` and `*.qits`). Nothing in the platform wrote them, so a fresh zone resolved nothing, and a
made-up name such as `x.qits.wohlben.eu` got a TLS handshake and a 404. Worse, one exact name below
a wildcard turns its parent into an empty non-terminal the wildcard stops answering for (RFC 4592):
that mix caused the qits-2273 and qits-2280 outages, and the "shadow" records written to paper over
it are gone. Now every host has an exact record, the edge writes no wildcard, and an unknown name is
NXDOMAIN.

**What is written** (`HostRecords`), in the public zone (`QITS_DOMAIN`, e.g. `wohlben.eu`), TTL 60:

| Name | Type | Value | Label |
| --- | --- | --- | --- |
| `node.<domain>` | `A` (+ `AAAA`) | the node address, `QITS_EDGE_DNS_NODE_ADDRESS` | `qits-exposure=public` |
| `<domain>` (the apex, `@`) | `A` (+ `AAAA`) | the node address — an apex cannot hold a CNAME | `qits-exposure=public` |
| every public host, e.g. `ci.dev.qits.<domain>` | `CNAME` | `node.<domain>.` | `qits-exposure=public` |
| every project door `<project>.<domain>`, and `<env>.<project>.<domain>` for a project with environments | `CNAME` | `node.<domain>.` | `qits-exposure=public` |
| every private host, e.g. `projects.qits.<domain>` | `A` | the tunnel address, `100.96.0.1` | `qits-exposure=private` |

The public hosts are the ones the served route table publishes (`EdgeRoutes.applicationHosts`) plus
the `PlatformApps` labels the edge answers from code (`idp`, `registry`, `mirror`),
composed for every served environment and every known project exactly as the navigation document
composes them (`EnvironmentAuthority.hostOrigin`): `<host>.<project>.<domain>` for an env-less
project in the default environment, `<host>.<env>.<project>.<domain>` for one with environments, and
`landing` is the door itself. The private hosts are composed the same way from the `private`
withdrawals (`EdgeRoutes.withdrawnHosts`); their value is the newest
`PrivateEdgeAnnounced.tunnelAddress` (any `/prefix` stripped), projected by
`PrivateEdgeAddressSubscriber` (consumer `edge-private-edge-address`, replayed from the epoch) into
`edge_private_edge_address` (V14); with several private edges the newest announcement overall wins.
A renamed host needs no special code: the old name leaves the route table and its record is deleted.
The DNS-01 TXT at `_acme-challenge.<name>` is a different name and sits beside a CNAME untouched.

**The node address is the bootstrap's input.** `QITS_EDGE_DNS_NODE_ADDRESS`
(`qits.edge.dns.node-address`) is a comma-separated list of IPv4 and/or IPv6 literals —
`46.224.171.33` on the estate — and `qits-bootstrap-cli` sets it from its `--public-ip` through the
deployment settings (qits-2293). It is never looked up or derived. So a new domain needs exactly two
things: the Hetzner token (held by qits-egress, or the seed edge's swarm secret on a cold boot) and the
node address; the edge writes everything else. Without an address the edge writes and deletes no public record and says so once at INFO; the
private records do not need it. Likewise without a tunnel address the private records are neither
written nor deleted, and the public ones still are. A project with the slug `node` would make its
door a CNAME to itself, so while one exists no node record and no CNAME is written, with a WARN.

**Order is the safety.** Every lookup comes before any write: the owned records are listed once per
pass (every page, `type` A, AAAA and CNAME, `label_selector=qits-exposure in (private,public)`), and a
name is looked up only when it is missing from that list — Hetzner allows 3600 requests an hour per
token. A pass that cannot list or read the zone writes and deletes nothing. Then the node record; a
CNAME only once the node is in place; the apex. Deletes come only after every create and set. A
**type switch** at one name — an application that turns from private to public or back, or the old
`qits` door `A` that is now a CNAME — is a delete and a create back to back in the same pass, logged:
Hetzner refuses a CNAME beside an `A`, and the zone's negative TTL is an hour, so the gap must never
be split across passes. A failed create is retried on the next trigger.

**What it never touches.** Only RRSets labelled `qits-exposure` (`public` or `private`) are written or
deleted; the label is checked again on each one, whatever the API's selector returned. Before a
create the name is looked up, and an UNLABELLED record of a conflicting type there — a record a person
made — is a WARN and the name is skipped. The migration below is the one exception.

**The migration off the hand-made records** runs inside the normal pass and is idempotent:

- owned shadow records (labelled `private`, e.g. `qits` and `*.qits`) are no longer wanted: a shadow at
  a door is switched to its CNAME, the rest deleted — a wildcard, or anything with names below it,
  only once every desired name below it is in place;
- an unlabelled `A` at a name that is now a CNAME (the hand-made `qits` of qits-2280) is switched,
  and an unlabelled `*.<project>` wildcard directly under a project door (`*.qits`) is deleted, each
  only once every desired name below it is in place;
- the hand-made apex `A` is adopted — its label set to `public` — when its values are the node
  address, and left alone with a WARN when they are not;
- `*`, `*.dev`, `*.*`, `*.*.*`, `*.*.*.*` and every other unlabelled record are never touched. One
  INFO per boot names the root wildcards still there as no longer needed; the owner decides.

**Who writes.** The PUBLIC edge (`qits-edge`), through the same base URL and bearer its DNS-01 orders
use (`HetznerAccess`, see [Who holds the Hetzner token](#who-holds-the-hetzner-token-qits-2617)) —
whether or not ACME is enabled. `HostRecords` is inactive, and says why once at INFO, on the private
edge; an edge with no Hetzner token is not inactive, it writes through qits-egress. The private
edge's tunnel responder (`TunnelDns`) is unrelated and unchanged.

**When.** A debounced reconcile (5 s) is requested whenever a route table is served — the cached
one at boot and every newer one stored — when a new tunnel address is announced, and once the
project projection reaches the log head at start; a 10-minute schedule is the backstop. Nothing is
deleted before a route table is loaded and the project projection is authoritative. Until then a
**seed pass** (qits-2293) creates or sets only the names code knows — the node, the apex and the
`PlatformApps` CNAMEs (`registry`, `mirror`, `idp`, e.g. `registry.qits.<domain>`) — and
deletes nothing, switches no type and skips a hand-made record with the same WARN, so a fresh
bootstrap's wait for `registry.qits.<domain>`, which runs before any deploy pushes a table, can pass;
it logs `host records seeded: …`. A
reconcile that changed something logs one INFO line, `host records reconciled: created …, set …,
labelled …, switched …, deleted …`; a Hetzner or qits-egress failure is a WARN with the authority that
answered, its status and its body (no bearer is ever logged) and is retried on the next trigger — routing and event consumption never see it.

**What it means for clients.**

- Every public name is a CNAME to `node.<domain>`, and a name nobody serves is NXDOMAIN rather than a
  404 from the edge — once the hand-made wildcards are gone.
- Private names are visible in the public zone, and unreachable without the tunnel: `100.96.0.1`
  routes nowhere off it. Nothing behind them changes; authentication is still on every door.
- Off the tunnel a private name TIMES OUT (a connect to `100.96.0.1` that never answers) instead
  of reaching the public edge's `421` page. The `421` is still answered when a resolver cached a
  public answer from before the record existed, or filtered the `100.x` answer.
- **DNS-rebind protection drops the private answers.** dnsmasq ≥ 2.93 (OpenWrt 25.12) discards
  answers in `100.64.0.0/10` by default, and a FRITZ!Box with rebind protection may do the same, so a
  client behind such a router gets no address for a private name from the public zone. The remedy is
  a person adding the zone (e.g. `qits.wohlben.eu`) to the router's rebind exceptions — on a
  FRITZ!Box: Heimnetz → Netzwerk → Netzwerkeinstellungen → DNS-Rebind-Schutz. The tunnel resolver
  `100.96.0.1` (split DNS) answers the same names on the tunnel and is unaffected either way.

## What it does not do — the non-goals, on purpose

- **No hand-maintained path table.** Direct prefixes are deployment facts consumed from the durable
  event log, never an enum or an edge environment variable. The auth gate is per vhost and per
  *credential*, never per path, and proxying preserves the request path unchanged — the door's `GET
  /` answers rather than rewrites. `/token` remains the single path this process claims on a
  configured application vhost.
- **No login page and no session of its own.** The edge *reads* a session — it introspects the
  `qits-session` cookie at idp and lets a live one through (see *Browser sessions* below) — but
  it asserts no identity from it, and it issues none, stores none, and serves no page.
  Registration, login and logout are qits-idp's, reached through the anonymous `/idp/` prefix on
  idp's own host.
- **No header stripping or injection beyond `X-Forwarded-*` and `X-Qits-*`.** The reserved prefix is
  stripped on every request, and **no identity is ever written back into it** (qits-1373): no
  `X-Qits-User`, `X-Qits-User-Id` or `X-Qits-Roles`, for a session or a bearer. A service
  authenticates only the bearer it is sent and reads nobody from a header. What the edge does write
  after the strip is attribution: `X-Qits-Caller` for a platform page, and a `qits:system` bearer's
  own `X-Qits-Causation-Id`, `X-Qits-Caller`, `X-Qits-Environment`, `X-Qits-Origin-Trace-Id` and
  `X-Qits-On-Behalf-Of` (see *A service's own name, gated per request*). `Authorization`, `Cookie` and every custom header pass through untouched.
- **No UI, no SPA, no landing page, no `/api`.** The paths this process answers are `/q` (on its own
  names only),
  `/main-navigation`, `/upstream-pools`, and, on a configured application vhost only, `/token`. It knows nothing about
  projects or repositories: a navigation slot says WHERE the shell hangs an entry, and the shell
  decides what hangs there.
- **No TLS of its own to configure.** The image carries a Let's Encrypt certificate *slot* and
  nothing in it (see below). With no keystore from the deployment the edge speaks plain HTTP, and a
  terminator in front of it stays a deployment choice; see `X-Forwarded-Proto` below.

## What it does do

- **Streams.** Request and response bodies are never buffered, so SSE channels, `git clone`, OCI
  layer pushes and chunked responses all pass through. `EdgeRoutingTest` times the first chunk of a
  slow response, which is the only assertion that catches a buffering regression.
- **Forwards WebSocket upgrades**, which is what carries the platform's interactive PTY terminals.
  Upgrades are the edge's **own** path, `EdgeWebSocketUpgrade`, not `vertx-http-proxy`'s built-in
  one: under Quarkus the inbound request reaches the proxy already read, its upgrade path then
  crashed mid-handshake — after the upstream had accepted — and every attempt leaked one upstream
  pool connection, neither closed nor returned. At the pool's 64 the whole origin hung, plain GETs
  included, with nothing logged. The edge's path never registers a body handler on the handshake
  (a WebSocket client sends nothing before the `101`), closes the upstream connection on every
  failure after acquisition, and answers a refused upgrade with the upstream's own status.
  Saturation is bounded and visible now, whatever causes it: each per-origin pool (256) fronts a
  bounded wait queue (256) and every origin acquisition carries a 30 s bound, so exhaustion answers
  fast — 503 on the upgrade path, 502 through the proxy — with a WARN line naming the origin,
  instead of queueing forever with nothing logged.
- **Keeps the client's `Host`.** `vertx-http-proxy` leaves a proxied request's authority unset and
  the client then fills `Host` in from the socket it opened, so without the fix in `EdgeHeaders`
  every request would reach its service claiming to be for `prod-qits-projects:8080`. Redirects,
  cookie domains and absolute URLs are all built from that name. `VhostRoutingIT` asserts it from
  the receiving end, on two services at once.
- **Adds `X-Forwarded-For` / `-Host` / `-Proto`, only when absent.** The edge is not always the
  outermost hop: a TLS terminator in front of it is the only thing that can tell the truth about
  `https`, so overwriting would replace a true value with a false one. Consequently **nothing may
  make a trust decision on these three**; they are diagnostics and link generation.
- **Corrects the SPA cache header.** Every service serves its SPA with the Quarkus static default,
  `Cache-Control: public, immutable, max-age=86400`. That is right only where the name changes with
  the content, so `EdgeCacheControl` rewrites it to `no-cache` on every path whose filename is not
  content-hashed — above all each `index.html`, the mutable pointer naming the hashed bundles and so
  the file that decides which version of an application a returning browser runs. **Only that exact
  default is touched**: a header a handler chose is a decision, and a blanket rewrite would weaken
  this process' own `no-store` routes. qits-gateway did this and the edge did not when it replaced
  it, which is how a green, correctly deployed release could stay invisible for a day and then come
  right on its own — a cache reading as flakiness. `SpaFreshnessIT` is the story: all three shapes
  served by a real upstream, through a launched edge, with the two that are corrected and the two
  that are not side by side.
- **Serves `/main-navigation` on every vhost**, from the same snapshots, and writes every origin
  in the grammar the router reads — `<app>[.<env>].<project>.<domain>`, spelled forwards.
  `https://dev.acme.example.com` and `https://ci.dev.acme.example.com` for a project with
  environments, `https://gizmo.example.com` and `https://ci.gizmo.example.com` for one without,
  whichever of its names the request itself used. A one-label domain (`dev.acme.localhost:8080`) is
  the same rule rather than an exception to one. The document is `environment`, `origin`,
  `projectOrigin`, `slots` and `applications`, and nothing else. `origin` is the innermost door —
  the environment's inside a project that has them, the project's own otherwise;
  `projectOrigin` is the authority a client puts `<app>.` in front of to reach that application for
  this project (ONE label: the project is in the authority already), and it exists because the
  client side derived that name itself and shipped two domain-derivation bugs doing it. On a name
  inside no project — the apex, an address literal — an entry's origin is null, because there is no
  application address to compose. Then every
  slot of the closed
  vocabulary (empty ones included, so a shell iterates the document rather than a copy of the
  vocabulary), and one entry per placement with the application, the label, the host, that host's
  origin, the application's primary route `path` and the position. `host` is null until that
  application is flipped, and `path` is present either way — it is what a shell renders an unflipped
  application under, so nothing leaves the sidebar during a rollout. There is no flat list and no
  synthesized `Home`: the environment's own door is qits-projects' `system` entry, a deployment fact
  like every other entry here. `applications` holds one object per application with a published
  host (`origin`) or api-docs path (`apiDocs`).
- **Names private applications in `/main-navigation` to a signed-in caller, and to nobody else**
  (qits-2061). The document is anonymous, but when the request carries a credential the service gate
  would accept — a bearer or basic credential that passes `EdgeAuth.checkCredential`, or, while the
  browser gate is on and no `Authorization` is sent, a `qits-session` cookie `EdgeSessions`
  introspects as live (`AdmittedCaller`, the check `/upstream-pools` uses too) — the public edge
  adds every application it withdrew for being `private` that declared a host:

      "qits-stt": {"origin": "https://stt.qits.wohlben.eu", "exposure": "private"}

  No `apiDocs`, no `slots` entry. A missing, refused or uncheckable credential is never an error
  status: the private entries are left out and the answer is the anonymous document, byte for byte
  — private names are not advertised to the internet. Public and `both` entries never carry
  `exposure`. With nothing withdrawn there is no credential check at all. The private edge's own
  document is unchanged: what it withdraws is public, already listed by the public edge.
- **Keeps upstream sockets honest.** Every upstream connection has `SO_KEEPALIVE` on and the
  kernel's keepalive timers set to probe after 60 s of silence, every 10 s, and give up after 3
  (`UpstreamKeepAlive`). An SSE stream whose upstream task swarm redeployed away gets no FIN and no
  RST, the edge only reads, and the proxy client's idle timeout is zero on purpose — so without
  these the socket stayed ESTABLISHED for good and held one of its origin's 64 pool slots; after 64
  such streams every request to that origin waited out the 30 s acquisition bound and failed.
  Vert.x 4.5 applies no `tcpKeepAlive*` timer to a client socket, so they are set per channel in the
  client's connect handler, reached through Vert.x internal API in one class (`UpstreamChannel`).
  An option that cannot be set is an ERROR once per process per option and never fails a request.
- **Proxies streams through a pool of their own.** An SSE GET and a WebSocket each hold a pooled
  HTTP/1.1 upstream connection for their whole life, so agents' MCP event streams to qits-projects
  once filled its whole pool (then 64) and every ordinary request to it queued 30 s and failed. A
  request is a *stream* when it is a WebSocket upgrade, or a GET/HEAD whose `Accept` names
  `text/event-stream` (any value, any case); those go through a second proxy client with its own
  per-origin pool of 256. Everything else — MCP's POSTs included, although they send
  `Accept: application/json, text/event-stream` — goes through the ordinary client, also 256.
- **Says when a pool is under pressure, and what it is full of.** Each client's connect handler
  counts open connections per origin (`host:port`, what Vert.x pools by) and counts each one down
  when its Netty channel closes — the one close signal that survives any change of handler on that
  channel. Every connection the pool grants is *leased* to the exchange it carries — method, path
  without query, inbound client (first `X-Forwarded-For` hop, else the peer), `User-Agent`, the
  `workspaceId` query parameter, and the trace id — until the upstream response ends, the exchange
  fails, or the channel closes; an idle pooled connection has no lease. A check every 60 s logs one
  WARN per pool and origin that spent more than 30 s of the interval at max, or where any
  acquisition waited more than 5 s for a connection, with the time at max, the longest wait, the
  count of slow waits and the five oldest leases. WebSockets count: a spliced `101` keeps its pool
  slot until it closes, so a full pool of terminals is a full pool.
- **Serves `/upstream-pools` on every vhost**, never proxied, `Cache-Control: no-store`: a JSON
  array with one entry per pool and origin holding a connection, fullest first —
  `{"name":"qits-projects","environment":"dev","origin":"dev-qits-projects:8080","pool":"stream","open":12,"max":256,"held":[{"method":"GET","path":"/projects/mcp","ageMs":3605211,"client":"10.0.0.7","userAgent":"…","workspaceId":"ws-1","traceId":"4bf9…"}]}`.
  `held` is the pool's leases on that origin, oldest first.
  `name` and `environment` come from the route table, then the platform app grid, then
  the wire alias read as `<env>-<application>`; an origin none of them names is listed under its
  host with a null environment. `max` is that pool's configured size. It answers only a caller the
  edge would let through to a service — a machine credential carrying the platform audience, or,
  with the browser gate on, a live `qits-session` — and `401` (a `Bearer` challenge, never `Basic`)
  to everybody else.
- **Answers `/q/health/{live,ready}` itself**, never proxied — but only on a name that means the edge:
  the apex reading (no `Host`, an address literal, a single label such as `localhost` or the wire
  alias, the bare domain). On any other name `/q/*` is an ordinary path: an app's vhost proxies it
  to the app behind the gate, a name nothing routes answers `404`. An app's name never reports the
  edge's health — it did until qits-2222, and a stopped app read healthy. Readiness
  reports the served route table's version (`route-table`, or `none`) and is DOWN only while no
  table is served on the private edge — see *Startup serves the cached table*.

### A loopback page's socket: the bearer rides the subprotocol offer

Under `ng serve` the SPA is on `http://localhost:<port>` and calls the platform with
`Authorization: Bearer <token>` (see `EdgeCors`). A browser `WebSocket` cannot send that header, and
the loopback page holds no session cookie, so its terminal and chat sockets carry the token in the
one header a page does control on a handshake (`EdgeWebSocketBearer`, qits-1246):

```js
new WebSocket(url, ['qits.bearer', token]);   // other subprotocols may follow, e.g. [..., 'tty']
```

- **The contract.** The offer names `qits.bearer`, and the entry immediately after it is the token —
  a JWT or a `qits_tok_` value. Both are RFC 7230 `token`s (base64url plus dots, no comma or space),
  so the browser accepts them as subprotocol names. The URL never carries it.
- **Loopback pages only.** The handshake's `Origin` must be what `EdgeCors.isLoopback` admits —
  exactly `http://localhost[:port]` or `http://127.0.0.1[:port]`. Any other page offering
  `qits.bearer`, a page under the platform domain included, and a handshake with no `Origin`, is
  answered `403` with a valid token or without one: a page under the domain uses its cookie.
- **Judged like any bearer.** The token is moved out of the offer into `Authorization: Bearer`, and
  from there it is the ordinary machine path — the same check, the same audience, an opaque token
  forwarded as its JWT, the session cookie stripped and no identity asserted. It is checked even on a
  name whose reads are open. A marker with nothing after it, or a token that fails, is a `401`
  before any upstream connection is taken.
- **Neither the marker nor the token goes upstream.** The service sees `Authorization: Bearer
  <jwt>` and an offer holding only the other subprotocols (no `Sec-WebSocket-Protocol` at all when
  there were none). The browser is answered `Sec-WebSocket-Protocol: qits.bearer` — or the upstream's
  own selection when it picked one of the other offered values. A browser fails a handshake whose
  answer selects nothing after it offered something, so the edge never leaves it empty.
- **No log line carries it.** The edge logs no request header anywhere, and after the gate the
  offer no longer holds the token at all. `EdgeSessionGateTest` asserts both halves from a handler on
  the root logger.

### The one known gap: `Host` on a WebSocket handshake

The upgrade path rebuilds the handshake with the client's own `Host` dropped — deliberately kept
from `vertx-http-proxy`'s behaviour when `EdgeWebSocketUpgrade` replaced it, so upstreams see no
change. An upstream therefore reads a socket's original host name from **`X-Forwarded-Host`**,
which the edge does set on that path, and not from `Host`. It costs nothing today, because a
handshake's `Host` is a protocol formality rather than something a service routes on one hop
further in — but if that ever changes, this is where to look.

`StreamingPassthroughIT` asserts the gap **both ways round** — `X-Forwarded-Host` is the vhost and
`Host` is not — so it stays a known gap rather than quietly becoming a surprise. A story that
pinned only the half that works is how it would stop being documented.

## Redeploys — what a replaced edge finishes

Every download on the platform passes through this process, so a redeploy of it is a redeploy of
every transfer in flight (qits-1170). The update is start-first: the successor passes
`/q/health/live` — swarm's healthcheck, which is liveness (qits-2160); the successor serves the
cached route table from its first request — and only then does swarm send the predecessor SIGTERM and
take it out of the ingress balancer, so nothing new reaches it. What happens to the old task after that:

- **Streams end at once.** `EdgeRouter.draining` runs in Quarkus' pre-shutdown pass
  (`ShutdownDelayInitiatedEvent`, enabled by `quarkus.shutdown.delay-enabled=true` with no delay)
  and closes every stream-pool connection — proxied SSE and spliced WebSockets — then sweeps again
  each second. Their clients reconnect, to the new task. Without this the drain could never end
  early: those streams never finish by themselves.
- **Every exchange that ends is uncounted** (`DrainCount`). Quarkus' drain counts a request finished
  only when its response's end, close or exception handler runs, and two endings run none: a
  response the proxy resets because its upstream broke mid-body (HTTP/1.1 marks it closed before
  the connection goes, so the close finds nothing to tell), and a WebSocket spliced with
  `toNetSocket()`. Each used to stay counted until the process exited, so after a day of uptime the
  drain always ran to its timeout — the live redeploy of 2026-10-21 closed its 13 streams and still
  waited the full 9m30s. `DrainCount` settles both: as a proxy interceptor when a response send
  fails, and from the splice when the inbound socket closes.
- **Transfers finish.** Ordinary requests drain for up to `quarkus.shutdown.timeout=9m30s`, inside
  the `stop_grace_period: 10m` that `.config/qits/deployments.yml` hands the deployer. Keep the two
  in step: a timeout at or over the grace is SIGKILL mid-transfer. **That is the public edge's
  budget.** The private edge updates stop-first, so nothing serves its names while it drains: it
  drains for `30s` at most (`PrivateVariantConfigSource`) inside a `stop_grace_period: 1m`, and a
  longer transfer through it is cut by a redeploy — accepted, against the 9m51s outage a stuck
  drain cost (qits-2223).
- **Then it lingers.** After the drain the process waits `qits.edge.shutdown.linger=10s` before
  exiting (`ShutdownLinger`): a response Quarkus calls complete may still sit in the kernel's send
  buffer, and exiting at once lets swarm tear down the network namespace under it — a 201 MB jar
  lost its last 639 KB that way. The budget is 9m30s + 10s, still under the 10m grace.
- **An upstream being replaced is retried once.** A bodyless GET or HEAD whose upstream connection
  broke before any response head — a pooled connection the draining upstream closed, a refused or
  timed-out connect — is sent once more on a fresh connection (`UpstreamRetry`), then answered 502
  as before. Never a request with a body, never a response that has started.

## Configuration

Every key is overridable by environment variable, so a deployment declares the whole surface without
a file.

| Key | Env | Default | What it is |
| --- | --- | --- | --- |
| `qits.edge.domain` | `QITS_DOMAIN` | `localhost` | **The one domain this platform states about itself**, and the primitive every composed name is built from: the grammar every Host is read against, the certificate's SANs, the browser return authorities, and the canonical origin. It is the estate's fact rather than this service's, so it is read under the platform's own spelling — qits-deployments writes `QITS_DOMAIN` into every container beside `QITS_ENVIRONMENT`. `QITS_EDGE_ACME_DOMAIN` and `QITS_EDGE_SESSIONS_CANONICAL_ORIGIN` were the same fact under two more names and are **retired** |
| `qits.edge.environments` | `QITS_EDGE_ENVIRONMENTS` | `prod` | The routable environment names, comma separated |
| `qits.edge.default-environment` | `QITS_EDGE_DEFAULT_ENVIRONMENT` | `prod` | Where the apex and every unmatched host go. **Must be in the list** |
| `qits.edge.apps.<app>.hosts.<env>` | `QITS_EDGE_APPS_<APP>_HOSTS_<ENV>` | — | A local process's or a suite's override of one **platform** label in one environment, `host` or `host:port`. It cannot add a label; a live deployment sets none |
| `qits.edge.exposure` | `QITS_EDGE_EXPOSURE` | `public` | Which variant this edge is, and so which route table it accepts, `public` or `private` — see *The exposure class* |
| `qits.edge.dns.node-address` | `QITS_EDGE_DNS_NODE_ADDRESS` | — | The node's public address, IPv4 and/or IPv6 literals comma separated; the public edge writes it as `node.<domain>` and the apex, and every public name as a CNAME to `node.<domain>.`. Set by `qits-bootstrap-cli` (qits-2293); empty, no public record is written or deleted — see *Host records in public DNS* |
| `qits.edge.listen.allowed-local-cidrs` | `QITS_EDGE_LISTEN_ALLOWED_LOCAL_CIDRS` | — (any) | Local addresses a connection may arrive on, as CIDR blocks; set, anything else is closed before routing and the loopback is always added |
| `qits.edge.wireguard.peers` | `QITS_EDGE_WIREGUARD_PEERS` | — | Static peers, `name:base64PublicKey:tunnelIp` comma separated; names the caller in the private edge's peer log. A malformed entry is dropped with a WARN |
| `qits.edge.peer-log.overlay-cidr` | `QITS_EDGE_PEER_LOG_OVERLAY_CIDR` | `10.231.0.0/20` | The `qits-private` overlay; a remote inside it is `peer=overlay` |
| `qits.edge.catchup.required` | `QITS_EDGE_CATCHUP_REQUIRED` | `true` | Runs the project and WireGuard-peer projections' startup catch-up to qits-events' head; turn off only in an intentionally offline test/dev setup |
| `qits.edge.catchup.retry` | `QITS_EDGE_CATCHUP_RETRY` | `PT1S` | Delay before retrying an incomplete, failed, or unavailable catch-up read |
| `qits.edge.shutdown.linger` | `QITS_EDGE_SHUTDOWN_LINGER` | `10s` | How long the process stays alive after the HTTP drain so the kernel can send what the last responses queued; with `quarkus.shutdown.timeout` it must stay under the 10m stop grace (qits-1170) |
| `qits.edge.idp.dial-url` | `QITS_EDGE_IDP_DIAL_URL` | `http://${QITS_ENVIRONMENT:dev}-qits-idp:8080/idp` | The address the edge dials. `/jwks`, `/token` and the two introspection paths are derived from it, never configured. It is never the issuer: the accepted `iss` is `https://idp.qits.<qits.edge.domain>` alone, derived in code and not configurable (qits-730). **Internal on purpose** (qits-1403, exception E1): `QITS_RESOURCE_IDP_URL` is not read any more, because the deployer makes it idp's public name — which this process serves — and the edge must not dial its own gate to check a token. Nothing injects `QITS_EDGE_IDP_DIAL_URL`. `QITS_IDP_DIAL_URL` is not read either (qits-540) |
| `qits.edge.auth.enforce-on-apps` | `QITS_EDGE_AUTH_ENFORCE_ON_APPS` | `true` | Service vhosts require a valid idp token |
| `qits.edge.auth.anonymous-read-apps` | `QITS_EDGE_AUTH_ANONYMOUS_READ_APPS` | — | App labels whose `GET` and `HEAD` are open; every other method on them still needs a token |
| `qits.edge.auth.clock-skew-seconds` | `QITS_EDGE_AUTH_CLOCK_SKEW_SECONDS` | `30` | How far this clock and idp's may disagree about `exp` |
| `qits.edge.auth.jwks-refresh-cooldown-ms` | `QITS_EDGE_AUTH_JWKS_REFRESH_COOLDOWN_MS` | `5000` | Shortest gap between two JWKS fetches |
| `qits.edge.auth.basic-cache-ttl-ms` | `QITS_EDGE_AUTH_BASIC_CACHE_TTL_MS` | `300000` | Ceiling on how long a validated HTTP Basic credential is believed; the minted token's own life is the other half |
| `qits.edge.auth.basic-cache-size` | `QITS_EDGE_AUTH_BASIC_CACHE_SIZE` | `1024` | The most validated credentials held at once, least-recently-used |
| `qits.edge.auth.token-cache-ttl-ms` | `QITS_EDGE_AUTH_TOKEN_CACHE_TTL_MS` | `15000` | Ceiling on how long idp's answer about an opaque `qits_tok_` token is believed — refusals included. **The upper bound on a revoked token's afterlife at the edge** |
| `qits.edge.auth.token-cache-size` | `QITS_EDGE_AUTH_TOKEN_CACHE_SIZE` | `1024` | The most introspected tokens held at once, accepted and refused together, least-recently-used |
| `qits.edge.auth.idp-retry-window-ms` | `QITS_EDGE_AUTH_IDP_RETRY_WINDOW_MS` | `45000` | How long a redeploying idp is waited out before the edge answers an error |
| `qits.edge.auth.idp-call-timeout-ms` | `QITS_EDGE_AUTH_IDP_CALL_TIMEOUT_MS` | `5000` | How long ONE call to idp may take, connection included — **what makes an answer certain** |
| `qits.edge.sessions.enabled` | `QITS_EDGE_SESSIONS_ENABLED` | `false` | Whether a browser needs a session on a service vhost — **the rollout flag** |
| `qits.edge.sessions.cookie-name` | `QITS_EDGE_SESSIONS_COOKIE_NAME` | `qits-session` | The cookie idp sets and this process reads |
| `qits.edge.sessions.login-path` | `QITS_EDGE_SESSIONS_LOGIN_PATH` | `/idp/login` | Where a navigation with no session is sent — on the host of whichever deployment owns this route |
| `qits.edge.sessions.anonymous-prefixes` | `QITS_EDGE_SESSIONS_ANONYMOUS_PREFIXES` | `/idp/` | Path prefixes served with no credential at all — on the owning service's own host, nowhere else |
| `qits.edge.sessions.cache-ttl-ms` | `QITS_EDGE_SESSIONS_CACHE_TTL_MS` | `30000` | How long an introspected session is believed — and how long a logout lingers |
| `qits.edge.sessions.cache-size` | `QITS_EDGE_SESSIONS_CACHE_SIZE` | `1024` | The most sessions held at once, least-recently-used |
| `qits.edge.sessions.stale-grace-ms` | `QITS_EDGE_SESSIONS_STALE_GRACE_MS` | `60000` | How long a cached session outlives an **unreachable** idp |
| `qits.edge.idp.client-id` | `QITS_RESOURCE_IDP_CLIENT_ID` | — | The edge's own idp client, for introspection: the `idp:client` resource the deployer injects (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4/D6/D7). Its id is `{env}-qits-edge` and stays so — the plan's D2 cutover to `qits-platform-edge` is obsolete with the platform tier gone. `QITS_EDGE_SESSIONS_CLIENT_ID` is not read any more (qits-540) |
| `qits.edge.idp.client-secret` | `QITS_RESOURCE_IDP_CLIENT_SECRET` | — | Its secret; `QITS_EDGE_SESSIONS_CLIENT_SECRET` is not read any more. Unset is "no credential", which fails startup if the gate is on. These three keys were `qits.idp.dial-url` and `qits.edge.sessions.client-*`, whose environment spellings ARE the older variable names — and an environment variable outranks `application.properties`, so with both injected the old one won (qits-163). They stay under `qits.edge.idp` so a container still carrying the older names cannot set them. Nothing injects `QITS_EDGE_IDP_*` |
| `quarkus.oidc-client.qits.auth-server-url` | — (follows `qits.edge.idp.dial-url`) | `qits.edge.idp.dial-url`'s own value | **The token this service PRESENTS** (qits-543/qits-1330): the named client `qits`'s own `qits:system` bearer, minted by client credentials from the same `idp:client` resource as the two rows above — one credential at idp, two consumers of it here. Dials the SAME address as every other call this process makes to idp, by reference rather than by a second literal: this is the one service that derives the issuer from the domain in code (`Idp#issuer`), and restating `https://idp.qits.<domain>` here would be refused outright by `EdgeChallengeTest#theShippedFileStatesNoIssuer`. No class of this source tree spends the token today; the qits-eventstream library's calls do, through `OidcClients.getClient("qits")` (qits-1328). Off under `%dev` and `%test` |
| `quarkus.oidc-client.qits.client-id` / `.credentials.secret` | `QITS_RESOURCE_IDP_CLIENT_ID` / `QITS_RESOURCE_IDP_CLIENT_SECRET` | `dev-qits-edge` / — | The same pair `qits.edge.idp.client-id`/`-secret` read, reused rather than duplicated |
| `qits.observability.url` | `QITS_OBSERVABILITY_URL` | `http://qits-observability:8080` | Where telemetry goes; `qits.telemetry.endpoint` (qits-telemetry-core, qits-2188) derives the OTLP endpoint from it, bearer attached |
| `qits.events.url` | `QITS_EVENTS_URL` | `http://${QITS_ENVIRONMENT:dev}-qits-events:8080` | Where the edge's durable consumers read qits-events. **Pinned in this repository's `application.properties`** (qits-1403, exception E1), above the qits-eventstream jar's own default, which is to move to qits-events' public name — through the edge, whose catch-up is what that name waits behind |
| `qits.observability.url` (as shipped) | `QITS_OBSERVABILITY_URL` | `http://${QITS_ENVIRONMENT:dev}-qits-observability:8080` | The row above names the old bare default; the shipped one is the internal alias for the public edge — it has not left qits-net, so its own telemetry must not loop through its own listener (qits-1403, exception E1). The private variant overrides this same key to the public name instead (see "The private edge" above); either way `qits.telemetry.endpoint` follows it with a bearer attached |
| `qits.telemetry.endpoint` | — (derived) | `${qits.observability.url}/observability/api/otel` | qits-telemetry-core's own default, restated here unchanged: the module's three CDI exporters append `/v1/<signal>`. Not a second address to keep in step — only `qits.observability.url` above ever moves |
| `qits.telemetry.bearer.enabled` / `.client` | — | `true` / `qits` | The exporters attach the named oidc client's (`qits`, configured above for qits-eventstream) current bearer to every export — on both variants, since the internal alias accepts an authenticated request as readily as an anonymous one |

Six things fail **at startup** rather than per request, deliberately: an environment or application
name that could not be a DNS label, a default that is not in the list, an application that shares an
environment's name (the tie-break would make that environment unreachable), an address override for
a label that is not a platform app, and the session gate turned on with no client id and secret to
introspect with. All would otherwise be a 502, a 404, a
connection error or a 401 on every request, with nothing to read.

**The platform's app vhosts are code, not configuration** (qits-528). `PlatformApps` maps each
label to the application it fronts, and the upstream is that application's wire alias in the
environment the Host name named — `<env>-qits-<application>:8080`, the address qits-deployments
gives every service on qits-net:

| label | application | `dev`'s upstream |
|---|---|---|
| `registry` | `qits-artifacts` | `dev-qits-artifacts:8080` |
| `mirror` | `qits-mirror` | `dev-qits-mirror:8080` |
| `idp` | `qits-idp` | `dev-qits-idp:8080` |

`idp` joined the byte-plane labels in qits-1403 (then three; `githost` left in qits-2743) so its name answers with no route table at
all (see *Startup serves the cached table*). Its gate is the one it had as a published host — the
anonymous prefixes (`/idp/`, so the login page and `/idp/jwks`) on its own name, the session gate
for a cookie, the ordinary service-vhost gate off its route — with one addition: **under idp's own
route, a request carrying `Authorization` passes through untouched.** idp judges every credential
itself (a `client_credentials` pair at `/idp/token`, its own Basic at the introspection doors, its
own Bearer on reads), so the edge checks, spends, mints and rewrites nothing there and refuses
nothing; spending a Basic pair and forwarding the minted JWT instead would break every grant made on
idp's public name. The `X-Qits-*` namespace is still stripped, attribution included: keeping a
service bearer's attribution headers would need the credential checked. This holds whether the name came
from the route table or from `PlatformApps`, with a table and without one. `PlatformApps` states idp's own route, `/idp`, so that "on the
name that owns them" holds without a table; once idp is in one, its published `idp` host must point
at the same `<env>-qits-idp:8080` or the table is refused, as for `registry`.

These used to be keys — `qits.edge.apps.<app>.host-pattern`, `audience-pattern` and `port` — and
the live store held six `QITS_EDGE_APPS_*_PATTERN` entries spelling this one rule, one of them a
tier that no longer exists (`{env}-qits-platform-mirror`). None of it is a fact about an
installation, so none of it is configurable. The six entries are left inert until the configuration
GC collects them: the application declared none of them from the release after the one that first
did.

`qits.edge.apps.<app>.hosts.<env>` is what is left: a fixed address for one platform label in one
environment, for a developer running one service on `localhost:8000` and for this repository's own
tests, where the upstreams are stand-in servers on ephemeral ports (`StubGateways` for the suites,
`StoryUpstream` for the userflow catalogue). It cannot add a label — a key naming anything else
fails startup — and a live deployment sets none: a stale override sends a whole tier's traffic to
the wrong process.

There is **no** `qits.edge.upstream-host-pattern`, `qits.edge.upstream-port` or
`qits.edge.upstream-hosts`. Those were the per-environment gateway's address, and there is no
per-environment gateway: an upstream is a platform app label or a host a deployment published, and
nothing else.

## Authentication — terminated here, on the first node

The edge sees every request before anything else does and already reads the `Host` header, so it is
the right and cheapest place to gate. What it gates is a **vhost**, never a path: an application
vhost fronts a service with no external auth of its own.

A request to an application vhost must carry an idp credential: an access token, or the client id
and secret it is minted from (see *HTTP Basic* below). A token is validated **offline**
against the keys fetched from `qits.edge.idp.dial-url/jwks` and cached — idp is overlay-only, so a host client
cannot reach it, and keeping it off the per-pull path is worth more than the freshness a call-out
would buy. An unknown `kid` buys **one** refresh, behind a cooldown, so a made-up kid cannot turn
into a request per request at the identity provider. The checks are RS256 only, an exact accepted `iss`, live
`exp` within the skew, and `qits-platform` in `aud`.

**One audience opens every vhost, and no other does.** A token whose `aud` names `qits-platform`
passes on every gated vhost, in every environment, and on every path: Bearer, Git's
`Basic oauth2:<token>`, and Basic client credentials. A token without it is refused, whatever else
its `aud` names — `dev-qits-githost` opens nothing. It is the one audience idp mints. It has no
`{env}`, on purpose: it only says
the token is for this platform, and the token's roles are what each service checks
(epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4). `qits.edge.auth.audience-pattern` and
`qits.edge.auth.platform-audience` were keys until qits-528, each defaulting to that literal; no
deployment set either.

### Anonymous reads, named per app

`qits.edge.auth.anonymous-read-apps` lists app labels whose **`GET` and `HEAD` pass without a
credential**. Every other method on those same names keeps the whole check, so this opens reads and
never a service.

The reads are the bootstrap steps: pulling a base image onto a fresh node, cloning a repository,
fetching a dependency from the mirror — each happens *before* there is anything to hold a token, and
each is what a gated vhost breaks first. A push, a tag delete, a `receive-pack` is never a bootstrap
step.

The list is empty by default, which is full enforcement. It is matched against the app label the
`Host` name already resolved to — a configured entry or a published host — so it reaches service
vhosts only, and a label nothing claims is still a `404` rather than an open door.

### The docker flow

`docker login` stores a password and resends it forever, while an idp token lives ~300 seconds and
cannot be refreshed. The Distribution spec's own answer is the Bearer token-endpoint flow, and it is
what this implements:

1. docker asks for something and gets `401` with
   `WWW-Authenticate: Bearer realm="http://<vhost>/token",service="<vhost>"`;
2. docker GETs that realm with HTTP **Basic** — an idp **client id and client secret**, which is the
   durable credential a user stores with `docker login`;
3. the edge brokers a `client_credentials` grant to idp over qits-net and answers
   `{"token": …, "access_token": …, "expires_in": …}`;
4. docker retries with `Authorization: Bearer …`, and re-fetches when it expires.

The grant asks idp for **no specific audience**, which gets the client's whole allowed list; naming
one here would fail at idp with an `invalid_target` the caller cannot read, whereas checking `aud`
on the way back in puts the refusal where the reason is known. docker's `service` and `scope` query
parameters are read and dropped: the audience the token carries *is* the permission, and
per-repository grants would be a change to the platform's claim model rather than to this process.

**An opaque token logs docker in too:** `docker login registry.<env>.<domain> -u token -p qits_tok_…`
(any username). At step 3 the edge sees a `qits_tok_` password, grants nothing, and introspects it
instead — the same call and the same cache as the gated path below — answering the docker-shaped
body with the JWT the token stands for and the `expires_in` that JWT has **left**. docker then
presents that JWT as an ordinary Bearer, and comes back to the realm when it runs out, which is where
a revoked token stops working. An unknown or revoked token is the realm's `401 UNAUTHORIZED`; an idp
that cannot be reached, its `502 UNAVAILABLE`. A client id and secret behave exactly as before.

### HTTP Basic, for the clients that cannot do the dance

maven, npm and git send `Authorization: Basic` and nothing else — none of them reads a Bearer
challenge, fetches a token and retries — so a gated request carrying **Basic** is accepted on its
own terms. The edge validates it the only way a client id and secret can be validated: it spends
them at idp, exactly as `/token` does, and then treats the token that comes back as if the caller
had presented it. Same issuer, same `exp`, same signature, same audience.

A credential that is not base64 of `<id>:<secret>` is refused **here**, without a call: there is
nothing to ask idp about, and asking would hold the caller for the whole retry window below.

The result is cached against a **SHA-256 of the credential** — never the credential — for the
shorter of the minted token's life and `qits.edge.auth.basic-cache-ttl-ms`, in a bounded LRU. Without
it, a Basic client resends its credential on every request, so every dependency fetch would put an
idp round trip on the path. What is cached is the credential's own soundness plus the audiences it
carried, checked for `qits-platform` on every request. **Refusals are not cached**: a rotated secret must start working the moment it is right.

The `401` stays the Bearer challenge whatever the credential was — docker is the client that reads
it — and carries `error="invalid_token"` when a credential was presented and refused.

**The password may be an opaque token instead of a secret**, in two spellings: git's
`oauth2:qits_tok_…`, and `<any username>:qits_tok_…` for every client that only knows a user and a
password — maven's `settings.xml`, npm, `docker login`. The password's `qits_tok_` prefix is what
decides it, and it is looked for first: such a pair is never spent at idp as a client secret, nor
parsed as the JWT an `oauth2:` password otherwise is. It takes the token path below, and what goes
upstream is the JWT, exactly as for a Bearer — on a WebSocket upgrade too, because the upgrade copies
the header map the check has already rewritten.

### Opaque tokens, introspected per request

A **token** is a value idp issues and stores hashed: `qits_tok_` followed by 43 characters of
base64url. It is not a JWT and carries nothing the edge could check alone — what it stands for is a
row at idp, which is exactly what makes it revocable. The prefix is the whole recogniser: a JWT
always begins `eyJ`, so the two are told apart before anything is parsed, a token is never parsed
as a JWT or looked up in the JWKS, and a JWT is never sent to introspection.

It is accepted in three spellings, all on the same path:

- `Authorization: Bearer qits_tok_…`;
- `Authorization: Basic base64(oauth2:qits_tok_…)` — git's credential-helper convention;
- `Authorization: Basic base64(<anything>:qits_tok_…)` — any username at all; the password is what
  says it is a token.

The edge `POST`s it to `<qits.edge.idp.dial-url>/api/tokens/introspect` with its **own** client id and
secret in HTTP Basic — the pair it introspects browser sessions with (`qits.edge.idp.client-id`
/ `-secret`), over the same dial, with the same per-attempt timeout and retry window. idp answers
the ordinary idp JWT the token currently stands for; the edge holds that JWT to the Bearer rules —
issuer, expiry, signature, and the platform audience — and **forwards the JWT in the token's place**.
An upstream never sees a token: it sees a JWT its own OIDC mechanism already validates, with the
token's roles in `groups`. A token can therefore never buy more than the JWT it stands for.

idp's answer is cached against a **SHA-256 of the token**, in a bounded LRU
(`token-cache-size`), for the shorter of the minted JWT's life less the minute's margin and
`token-cache-ttl-ms`. **Refusals are cached too**, for the whole TTL — deliberately unlike the Basic
cache. A secret is fixed in place, so caching its refusal would keep a corrected credential shut; a
token is never fixed, it is replaced by a new value with a new cache key, while a revoked token left
in a CI config or a caller cycling made-up values would otherwise be one idp round trip per request
on the door that holds every token on the platform.

An unknown, deleted or wrongly-asked-about token is idp's `404`/`401`, and the caller gets the
ordinary `401` Bearer challenge. An idp that **cannot be reached** is a `503` with `Retry-After: 1`
and one WARN naming the door — never a pass, and never a `401`, which would tell git's credential
helper to erase a perfectly good stored token. An edge with no client of its own refuses every token
and says so once, at startup.

**Revocation lags by `token-cache-ttl-ms`** (15 seconds by default): a token deleted at idp opens
doors here for at most that long. Stated so nobody files it as a bug.

Measured on the live platform: _(numbers land with the proof task)_

### An identity provider that is not there

idp is a container like any other and is redeployed like any other. For a few seconds its name
refuses, drops, or accepts a connection and never answers, and on 2026-08-14 a deploy push died with
"the identity provider could not be reached" for landing inside that window.

Every dial at idp — the `/token` broker, the Basic validation, the token introspection, the JWKS
fetch — is therefore

- **bounded per attempt** by `qits.edge.auth.idp-call-timeout-ms`, connection included, and
- **retried** on connection-classed failures with a doubling backoff until
  `qits.edge.auth.idp-retry-window-ms` runs out.

An **answer** from idp is never retried: a `401` is idp deciding, and repeating the question would
turn one refusal into a burst of them. Only the network is.

The per-attempt timeout is the load-bearing half. A Vert.x client is built with no request timeout
and no idle timeout, so an idp that accepts a connection and then says nothing leaves the call
outstanding with nothing to end it — no status, no body, until the inbound connection's own idle
timeout closes it an hour later. A docker client has no timeout of its own on a realm call, so what
that looks like from the outside is a `docker push` that hangs rather than fails.

## Browser sessions — on every service's own name

Machine credentials are the section above. A **person** carries neither a token nor a client secret,
so every service's own name gates a `qits-session` cookie instead. The bootstrap enables
`qits.edge.sessions.enabled` only after it has seeded the edge's introspection credential and the
IdP's browser SSO settings. The environment vhost is the door and serves nothing, so it gates
nothing.

**The login page lives on idp's own name, not on the door.** The origin is read off the deployment
projection per request: whoever owns `login-path` and publishes a host. The **canonical origin**
cannot follow it, because it is also what a name inside no project falls back to — so it stays a
door, and is the fallback while no deployment has published a host for the login path.

That origin is **derived, not configured**: `https://qits.<domain>`, the platform project's own
door, or `http://qits.<domain>:8080` where the domain is not a real one. It is deliberately not the
apex, which is what `QITS_EDGE_SESSIONS_CANONICAL_ORIGIN` held (`https://wohlben.eu`) before it was
retired: the apex carries no project label, so the edge serves it as a door and a browser sent there
to log in meets a 404. idp is a platform
service deployed once, so an environment that owns no route for the path asks the default
environment before falling back.

**The return host is the name the person was on**, when it is a name under the stated domain. That
check is what stops the platform's own login becoming a redirector for somebody else's site, and it
is **derived rather than configured**: the domain itself, plus one wildcard in front of it admitting
up to three labels — `<app>[.<env>].<project>.<domain>` is the deepest the grammar goes. One
wildcard therefore covers every project and every environment, and there is no key to fall behind
the project set. The security property is the **domain anchor**, not the label count: every name it
admits is under the domain this deployment states, which is its own. The depth is bounded at the
grammar's own depth anyway, so a return host is still a name the grammar could have produced and a
suffix test's `<domain>.evil.example` is refused. A name outside all of that falls back to the door,
which is a person landing somewhere they did not ask for; that is the symptom to look for if the
**idp's** own `QITS_IDP_BROWSER_SSO_BROWSER_HOSTS` has not learnt the same shape, because it
validates the same value one hop later.

### A service's own name, gated per request

`ci.dev.example.com` is one service and one name, and both a person and a machine type it. So the
gate on a **service vhost** — a published host, or a configured application vhost, which are now the
same thing — is decided per request rather than per plane. Every inbound `X-Qits-*` header is
dropped first, whatever the outcome, and no identity header is written back on any branch
(qits-1373) — a service learns who called from the bearer it validates itself.

1. a `Bearer` or `Basic` **machine credential** takes the machine path above, checked in full, and
   is proxied with no identity headers and with the browser cookie removed — a machine's identity is
   in its token. **A service bearer keeps its attribution** (qits-1403): when the credential the
   caller itself presented is a `Bearer` JWT the edge accepted and whose `groups` include
   `qits:system`, its `X-Qits-Causation-Id`, `X-Qits-Caller` and `X-Qits-Environment` pass as sent,
   so a service's call to a peer's public name traces like its call on qits-net did. Nothing else
   qualifies — a client secret, a git `oauth2:` pair, an opaque `qits_tok_` token (whatever JWT idp
   stands it for) and a JWT without the role all keep the strip.

   **The two call-chain headers pass on the same terms** (qits-2542): `X-Qits-Origin-Trace-Id`, the
   trace id of the chain's first call, and `X-Qits-On-Behalf-Of`, the bearer `sub` of the person or
   agent the chain acts for. Services reach their peers through these public names, so without them
   in the kept list every hop would start a new chain and lose its user — and qits-toggles freezes an
   evaluation per origin and matches its user rule on that user. `X-Qits-On-Behalf-Of` names a user,
   but it is **not an identity the edge asserts**: it is a service bearer's own statement, which the
   receiving integration library honours only because the bearer carrying it is a plain `qits:system`
   client. Keeping it for nobody else is the first wall against impersonation — a browser, a
   person's CLI or an agent's token can never get either header past the edge; the receiver's own
   rule is the second wall, for any path that does not cross the edge;
2. otherwise, with the session gate on, a `qits-session` **cookie** is introspected at idp and a
   live one is let through — a **gate, not an identity**: nothing about the person is written
   upstream. It is what keeps full pages such as a docs bundle or swagger-ui private. **The cookie
   is kept**: the browser's next request carries it too;
3. a caller holding neither still gets that app's **anonymous reads** (`anonymous-read-apps`), so
   `docker pull`, `npm install` and `git clone` work on exactly the names they work on today — and,
   on the name that **owns** them, the `anonymous-prefixes`, which is what serves `/idp/login` on
   `idp.dev.example.com`. Every other host refuses that prefix: it opens one service, not a path on
   every name;
4. anything left is refused in the shape the caller can act on: a navigation gets the login
   redirect, and everything else gets the `WWW-Authenticate` challenge — `docker` on `/v2/` above
   all, which acts on the realm and would give up without it.

With the session gate off, and for a configured application with no published host, this is the gate
exactly as it stood.

**`X-Qits-Caller` names the application whose page a request comes from** (qits-1198), on every
proxied request and independent of the four branches above — except that a service bearer's own
`X-Qits-Caller`, where it sent one, is kept rather than replaced. A service stamps it on its own outbound
calls; a browser cannot, so the edge asserts it after the same strip. The page is the request's
`Origin`, else the origin of its `Referer` (a same-origin GET sends no `Origin`); it must be one CORS
admits under the stated domain, and its name is then read exactly as a request to it would be routed
— `ci.dev.acme.<domain>` is whoever published `ci` in dev, an env-less project's own name is its
landing, `registry.…` is the application `PlatformApps` says it fronts. A foreign page, a door, an
unknown label or no page at all asserts nothing. The value is the deployed identity, the same as the
caller's OTel `service.name` (`qits-landing`, `qits-ci`), and the edge records it on its own server
span as `qits.caller` too. It is attribution, not a credential: nothing may authorise on it.

The cookie is removed from every request that did **not** use it, which is what keeps a
parent-domain session out of registry, mirror and git-host traffic. Unrelated cookies and all
machine credentials pass through unchanged.

**The return target is two allow-lists, not a reflected URL.** The edge accepts only a configured
browser authority and a single-slash path; the IdP validates the same authority once more before
the SPA navigates. `//host`, `/\host`, an absolute URL, a control character, or an unlisted host
lands at the apex front door.

An entry may be `*.<authority>`, and it matches **exactly one extra label** in front of that
authority, port included. Every service of an environment is its own browser host now, so listing
them would be a second copy of the deployment's application list; the wildcard is one line that
follows it. It is not a suffix check: `a.b.dev.example.com` is a different site to a browser and is
refused.

**A dead cookie still reaches `/idp/`** on idp's own host. The prefix answers every caller with no
usable credential, not only the ones carrying none — otherwise a browser holding a revoked session
would be redirected to a login page it is refused at, forever. This is the one place the order
differs from the plan's, and the reason is that loop. The refused cookie does not ride along: the
request is not using it.

### Introspection, and the cache in front of it

The cookie is **opaque** — 256 random bits, stored hashed at idp — so this process cannot decide
anything about it alone: it `POST`s `<qits.edge.idp.dial-url>/api/sessions/introspect` with its own client id
and secret in HTTP Basic and reads `{userId, username, roles, expiresAt}`. A non-200 is a refusal.
The alternative, a signed cookie verified offline against the JWKS already held here, would cost
revocation — a logout would be a row idp changed and nobody read.

The dial is bounded exactly like every other call at idp (`idp-call-timeout-ms` per attempt,
connection-classed retries inside `idp-retry-window-ms`, an **answer** never retried). The result is
cached against a **SHA-256 of the cookie** — never the cookie — in a bounded LRU, for
`cache-ttl-ms`. Refusals are not cached: a browser that has just logged in must not keep being
refused.

`stale-grace-ms` is the lesson the token broker paid for on 2026-08-14: idp is redeployed like any
other container, and for a few seconds its name refuses or never answers. A machine retries a push
and nobody notices; a person is logged out mid-click. So a session idp has already vouched for
answers for that much longer while idp is **unreachable** — never when idp *answers* no, and never
past the session's own `expiresAt`, so it widens no door that was open.

**Revocation lags by `cache-ttl-ms`** (30 seconds by default). Stated so nobody files it as a bug.

### Telemetry

Traces, logs and metrics leave over OTLP `http/protobuf` to qits-observability, exported by
qits-telemetry-core's three CDI beans rather than Quarkus' own exporter (epic qits-428, then
qits-2188) — the beans attach the `qits` oidc client's current bearer to every export, which
Quarkus' own exporter never could. One key names the receiver — `qits.observability.url`, host and
port with no path — and `qits.telemetry.endpoint` derives the ingest path from it, because that path
belongs to the receiver rather than to the deployment. `OtelLogConfigTest` pins the endpoint, the
bearer settings and the four log keys so a changed Quarkus default cannot switch log export off with
a green build.

**The bearer is why the private edge exports at all (qits-2188).** The public edge still dials the
internal `qits.observability.url` alias below and never left qits-net, so an authenticated export
costs it nothing extra; the private edge's `qits.observability.url` is qits-observability's public
name (see "The private edge" above), which answers an anonymous POST with 401 same as any other
peer's. Both variants read the identical `qits.telemetry.endpoint` key — only
`qits.observability.url` underneath it moves — so there is one exporter configuration for both,
not two.

The SDK is **disabled under `%dev` and `%test`**: a clone-alone `./mvnw verify` has no receiver to
reach, and an exporter retrying against an unresolvable name turns the suite into a wall of export
failures. Telemetry is real in a deployment.

**One exception: the OTLP ingest this edge proxies gets no span of its own** (qits-1432). Once
every service exports its telemetry through this edge rather than straight to qits-observability,
each export batch is itself a proxied request under `/observability/api/otel/` — and without the
exception, that request opens its own edge span, which is exported in the next batch, which proxies
through here too: a trace reporting on itself for ever. Two halves, because the edge opens a span on
each side of the hop:

- the inbound **SERVER** span is dropped by `quarkus.otel.traces.suppress-application-uris=
  /observability/api/otel/*`, Quarkus' own escape hatch for exactly this shape. It reads off the
  Vert.x HTTP instrumentation's own attributes, set from `receiveRequest` before this process' own
  catch-all route ever runs, so it reaches a proxied request as readily as a JAX-RS one;
- the outbound **CLIENT** span — the edge's own reverse-proxy hop to the upstream, instrumented by
  the identical Vert.x tracer on the one `Vertx` this whole process shares — is a different
  `SpanKind` the config key never reaches (and a different attribute, `url.full` rather than
  `url.path`, even if it did), so `ObservabilityIngestSampler` drops it directly: a CDI `Sampler`
  bean Quarkus wraps with the same config key, so both halves apply together rather than one instead
  of the other.

Dropping is not refusing: the request is proxied exactly as any other, with the caller's
`Authorization` passing through unchanged, and a dropped span still lets its trace id propagate to
whatever is downstream of it. Nothing else on this path is suppressed — logs and metrics for the
same request still leave as they would for any other.

### TLS: wildcard certificates through DNS-01

The `acme/` module is the edge's ACME client. For the **stated domain** — `qits.edge.domain`, the
same value the router reads every name against, never a key of ACME's own — it orders one SAN
certificate whose names are **derived**, in four tiers, because a wildcard covers exactly one label:

| tier | shape | reaches |
| --- | --- | --- |
| apex | `wohlben.eu`, `*.wohlben.eu` | `idp.wohlben.eu`, and nothing under it |
| environment | `*.<env>.<domain>` | `ci.dev.wohlben.eu` |
| project | `*.<slug>.<domain>` | `ci.acme.wohlben.eu` |
| project × environment | `*.<slug>.<env>.<domain>` | `ci.acme.dev.wohlben.eu` |

The third tier is now a name that **resolves and verifies and is not served**: `ci.acme.wohlben.eu`
states no environment, so the routing model above answers it 404 with the explicit spelling. It stays
on the certificate deliberately — the TLS handshake happens before that answer, and a name whose
refusal arrives as a certificate error is a browser page nobody can read. It costs one SAN per
project against the ceiling below.

**The project tiers are fed by events, not by configuration.** qits-projects publishes
`ProjectCreated` and `ProjectDeleted`; `ProjectLifecycleSubscriber` projects them into `edge_project`
(a durable, replay-from-epoch consumer, tombstoned so a late create cannot
resurrect a deleted project) and `EdgeProjects.slugs()` is what the name set is built from. A
project created on Tuesday is on the certificate on Tuesday — which is what retired the older
arrangement, where a project's host reached the certificate only if somebody remembered to add it to
a bootstrap key.

A create **requests** a reconcile rather than performing one: the request is debounced by
`qits.edge.acme.reconcile-debounce` (30s), because epoch replay delivers every `ProjectCreated` ever
published in one burst and an order per frame would spend Let's Encrypt's five duplicate
certificates a week within seconds. A **delete orders nothing at all** — every name on the installed
certificate still answers, so the surplus wildcard ages out at the next renewal.

**`QITS_EDGE_ACME_ADDITIONAL_NAMES` remains, for names no tier describes:**

```
QITS_EDGE_ACME_ADDITIONAL_NAMES=status.acme,status.gizmo.wohlben.eu
```

Whole or relative to the domain; `status.acme` is `status.acme.<domain>`. It is a list of **names**
and knows nothing about projects. The bootstrap owns the list (`QITS_ACME_EXTRA_SANS`) and checks it
before the run, because the edge answers its challenges in this domain's own zone and one name
outside it fails the whole order. Unset orders exactly the derived set; a name added reaches the
certificate at the next order — the 12h reconcile, or a restart at once.

**The ceiling is 100 names**, which Let's Encrypt refuses past rather than trims. `CertificateNames`
caps first, by dropping whole **project** tiers — from the end of the slugs' sorted order, a project
wholly on or wholly off, until the set fits — and the manager logs an `ERROR` naming every dropped
slug on every reconcile while any are dropped. The arithmetic is `2 + E + P + P·E + A`, and the term
that grows on its own is `P·E`: one project on a three-environment edge is four names, so a
three-environment edge reaches the ceiling at its twenty-fourth project.

It used to **refuse** there instead, which read as the cheaper failure and was the more expensive
one: the refusal was raised while building the name set, which every reconcile does before it knows
whether it has anything to order, so one project past the ceiling stopped **expiry renewals** too
and the platform's only TLS terminator would have gone dark ninety days later with one log line per
reconcile to say so. The apex, `*.<domain>`, the environment tiers and the additional names are
never dropped — when they alone exceed 100 the derivation still throws, because that is a
deployment to correct rather than a projection that grew. The manager still warns at 90.

The manager writes short-lived `_acme-challenge` TXT values through Hetzner's Cloud API — through
the qits-egress gateway on the deployed edge, directly on the seed edge (see below) — waits until
both Cloudflare and Google public DNS-over-HTTPS resolvers observe them, and removes only the value
it created. ACME account state and certificates persist on the TLS volume. Successful certificates
are installed in immutable version directories and an atomic `current` symlink switch lets Quarkus'
TLS registry reload them without restarting the edge.

The same API path also writes every public name and the private hosts' `A` records (labelled
`qits-exposure=public` / `private`), and that does not depend on `QITS_EDGE_ACME_ENABLED` — see
[Host records in public DNS](#host-records-in-public-dns-qits-2273-qits-2292).

`QITS_EDGE_ACME_ENABLED` and `QITS_EDGE_ACME_MODE` are the two keys that decide whether there is a
certificate to own; the domain is not one of them any more. The image defaults them to `true` and
`production` in the `prod` profile (qits-2009), and the TLS key store is an image default as well:
the PEM pair `/work/.letsencrypt/current/lets-encrypt.crt` / `.key` that the manager installs, with
a one-minute reload period. An installation states the ACME keys only to turn ACME off
(`QITS_EDGE_ACME_ENABLED=false`) or to order from staging (`QITS_EDGE_ACME_MODE=staging`, worth it
until the whole DNS path works); an environment variable overrides any of these defaults. Quarkus
refuses to start when the PEM files cannot be read, so a variant without a certificate on that path
must override the key store too. `deployments.yml` declares the same five values as
`image_defaults:`, so the deployer retires any stored setting that merely restates one, once a
deployment is ACTIVE; on 2026-11-10 that retired the five rows imported from qits-configuration,
leaving `QITS_EDGE_ACME_EMAIL` and the Hetzner token as the edge's only settings — and the token has
since moved to qits-egress (qits-2617, below). Change a default in `application.properties` and its
`image_defaults` pair together. A non-expiring production certificate is never replaced by staging.
Replicas use a database lease so only one of them can place an order or renew at a time.

#### Who holds the Hetzner token (qits-2617)

Every Hetzner call — the DNS-01 TXT (`HetznerDnsChallengeProvider`) and the host records
(`HetznerAddressRecords`), all on `zones/<zone>/rrsets` — goes to
`qits.edge.acme.hetzner-api-url` (`QITS_EDGE_ACME_HETZNER_API_URL`) with the bearer `HetznerAccess`
decides per pass:

| edge | base URL | bearer | who holds the Hetzner token |
| --- | --- | --- | --- |
| **deployed** `qits-edge` | default `http://${QITS_ENVIRONMENT}-qits-egress:8081/hetzner/v1/` | the edge's own `qits` oidc-client access token (sub `<env>-qits-edge`), kept until a minute before it expires | qits-egress only: its secret setting `QITS_EGRESS_SECRET_HETZNER_DNS` |
| **seed** edge of a bootstrap stack | `https://api.hetzner.cloud/v1/`, set by the stack | the Hetzner token from `QITS_EDGE_ACME_HETZNER_TOKEN_FILE=/run/secrets/qits-dns-hetzner-token` | the bootstrap's swarm secret |

The gateway authenticates the edge from its bearer, strips it, injects the Hetzner token for the
`hetzner` route and forwards `/hetzner/v1/zones/…` to `https://api.hetzner.cloud/v1/zones/…`. The
grant is this repository's `.config/qits/egress.yml`: route `hetzner`, target
`https://api.hetzner.cloud`, secret `HETZNER_DNS`, inject `bearer`, methods `GET, POST, PUT, DELETE`
— exactly the methods `HetznerApi` sends. A new Hetzner call with another method needs that list
widened in the same change.

**By certificate, no bearer (epic qits-2626, qits-2760).** The deployed edge keeps a workload
certificate for `application:qits-edge` (qits-trust-core's `WorkloadCertificate`,
`qits.workload-certificate.enabled=true`): made in memory at boot, issued by qits-certificates for
its `qits` client's bearer, renewed at 2/3 of its day. While it holds one and no Hetzner token is
configured, every Hetzner request goes to the same route on qits-egress' TLS proxy listener,
`qits.edge.acme.hetzner-certificate-api-url` (default
`https://${QITS_ENVIRONMENT}-qits-egress:8082/hetzner/v1/`), with the certificate and **no
`Authorization`** — egress' audit line names it `principal=application:qits-edge
auth=certificate clientSerial=<hex>`. The client trusts the JDK's CAs or the platform anchors
(`PlatformTrust`), and checks the host name against egress' `DNS:<env>-qits-egress`.

- **Decided per request** (`HetznerAccess.route`, `HetznerRoute` in the acme module), so a
  certificate obtained or lapsed mid-pass changes the next call, not a later pass.
- **A cold boot never waits on the CA**: with no certificate held the calls take the 8081 gateway
  with the bearer, as above — which is how the edge's first ACME order goes out before
  qits-certificates answers.
- **One INFO line** names the path whenever it changes: `Hetzner DNS calls go to
  https://<env>-qits-egress:8082 by its workload certificate, no bearer` (or `… with the edge's qits
  bearer`).
- **Never in direct mode**: the seed edge sends its Hetzner token to api.hetzner.cloud whatever it
  holds. `qits.edge.acme.hetzner-by-certificate=false` switches the certificate path off.

**Direct mode is the seed edge's, and only because of the cold boot.** The seed edge needs a real
certificate before anything can be pulled or deployed, and qits-egress is deployed only by the
train through that edge; so the stack keeps a token there (owner decision on qits-2523). The mode is
chosen by whether a token is configured: `QITS_EDGE_ACME_HETZNER_TOKEN_FILE` (wins, read on every
pass) or `QITS_EDGE_ACME_HETZNER_TOKEN` / `QITS_DNS_HETZNER_TOKEN` by value. No token is the ordinary
deployed edge and is **not** a refusal — ACME being enabled no longer requires one. An EMPTY token
file still is: a mounted secret that came up empty is a broken deployment, and falling quietly to the
gateway would hide it. The token goes only where `QITS_EDGE_ACME_HETZNER_API_URL` points, so a
configured token with the default URL would send it to the gateway, which refuses it (no qits
identity) — direct mode needs both keys, as the seed stack sets them.

**When the gateway refuses.** qits-egress answers 403 for a call without a grant (the edge's
`egress.yml` not yet in the deployer's table), 502 `secret not configured` before
`QITS_EGRESS_SECRET_HETZNER_DNS` exists, 503 without a grant table; a 4xx from Hetzner comes back
through it unchanged. Each is thrown with the method, the path, the authority that answered, the
status and the body (no secret in either), and handled exactly like a Hetzner error: the certificate
reconcile logs it at ERROR and keeps the current certificate until its next run, `HostRecords` at
WARN until its next trigger.

### Deployment

The deployer must publish the port — this is the one container on the host reached from outside
docker:

```
-p 8080:8080
```

`.config/qits/deployments.yml` makes this a **platform** service, deployed into the platform
environment alone. There is no branch to deploy from: a deploy pulls the version coordinate the
release published.
One edge exists because there is one host port to bind, and it fronts every environment's services,
so it belongs to no tier. The target is also what makes the routing work: a platform service joins
every environment's per-application networks, so `<env>-qits-artifacts`, `<env>-qits-projects` and
every other platform alias resolves for every name in `qits.edge.environments`. An
environment service would reach only its own tier and answer 502 for the rest — which is exactly
the shape `UpstreamOutageIT`'s third beat drives on purpose.

## Building

```bash
./mvnw verify                       # unit tests + the end-to-end proxy suite (no docker, no network)
./mvnw package                      # JVM build -> target/quarkus-app/
./mvnw quarkus:dev                  # dev mode on :8100
sdk env && ./mvnw package -Dnative  # native binary -> target/qits-platform-edge (no docker)
./mvnw test -Dtest=HostEnvironmentsTest

docker build -t qits/platform-edge:latest -f docker/Dockerfile .
```

**A clone of this repository alone builds and tests green** — no monorepo, no submodule, no docker,
no network, no credentials. That is why the pom duplicates the platform's Quarkus and JDK versions
instead of inheriting them.

`.sdkmanrc` names `25.0.2-graalce`, so `sdk env` gives you a `native-image` and the compile runs
in-process. **A missing GraalVM does not fail the build**: Quarkus logs `Cannot find the
native-image ... Attempting to fall back to container build` and shells docker with a Mandrel image.
Green either way, so recognise the fallback by the image pull. Leave it working — it is what a
GraalVM-less CI gets — but it is not the declared path.

Spotless (google-java-format) runs at `process-sources`, so formatting is never a review topic.

## The suite

Everything end to end lives in **one** `@QuarkusTest` class per JVM, and it has to stay that way. A
WebSocket upgrade through `vertx-http-proxy` only survives the **first** Quarkus start in a JVM;
after a restart it silently degrades to a plain proxied GET, so the handshake fails with nothing
logged anywhere. It is a property of the test harness, not of this code (qits-gateway paid for
finding it, and works around it with a second surefire execution). A restart happens when a test
class needs a different configuration from the one before it — so one class, one test resource, one
start is the cheapest immunity. **Splitting `EdgeRoutingTest` is how the socket tests start failing
for no visible reason.**

`EdgeSessionGateTest` is the one exception and it pays the fare: the session gate is a boot-time
flag, so proving both of its states needs two starts. It runs in a **second surefire execution**,
which forks a second JVM in which its application is the first start — the same workaround
qits-gateway uses, spelled out in `pom.xml`. `EdgeRoutingTest` keeps the flag **off**, which is what
makes it the proof that off changes nothing: it is unchanged.

Two harness details worth knowing before they cost an afternoon:

- `EdgeClient` uses Vert.x's `RequestOptions.setServer` to say a name it does not resolve. Vert.x
  **ignores** that on `HttpClient.webSocket` and resolves for real, so the socket tests use the JDK
  client instead — which needs `-Djdk.httpclient.allowRestrictedHeaders=host`, set on surefire's
  `argLine` in `pom.xml`.
- `quarkus.http.test-port=0`. On the deployment host 8081 is the platform's own npm registry, so the
  Quarkus default fails with a bind error that reads like a flake.
- The **management interface starts with the suite** now, on Quarkus' default management test port,
  9001. It stays a fixed port because `@TestHTTPResource(management = true)` has to be able to name
  it. A bind error there is a busy 9001, not a flake either.

**The integration half is a different posture entirely** and is not in `./mvnw verify` by default:
the story catalogue (`*IT`) runs the **packaged** artifact as a launched process, with the browser
gate ON, against stand-ins on real sockets. That is where the strip claim stops being an in-JVM
assertion about a stub and becomes a fact about the bytes a service actually receives. See
[Userflows](#userflows--the-proofs-that-double-as-documentation) for the class list and the command.

## Userflows — the proofs that double as documentation

The catalogue lives in `service/src/test/java/eu/wohlben/qits/edge/*IT.java` and emits under
`service/target/userstories/`, one directory per story with `userflow.json`, `user-story.md` and a
self-contained `index.html`, plus a site index carrying the aggregate network of all of them.
The verify step of the release-request phase — `.config/qits/release.yml` declares the
`java-service` archetype and overrides no slot — publishes the bundle once per release-request fold
as `@userflows/qits-edge-platform-service` (the repository's own name, which is what
`userflows: true` means). That step **gates**, like every step of the composed pipeline: a red story
is a red verdict for the whole fold and holds the request.

**Nine stories, one launched artifact, one `StoryProfile`.** A `@TestProfile` is what failsafe
launches a process for, so a second profile would be a second front door; every story class names
that one, `ForwardAuthBootstrapIT` included.

| Class | Story | Edges |
| --- | --- | --- |
| `ForwardAuthBootstrapIT` | A forged identity never reaches the service, and the edge asserts none in its place | 7 |
| `ForwardAuthBootstrapIT` | The door serves nothing, and a health probe is never gated | 6 |
| `VhostRoutingIT` | One name, one service: the Host header is the whole routing decision | 7 |
| `SessionlessWallIT` | The 401 wall: nothing a stranger sends leaves this process | 5 |
| `AnonymousReadIT` | An open read is still not a forged identity | 5 |
| `SpaFreshnessIT` | A released SPA is on screen at once: the edge unfreezes the document that names the bundles | 9 |
| `UpstreamOutageIT` | A service that is not there is an answer, and never a wait | 6 |
| `StreamingPassthroughIT` | An interactive terminal crosses the edge, and it crosses it stripped | 4 |
| `StreamingPassthroughIT` | A long answer arrives as it is produced, not when it is finished | 3 |

**Every diagram is observed, never narrated**, and every one of its ends is somebody else's
recording:

- **In**, tapped inside `EdgeClient` — the framework's shipped rest-assured tap cannot be used here
  for the reason that class exists at all: this service routes on `Host`, and rest-assured derives
  that header from the URL it was given. **The label carries the vhost**, because on this service
  the name IS the routing decision and two of `VhostRoutingIT`'s three requests are otherwise
  identical.
- **Out**, from `StoryUpstream` — a Vert.x recorder standing in for `qits-projects`, `qits-docs` and
  `qits-platform-mirror`, and qits-service-mock's `MockService` standing in for
  `qits-platform-idp`. The split is not stylistic: the applications need a chosen response header
  (the cache rewrite), a non-JSON body (an `index.html`), an answer written over time (the
  streaming claim), an outage arm and a WebSocket upgrade, and canned JSON can do none of the five.
  idp needs exactly canned JSON, which is what that library is for.

**What only a far-side recording can say** is most of this catalogue: the reserved `X-Qits-*`
namespace arriving empty on an anonymous read and on a session alike (no identity is written in
its place, qits-1373), a person's cookie NOT arriving on a machine vhost, which of two services received an
identical request, and a request that arrived and was then dropped mid-answer.

**And what only a negative assertion can say** is the sharpest claim of all. `SessionlessWallIT`
ends with `assertNoEdgesFrom(qits-edge)` — *nothing left this process* — with all four far
sides up and answering 200s in the stories either side of it. `assertEdgeCount` on every story is
what catches a refused request quietly starting to be forwarded.

**Running them:**

```bash
./mvnw -pl service -am -DskipITs=false verify \
  -Dit.test=ForwardAuthBootstrapIT,AnonymousReadIT,SessionlessWallIT,SpaFreshnessIT,StreamingPassthroughIT,UpstreamOutageIT,VhostRoutingIT
```

`skipITs` stays `true` in `service/pom.xml` — see the comment there — so the class list is named on
the command line and in `.config/qits/userflow-stories`, one class name per line, which the
archetype turns into the verify step's `-Dit.test`. **A new story class goes into that file in the
same commit that adds it**, or it is written and never run. Class order is topological:
every story carries `@UserflowRunsAfter(ForwardAuthBootstrapIT.class)` so the oldest class owns
whatever a boot produces, and `UserflowClassOrderer` is registered as junit's *secondary* orderer in
`src/test/resources/application.properties` — the one seam quarkus-junit permits, because it ships
its own `junit-platform.properties` and surefire hard-fails on a local override.

Two mechanics worth knowing before they cost an afternoon:

- `StoryUpstream`'s recording is **cumulative for the whole run**, with the framework's per-source
  cursor deciding which slice belongs to which story. So `onlyRequestTo(path)` can only mean
  "exactly one" on a path exactly one story drives, which is why several stories own a path of their
  own (`/projects/api/version`, `/projects/api/repositories`, `/projects/settings`).
- Each story uses a **cookie value of its own**. `EdgeSessions` caches a belief against a
  fingerprint of the cookie, so two stories sharing one would make the second's introspection edge
  depend on how long the first took.

## Relationship to qits-gateway

**There is no qits-gateway any more.** It was the per-environment proxy that demultiplexed services
by PATH inside one tier, and it was retired when every service gained a public name of its own — a
second hop would have been a second address, a second origin and a second cookie scope for something
that already had one. This process is the only proxy tier on the platform.

Two things it left behind are still load-bearing here and are worth knowing the provenance of:
`EdgeCacheControl` is a port of its class (and of the defect that appeared when the retirement
dropped it), and the second surefire execution in `pom.xml` is its workaround for a Quarkus harness
bug where a WebSocket upgrade only survives the first application start in a JVM. Anything else in
this file that names it is history.

### The Hetzner token, deployer-era (2026-08-31)

*History since qits-2617: the deployed edge holds no Hetzner token any more; it calls Hetzner
through qits-egress, which holds it. See [Who holds the Hetzner token](#who-holds-the-hetzner-token-qits-2617).*

The deployer-managed edge container has no swarm secrets — qits-deployments'
extras grammar carries env, mounts, publishes, aliases and groups, and nothing
else — so `QITS_EDGE_ACME_HETZNER_TOKEN_FILE` pointing at
`/run/secrets/qits-dns-hetzner-token` names a file that exists only in the
bootstrap's seed stack. The live arrangement is the **by-value arm**:
`env.QITS_EDGE_ACME_HETZNER_TOKEN` in the edge's config-host extras, beside
where `QITS_EDGE_SESSIONS_CLIENT_SECRET` rode before the edge read its idp client
from the deployer's `idp:client` resource, which established that a secret may
ride there. The file arm wins when both are set, so the file entry must stay
deleted until a deployer `secrets[i]` facility exists — that facility is the
structural fix, and the day it lands this section inverts.
