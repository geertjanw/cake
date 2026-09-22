# 🎂 Cake & Candles: Learn OpenTelemetry From Scratch

This is a hands-on demo for learning **[OpenTelemetry](https://opentelemetry.io)** (OTel),
the vendor-neutral standard for generating **traces, metrics, and logs** from your
software.

Instead of reading about spans and attributes in the abstract, you'll run a
small three-service Spring Boot application, send it a request, and watch that one request
turn into a real distributed trace, with business context, custom metrics, correlated logs,
and a few deliberate failures to investigate. (And then you'll send more!)

To *see* the telemetry, you need somewhere to send it. This demo uses
**[Dash0](https://www.dash0.com)**, the OpenTelemetry-native observability platform, as the
backend. Because everything the app emits is plain OpenTelemetry, nothing here is locked to
Dash0. Dash0 just gives us a clean place to explore traces, metrics, and logs, so we'll
point you to the right screen at each step.

> **New to observability?** You don't need to understand any of the terms yet. Each concept
> (span, trace, attribute, context propagation, metric, semantic convention) is introduced
> in place, the first time you actually produce one.

---

## What you'll learn

By the end you will have:

1. Run an app instrumented with the **OpenTelemetry Java agent** (zero-code instrumentation).
2. Watched a single HTTP request become **one distributed trace** across three services.
3. Seen the difference between **automatic** instrumentation (HTTP, JDBC, scheduling) and
   **manual** instrumentation written with the OpenTelemetry API (business spans, attributes,
   events, metrics).
4. Explored all **three OpenTelemetry signals** (traces, metrics, and logs) and seen how
   they correlate.
5. Investigated **failures** (timeouts, retries, partial failures) the way you would in a
   real incident.

Everything is done through concrete, copy-pasteable steps.

---

## The demo application

"Cake & Candles" is a birthday-party ordering system. You send it a party, and it orders a
cake and sends invitations. That single request flows through three services:

```
POST /parties ──► party-service ──► cake-service        (H2 inventory, slow oven)
                       │
                       └──────────► invitation-service  (one span per guest)
```

| Service | Port | What it does |
|---|---|---|
| `party-service` | 8080 | Front door. Works out the age from the birth date, orders the cake, sends invitations, and runs a scheduled "upcoming birthdays" job. |
| `cake-service` | 8081 | The bakery. Reserves a flavor in an H2 database, then "bakes", which takes longer the more candles there are. |
| `invitation-service` | 8082 | Sends one invitation per guest, and reports how many bounced. |

A few behaviours are wired in on purpose so there's something to observe:

- **Bake time grows with candles**, at `150 ms + 25 ms × candles`. Since candles equals age,
  older birthdays produce slower requests.
- **The oven times out** above 80 candles (age 81 and up), returning HTTP 504.
- **Lemon starts with only 3 in stock**, while other flavors start with 25. The 4th lemon
  order fails with 503 until you restock.
- **Invalid guest e-mails bounce** individually without failing the whole party.

Keep these in mind, because they're the raw material for the guided exercises later.

---

## Prerequisites

- **Docker** with Compose (the easy path), *or* **Java 21 + Maven 3.9** to run locally.
- A **Dash0 account**, which is where your telemetry lands.

### Step 0: Get a Dash0 account and credentials

> **Introducing Dash0.** OpenTelemetry produces the data, but it doesn't store or visualize it.
> You need a *backend* for that. Dash0 is an OpenTelemetry-native backend that ingests OTLP
> (the OpenTelemetry Protocol) directly, with no proprietary agent, so the same app can point
> at any OTLP backend without code changes.

1. Sign up at **[dash0.com](https://www.dash0.com)** (there's a free trial).
2. In Dash0, open **Settings → Auth Tokens** and, in **Auto-generated auth token**, click **Show token** next to **Token**, and copy it.
3. Open **Settings → Endpoints** and copy your **OTLP/gRPC endpoint**. It looks like
   `https://ingress.<region>.aws.dash0.com:4317`.

You'll paste both into a `.env` file in the next step.

---

## Step 1: Configure your environment

Copy the example file and fill in the two values from Step 0:

```bash
cp .env.example .env
```

Then edit `.env`:

```bash
DASH0_AUTH_TOKEN=<paste your ingestion token>
DASH0_ENDPOINT=https://ingress.<your-region>.aws.dash0.com:4317
```

> **What you just configured.** OpenTelemetry is driven almost entirely by
> [environment variables](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/).
> The app code never knows where telemetry goes. It just emits OTLP, and these variables
> tell the exporter *where* (`OTEL_EXPORTER_OTLP_ENDPOINT`), *how* (`OTEL_EXPORTER_OTLP_PROTOCOL=grpc`),
> and *with what auth* (`OTEL_EXPORTER_OTLP_HEADERS`, carrying your Dash0 token). See
> `docker-compose.yaml` for the full set. This separation, with instrumentation in the code
> and destination in config, is a core OpenTelemetry design principle.

---

## Step 2: Run the app

There are three ways to run the demo, and all three produce identical telemetry in Dash0.
Pick based on how close to production you want to be. The recommended path is a local
Kubernetes cluster, because it mirrors how the services usually run and sets up the Dash0
Kubernetes route (Option A). If you'd rather not touch a cluster, Docker Compose is the
simplest one-command option (Option B), and running on a plain JVM is handy when you're
editing the code and want a fast rebuild loop (Option C).

### Option A: Local Kubernetes (kind or k3s) (recommended)

Use this path if you want to see the demo the way it usually runs in production (as pods in a
cluster) and set up the Dash0 Kubernetes route.

Requires:

| Technology | Why | Where to get it |
|---|---|---|
| A local Kubernetes cluster (**kind** or **k3s**), already running | Runs the services as pods | kind: [kind.sigs.k8s.io/docs/user/quick-start](https://kind.sigs.k8s.io/docs/user/quick-start/) · k3s: [docs.k3s.io/quick-start](https://docs.k3s.io/quick-start) |
| `kubectl` | Apply manifests and talk to the cluster | [kubernetes.io/docs/tasks/tools](https://kubernetes.io/docs/tasks/tools/) |
| Docker | Builds the images before loading them into the cluster | [docs.docker.com/get-started/get-docker](https://docs.docker.com/get-started/get-docker/) |
| `curl` and `jq` *(jq optional)* | Send requests and pretty-print responses (or use the browser form) | [curl.se/windows](https://curl.se/windows/) · [jqlang.github.io/jq/download](https://jqlang.github.io/jq/download/) |

**This path assumes the cluster is already up.** Bring one up first, for example
`kind create cluster` or a running k3s install.

The manifests live in `k8s/`. There's no in-cluster registry, so you **build the images
locally and load them into the cluster**.

**1. Build the three images:**

```bash
./scripts/build-images.sh
```

This produces `cake-and-candles/party-service:latest`, `.../cake-service:latest`, and
`.../invitation-service:latest`.

**2. Load them into your cluster.** The manifests use `imagePullPolicy: IfNotPresent`, so the
cluster uses these local images instead of trying to pull from a registry.

- **kind:**
  ```bash
  for svc in party-service cake-service invitation-service; do
    kind load docker-image "cake-and-candles/$svc:latest"
  done
  ```
- **k3s:**
  ```bash
  for svc in party-service cake-service invitation-service; do
    docker save "cake-and-candles/$svc:latest" | sudo k3s ctr images import -
  done
  ```

**3. Create the namespace and your Dash0 credentials.** The auth token goes in a Kubernetes
`Secret`, never in a manifest. The rest of the OTLP config is a `ConfigMap`.

```bash
kubectl apply -f k8s/namespace.yaml

# Load DASH0_* values from your .env, then create the Secret from them.
set -a && source .env && set +a
kubectl create secret generic dash0 -n cake-and-candles \
  --from-literal=OTEL_EXPORTER_OTLP_HEADERS="Authorization=Bearer ${DASH0_AUTH_TOKEN},Dash0-Dataset=${DASH0_DATASET:-default}"
```

> **Concept: Secrets vs ConfigMaps.** The token is sensitive, so it lives in a `Secret`
> injected as `OTEL_EXPORTER_OTLP_HEADERS`. Everything else (the OTLP endpoint, exporters,
> resource attributes) is non-secret and lives in `k8s/otel-shared.yaml`, shared by all three
> Deployments via `envFrom`. It's the exact same set of variables as `docker-compose.yaml`.
> Only the delivery mechanism changed.

**4. Point at your Dash0 region.** Edit `OTEL_EXPORTER_OTLP_ENDPOINT` in
`k8s/otel-shared.yaml` if your region isn't `eu-west-1`, then apply everything:

```bash
kubectl apply -f k8s/
```

**5. Wait for the pods, then reach party-service** via a port-forward (works identically on
kind and k3s):

```bash
kubectl -n cake-and-candles rollout status deploy/party-service
kubectl -n cake-and-candles port-forward svc/party-service 8080:8080
```

Leave the port-forward running. `localhost:8080` now reaches the cluster, so **Step 3 and all
later steps work unchanged.**

> **Where Dash0 fits on Kubernetes.** In this path each pod still runs the Java agent and
> exports OTLP straight to Dash0. In a real cluster you'd more often install the
> **[Dash0 Kubernetes operator](https://www.dash0.com/documentation/dash0/key-features/manage-dash0-with-kubernetes)**
> (via Helm). It runs an OpenTelemetry **Collector** in-cluster that receives your OTLP,
> enriches every signal with Kubernetes context (`k8s.pod.name`, `k8s.namespace.name`,
> `k8s.deployment.name`), and forwards to Dash0. You'd then point the apps at the in-cluster
> Collector instead of at Dash0 directly. That's the same "change config, not code" idea from
> Step 1, and it's covered under *Sending through an OpenTelemetry Collector* below.

To tear the demo down, run `kubectl delete namespace cake-and-candles`.

### Option B: Docker Compose

Requires:

| Technology | Why | Where to get it |
|---|---|---|
| Docker Engine + Compose | Builds and runs all three services in containers | [docs.docker.com/get-started/get-docker](https://docs.docker.com/get-started/get-docker/) (Docker Desktop bundles Compose) |
| `curl` | Send requests from the terminal (or use the browser form instead) | Preinstalled on macOS/Linux, or on Windows: [curl.se/windows](https://curl.se/windows/) |
| `jq` *(optional)* | Pretty-print JSON responses in the examples | [jqlang.github.io/jq/download](https://jqlang.github.io/jq/download/) |

```bash
docker compose up --build
```

The first build takes a few minutes: Maven compiles all three services and the Docker image
downloads the OpenTelemetry Java agent. When you see all three services logging, it's ready.

> **What is the OpenTelemetry Java agent?** It's a `.jar` attached to the JVM with
> `-javaagent:opentelemetry-javaagent.jar` (see the `Dockerfile`). It rewrites bytecode at
> startup to automatically create spans for HTTP servers and clients, JDBC queries, Spring
> `@Scheduled` jobs, and more, **with no changes to your code**. This is *zero-code
> instrumentation*, and it's the fastest way to get useful telemetry out of an existing app.

### Option C: Locally without Docker

Requires:

| Technology | Why | Where to get it |
|---|---|---|
| JDK 21 | Compiles and runs the services | [adoptium.net/temurin/releases](https://adoptium.net/temurin/releases/) (or `brew install temurin@21` / `sdk install java 21-tem`) |
| Maven 3.9+ | Builds the three modules | [maven.apache.org/download](https://maven.apache.org/download.cgi) (or `brew install maven`) |
| `curl` | Send requests from the terminal (or use the browser form instead) | Preinstalled on macOS/Linux, or on Windows: [curl.se/windows](https://curl.se/windows/) |
| `jq` *(optional)* | Pretty-print JSON responses in the examples | [jqlang.github.io/jq/download](https://jqlang.github.io/jq/download/) |

The script downloads the OpenTelemetry Java agent, builds all three services, and starts them:

```bash
./scripts/run-local.sh
```

Each service logs to `<service>.log` in the repo root. Press `Ctrl-C` to stop everything.

---

## Step 3: Send your first request and read your first trace

You can throw a party two ways. Both hit the exact same `POST /parties` endpoint and
produce the exact same telemetry, so pick whichever you prefer.

**A. In the browser.** Open **<http://localhost:8080/>**. party-service serves a small form
(name, birth date, flavor, guests). Fill it in and click *Throw the party*. The response,
including the party status, appears below the form.

> **Is there a "real" app UI?** No. The three services are headless REST APIs, and Dash0 is
> the UI that matters for this demo, because that's where the trace shows up. The form is a
> thin convenience client over the same JSON endpoint as `curl`, and it changes nothing about
> the telemetry. It lives at `party-service/src/main/resources/static/index.html`, served
> automatically by Spring Boot.

**B. With `curl`** (equivalent, and handy for scripting and reproducing exact payloads):

```bash
curl -s -X POST localhost:8080/parties -H 'Content-Type: application/json' -d '{
  "name": "Ada",
  "birthDate": "1990-12-10",
  "flavor": "chocolate",
  "guests": ["grace@example.com", "linus@example.org"]
}' | jq
```

Now open **Dash0 → Tracing** and filter on `service.namespace = cake-and-candles`. Within a
few seconds your request appears as a single trace. Click it.

> **Concept: traces and spans.** A **span** is one timed unit of work, such as an HTTP call, a
> database query, or a function you chose to time. A **trace** is the tree of all spans for one
> logical operation. Your one request produced a trace whose root is the `POST /parties`
> server span, with child spans nested underneath.

In this trace you should see, top to bottom:

- `POST /parties`, the root server span (created automatically by the agent in party-service).
- An HTTP **client** span where party-service calls cake-service.
- `bake cake`, a **hand-written** span in cake-service, carrying `cake.flavor` and
  `cake.candles` attributes.
- Two JDBC spans (`SELECT ... FOR UPDATE`, `UPDATE`) under it, created automatically.
- `oven`, another hand-written span. This is the slow bit you can see in the timeline.
- A fan-out of `send invitation` spans in invitation-service, one per guest.

> **Concept: context propagation.** How did all three services end up in *one* trace? When
> party-service calls the others over HTTP, the agent injects the current
> [trace context](https://opentelemetry.io/docs/concepts/context-propagation/) into request
> headers (the W3C `traceparent` header). The receiving service reads it and continues the
> same trace instead of starting a new one. You get this for free from the agent, and it's why
> distributed traces "just work."

> **Concept: attributes.** Notice `party.age`, `cake.flavor`, `cake.candles`, and
> `party.guest_count` on the spans. **Attributes** are key/value metadata that make a span
> searchable and groupable. In Dash0 you can now filter traces by `party.age > 70` or group
> by `cake.flavor`. Good attributes are *low-cardinality and meaningful*. The demo attaches
> age and flavor (great for grouping) but keeps the unique `party.id` off of metrics.

---

## Step 4: Generate some traffic

Let's move from one trace to a stream of them, which is where observability gets interesting.
This script sends a realistic mix: mostly happy parties, a few 90-year-olds (oven timeouts),
some bad e-mail addresses (partial failures), and the occasional run on lemon cake (out of
stock):

```bash
./scripts/throw-parties.sh 50 2      # 50 parties, 2 seconds apart
```

Leave it running and move on to the signals below.

---

## The three signals

OpenTelemetry defines three **signals**. You've already seen traces, so here's where to find
the other two in this demo.

### Traces

Covered above. The demo mixes automatic spans (HTTP, JDBC, scheduled jobs) with hand-written
business spans (`bake cake`, `oven`, `send invitation`, `count upcoming birthdays`). Explore
them in **Dash0 → Tracing**.

### Metrics

> **Concept: metrics.** Where a trace describes *one* operation, a **metric** is an aggregate
> (a count, a rate, a distribution) over *many*. OpenTelemetry has three instrument types
> you'll see here: **counters** (monotonic totals), **histograms** (value distributions, for
> latency), and **observable gauges** (a value sampled on an interval).

The demo emits the following, all with low-cardinality attributes only:

| Metric | Type | Where | Attributes |
|---|---|---|---|
| `parties.planned` | counter | party-service | `party.outcome`, `cake.flavor` |
| `parties.stored` | gauge | party-service | (none) |
| `cakes.baked` | counter | cake-service | `cake.flavor` |
| `candles.lit` | counter | cake-service | `cake.flavor` |
| `oven.failures` | counter | cake-service | `cake.flavor`, `failure.reason` |
| `bake.duration` | histogram | cake-service | `cake.flavor` |

Plus **JVM runtime metrics** (heap, GC, threads) contributed automatically by the agent.

Open **Dash0 → Metrics** and try charting `bake.duration` (p95) grouped by `cake.flavor`, or
`parties.planned` grouped by `party.outcome`. These are the building blocks of dashboards and
alerts.

### Logs

> **Concept: correlated logs.** OpenTelemetry can export your application logs over OTLP too,
> and the agent stamps each log line with the **trace ID and span ID** of the request that
> produced it. That's the payoff: from any span in a trace, you can jump straight to the exact
> log lines emitted during it, with no grepping across servers by timestamp.

In **Dash0 → Logs** (or from a span, follow the link to its logs) you'll see lines like
`Planning Ada's 30th birthday...`, `Baked a chocolate cake...`, and `sent 2 invitations, 1
failed`, all lining up under the same trace.

---

## Guided exercises

Each of these maps a thing you *do* to an OpenTelemetry concept you can *see*.

**About "throw a party for someone turning N".** Every exercise below just asks you to send a
party request whose `birthDate` makes the person N years old today. Age becomes the candle
count, and the candle count drives bake time and the oven timeout. Send it however you like:
type a birth date into the form at **<http://localhost:8080/>**, or run the `curl` command
shown.

Run the app, then try them in order:

1. **Happy path: read a full trace.** Throw a party for a 30-year-old with valid guests (the
   Step 3 request). Walk the span tree end to end. *Concept: spans, trace tree, parent/child.*

2. **Latency you can explain: histograms.** Throw a party for someone turning 75. That's 75
   candles, so the `oven` span is now about 2 seconds.
   ```bash
   curl -s -X POST localhost:8080/parties -H 'Content-Type: application/json' \
     -d '{"name":"Grace","birthDate":"1951-03-01","flavor":"vanilla","guests":["a@example.com"]}' | jq
   ```
   Compare `bake.duration` grouped by `cake.flavor`, or plot request latency against
   `party.age`. *Concept: histograms, attribute-based grouping.*

3. **An error: spans record exceptions.** Throw a party for someone turning 95. That's over
   the 80-candle limit, so cake-service returns 504, the `oven` span carries the exception and
   an ERROR status, party-service marks its own span as an error and returns 502, and
   `oven.failures{failure.reason=oven_timeout}` increments. *Concept: span status, recorded
   exceptions, error metrics.*

4. **A retry: sibling spans in one trace.** Order lemon cakes until the 4th fails (lemon
   starts at 3). party-service retries the 503 up to three times with backoff, showing three
   sibling client spans in one trace, then a `FAILED` party. Restock and watch the next one
   succeed:
   ```bash
   curl -s -X POST localhost:8081/inventory/lemon/restock?amount=10 | jq
   ```
   *Concept: retries visible as sibling spans, span events (`ordering cake, attempt 2`).*

5. **A partial failure: filter by attribute.** Include `"not-an-email"` in the guest list.
   The request returns 201 with status `PARTIAL`, and inside the trace exactly one `send
   invitation` span is red. Filter for `invitation.status = failed`.
   ```bash
   curl -s -X POST localhost:8080/parties -H 'Content-Type: application/json' \
     -d '{"name":"Linus","birthDate":"2000-06-15","flavor":"chocolate","guests":["ok@example.com","not-an-email"]}' | jq
   ```
   *Concept: partial success, per-child span status, attribute filtering.*

6. **Trace-to-logs correlation.** From any span, jump to its logs. Every service logs with the
   trace ID attached, so the lines for one party line up under one trace. *Concept: correlated
   logs.*

7. **A non-HTTP trace: background work.** Wait about a minute and look for `count upcoming
   birthdays` traces from the scheduled `UpcomingBirthdaysJob`. They have no HTTP parent, a
   reminder that traces aren't only for requests. *Concept: manual root spans, `@Scheduled`
   instrumentation.*

8. **Build a dashboard or alert.** Combine what you've collected: parties by outcome, cakes by
   flavor, p95 of `bake.duration`, error rate on `POST /cakes`, JVM heap per service. *Concept:
   turning metrics into dashboards and alerts in Dash0.*

---

## How the instrumentation works

This is the part to look at if you're instrumenting your own app.

### 1. The agent does the work (zero code)

Every service runs with the
[OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
attached via `-javaagent:opentelemetry-javaagent.jar`.

That alone gives you:

- HTTP server and client spans
- JDBC query spans
- Spring `@Scheduled` spans
- **Context propagation** between services (the shared trace)
- **Log export** over OTLP with trace correlation

None of that required a single line of instrumentation code.

### 2. The code adds business context (OpenTelemetry API)

On top of the agent, each service depends only on `opentelemetry-api` (the API, **not** the
SDK) and calls `GlobalOpenTelemetry.get()`.

The agent supplies the real implementation at runtime. With that, the code adds what the agent
can't know about your domain:

- **Business attributes** on the agent's server span:
  `Span.current().setAttribute("party.age", age)`
- **Custom child spans** for steps that matter: `bake cake`, `oven`, `send invitation`,
  `count upcoming birthdays`
- **Span events** for notable moments: `flavor reserved`, `ordering cake, attempt 2`
- **Metrics**: counters, a histogram, and an observable gauge, with low-cardinality
  attributes only
- **Exceptions and error status** recorded on spans when things go wrong

Read `Bakery.java`, `Mailroom.java`, and `PartyPlanner.java`. The instrumentation is kept
inline and commented so you can follow it.

> **Why API-only, no SDK?** The app links against the vendor-neutral API. The agent provides
> the SDK and the exporter. This means the *same code* works whether telemetry goes to Dash0,
> a Collector, or any other OTLP backend. You change environment variables, not code.

### 3. Semantic conventions

[Semantic conventions](https://opentelemetry.io/docs/specs/semconv/) are OpenTelemetry's
shared naming rules. They only cover general technical concerns (HTTP, databases, RPC,
messaging, resource identity, and runtime), which is what lets a backend like Dash0 understand
that data automatically instead of you configuring every field. Here is exactly where this
demo does and doesn't follow them.

**Conformant, because a convention exists for it:**

- **Resource identity:** [`service.name`](https://opentelemetry.io/docs/specs/semconv/attributes-registry/service/#service-name)
  (set via `OTEL_SERVICE_NAME`), [`service.namespace`](https://opentelemetry.io/docs/specs/semconv/attributes-registry/service/#service-namespace),
  and [`service.version`](https://opentelemetry.io/docs/specs/semconv/attributes-registry/service/#service-version).
- **Everything the agent emits automatically:** HTTP server and client spans
  ([`http.request.method`](https://opentelemetry.io/docs/specs/semconv/attributes-registry/http/#http-request-method),
  [`url.path`](https://opentelemetry.io/docs/specs/semconv/attributes-registry/url/#url-path),
  [`http.response.status_code`](https://opentelemetry.io/docs/specs/semconv/attributes-registry/http/#http-response-status-code),
  and so on), JDBC and database spans ([`db.*`](https://opentelemetry.io/docs/specs/semconv/attributes-registry/db/)),
  and JVM runtime metrics ([`jvm.memory.*`, `jvm.gc.*`, `jvm.thread.*`](https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/)).
  The agent implements these to spec, so you get conformant telemetry for
  free.

**Custom, because no convention covers it.** There is no semantic convention for a
birthday-cake domain, so all of the *business* attributes and metrics are necessarily custom:
`party.age`, `party.outcome`, `cake.flavor`, `cake.candles`, `invitation.status`,
`invitation.recipient.domain`, `parties.planned`, `cakes.baked`, `oven.failures`,
`bake.duration`, and the rest. 
- They follow the convention *style* (dotted namespaces, low
cardinality, and PII kept off spans, which is why only `invitation.recipient.domain` is
recorded, never the full address) but they are not standardized names.
- A couple of the metric
names (`cakes.baked`, `candles.lit`) also bend OpenTelemetry's metric-naming guidance, which
prefers a `namespace.noun` shape over a pluralized past-tense verb.

---

## Endpoints reference

**party-service** (`:8080`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | A small web form for throwing a party (thin client over `POST /parties`). |
| `POST` | `/parties` | Plan a party. Body: `name`, `birthDate` (ISO date), `flavor`, `guests` (array of e-mails). Returns 201 (`PLANNED` or `PARTIAL`) or 502 (`FAILED`) if there's no cake. |
| `GET` | `/parties` | All parties held in memory |
| `GET` | `/parties/{id}` | One party |

**cake-service** (`:8081`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/cakes` | Bake a cake (called by party-service). 503 out of stock, 504 oven timeout. |
| `GET` | `/inventory` | Flavor stock levels |
| `POST` | `/inventory/{flavor}/restock?amount=10` | Restock a flavor |

**invitation-service** (`:8082`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/invitations` | Send invitations (called by party-service). Always 200, and the body reports how many failed. |

All services expose `/actuator/health`.

---

## Configuration knobs

| Variable / property | Default | Effect |
|---|---|---|
| `DASH0_AUTH_TOKEN` | (none) | Your Dash0 ingestion token (**required**) |
| `DASH0_ENDPOINT` | `https://ingress.eu-west-1.aws.dash0.com:4317` | Dash0 OTLP/gRPC ingress |
| `DASH0_DATASET` | `default` | Dash0 dataset to write to |
| `DEPLOYMENT_ENV` | `demo` | Sets the `deployment.environment.name` resource attribute |
| `bakery.oven.max-candles` (cake-service) | `80` | Candles above this cause an oven timeout |
| `bakery.oven.millis-per-candle` (cake-service) | `25` | Bake time per candle |
| `clients.cake.max-attempts` (party-service) | `3` | Retries on 503 |
| `jobs.upcoming-birthdays.rate` (party-service) | `60000` | Scheduled job interval (ms) |

Spring properties can be overridden with environment variables in Compose, for example
`BAKERY_OVEN_MAXCANDLES=40`.

---

## Sending through an OpenTelemetry Collector instead

The [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) is a common piece of
production setups: services send to a local Collector, which batches, processes, and forwards
telemetry to one or more backends.

To route this demo through a Collector, point `OTEL_EXPORTER_OTLP_ENDPOINT` at the Collector
and drop the `Authorization` header, because the Collector's `otlp` exporter handles auth to
Dash0. **Nothing in the services changes**, which is exactly the point of an OTLP-native
pipeline.

---

## Project layout

```
.
├── pom.xml                      # parent: Spring Boot 3.5, OpenTelemetry BOM, shared deps
├── party-service/               # orchestrator + scheduled job
├── cake-service/                # bakery with H2 inventory
├── invitation-service/          # per-guest fan-out
├── Dockerfile                   # multi-stage, MODULE build arg selects the service
├── docker-compose.yaml          # all three services + OTel agent env config for Dash0
├── .env.example                 # copy to .env, add your Dash0 credentials
├── k8s/                         # manifests for the local Kubernetes path (Option C)
│   ├── namespace.yaml
│   ├── otel-shared.yaml         # shared OTLP config as a ConfigMap (edit the endpoint)
│   ├── party-service.yaml       # Deployment + Service
│   ├── cake-service.yaml
│   └── invitation-service.yaml
└── scripts/
    ├── run-local.sh             # run without Docker
    ├── build-images.sh          # build the three images for Kubernetes
    └── throw-parties.sh         # traffic generator
```

---

## Learn more

- **OpenTelemetry** concepts and docs: <https://opentelemetry.io/docs/>
- **OpenTelemetry Java agent**: <https://github.com/open-telemetry/opentelemetry-java-instrumentation>
- **Semantic conventions**: <https://opentelemetry.io/docs/specs/semconv/>
- **Dash0**: <https://www.dash0.com>

## License

MIT. Do whatever you like with it.
