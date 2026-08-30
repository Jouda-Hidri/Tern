---
name: failure-drills
description: Manually verify Tern's documented failure modes on docker compose - stop libretranslate and stop antarctic, one at a time, and check the API answers what the README says it will. Use when asked to test what happens when a dependency is down, to check the degraded paths still behave, or to confirm a change has not altered them.
---

# Failure drills

Two dependencies are allowed to fail and the API is supposed to behave differently for each.
The detector is optional - it cannot stop a message being stored. Antarctic is not: without it
there is nothing to store into. This drill takes each one down in turn and checks the answers
against the table in the README's "When things break".

Everything here runs against docker compose on `localhost:8080`. It changes no code.

`EndToEndTest` asserts the same answers automatically, against a proxy that hangs or refuses on
command, and it runs in seconds. Use it for "did this change break the failure modes". Use this
drill for what a test with a stubbed seam cannot tell you: that the real topology - separate
containers, a real network, a real detector, gRPC's own reconnect behaviour - still behaves that
way. If the two ever disagree, the drill is right.

## Before starting

The detector lives behind a compose profile, so it needs the profile flag or it will not be
running to stop:

```
docker compose --profile translate up -d --build
docker compose ps
```

Wait for readiness rather than guessing - the JVM takes a few seconds and libretranslate can
take minutes on a cold start while it downloads models:

```
until [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/actuator/health/readiness)" = 200 ]; do sleep 1; done
```

Establish a baseline with everything up, so a failure later is attributable to the drill and
not to something already broken:

```
curl -i -X POST localhost:8080/ -H 'Content-Type: application/json' \
  -d '{"text":"Bonjour mon ami, comment vas-tu aujourd hui"}'
sleep 5
curl -s localhost:8080/stats
```

Expect `201`, and the message counted under `fr` in `/stats` straight away - the language is
written with the message, so there is no lag to wait out. Short inputs detect badly, so use a
whole sentence rather than `"Bonjour!"`; a message counted as `unknown` here usually means the
input was too short, not that anything is broken.

## Drill 1 - the detector is down

```
docker compose stop libretranslate
curl -i -X POST localhost:8080/ -H 'Content-Type: application/json' \
  -d '{"text":"Le detecteur est arrete, mais ce message doit etre stocke"}'
sleep 5
curl -s localhost:8080/stats
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/health/readiness
```

Expected, and all of it matters:

- `POST` answers **201**. The message is stored; an unavailable detector must never fail a write.
- It takes about `TRANSLATE_TIMEOUT` longer than usual. Detection is on the write path, so a
  detector that is down costs every `POST` that wait before it gives up. Time the call - if it
  returns as fast as the baseline, detection is not being attempted at all, which is its own bug.
- The new message is counted under **`unknown`** in `/stats`, and `unknown` sorts last.
- Readiness stays **200**. The detector is not part of the readiness group.
- **Antarctic**, not artic, logs one `WARN ... Translate - detection failed`. It owns detection,
  because it is the service writing the language to the database.

To confirm the row really is stored rather than merely counted:

```
docker compose exec -T dbpostgresql psql -U postgres -d polldb \
  -c "select text, coalesce(nullif(language,''),'<empty>') from messages order by text desc limit 3;"
```

Restore before moving on, and give it a moment to come back:

```
docker compose start libretranslate
```

## Drill 2 - antarctic is down

```
docker compose stop antarctic
```

This one has **two** phases and the order matters - checking too late is the usual way to
conclude wrongly that the first phase is broken.

**Phase 1, while gRPC still waits out `ANTARCTIC_DEADLINE` (2s).** The call may or may not have
landed, so the API refuses to claim either:

```
curl -i -X POST localhost:8080/ -H 'Content-Type: application/json' -d '{"text":"antarctic is down"}'
curl -i -s localhost:8080/
```

- `POST` answers **202** - accepted, outcome unconfirmed. Not 201, which would claim a write
  that may not have happened, and not 5xx, which would report a failure that may not have
  happened either.
- `GET` answers **504** `Antarctic is deadline exceeded`.

**Phase 2, once gRPC has marked the connection dead.** Repeat the same two calls a few times.
The request now definitely never reached the database, so both become **503**
`Antarctic is unavailable`, and they fail in milliseconds rather than after 2s. Timing the call
is the clearest way to see the change:

```
time curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/
```

Throughout both phases, readiness must stay **200**. This is deliberate: artic still works, and
failing readiness would pull it out of the load balancer too, turning one outage into two.

Restore:

```
docker compose start antarctic
```

The next request or two may still fail while gRPC backs off. That is expected - retry rather
than reporting a failure.

## What "stopped" actually means

`docker compose stop antarctic` leaves the name resolving to an address that drops packets, so
gRPC waits out the deadline. That is why phase 1 exists at all. A container that actively
*refuses* the connection skips straight to 503. To exercise that path deliberately, point a
throwaway artic at a dead port on a live container:

```
docker run --rm -d --name artic-refuse --network tern_default -p 8085:8080 \
  -e POSTGRES_PASSWORD=password -e POSTGRES_USER=postgres -e POSTGRES_DB=polldb \
  -e POSTGRES_HOST=dbpostgresql -e ANTARCTIC_TARGET=antarctic:9999 tern-artic
```

It answers `503` immediately, with no 202/504 phase. Remove it with
`docker rm -f artic-refuse` afterwards.

Pick the published port by checking it is free first - `lsof -nP -iTCP:8085 -sTCP:LISTEN`. A
stray `kubectl port-forward` holding the port will answer the curls itself, and its Envoy-shaped
error looks enough like a real response to be mistaken for one.

## Reporting

Report each drill as expected / not expected against the bullets above, quoting the actual
status codes and the `/stats` body. If something deviates, say which drill and which
expectation, and include the logs for the window - both services, since artic answers the
request but antarctic is what talks to the database and the detector:

```
docker compose logs artic antarctic --since 2m
```

Leave the stack in the state you found it: everything started, nothing stopped.
