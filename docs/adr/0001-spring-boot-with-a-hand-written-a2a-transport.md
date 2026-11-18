# 0001. Spring Boot with a hand-written A2A transport over the official library

- Status: Accepted
- Date: 2026-10-15

## Context

The home finance agent reviews a household's monthly spending across cards and asks a card agent
for one month's charges over the open A2A protocol. The answer is a stream by design: rows in
batches as they are ready, then possibly a pause asking for a missing statement. The official
a2a-java server library handles tasks, their states and the JSON-RPC methods, and its server
classes are written for CDI injection rather than Spring. Its Spring community integration answers
`SendStreamingMessage` with `UnsupportedOperation`, so it cannot serve the skill.

## Decision

- One Java 21 Spring Boot process per replica, with the official a2a-java server library for the
  A2A task model and its transport-neutral JSON-RPC handler.
- A small Spring MVC layer of its own in `a2a/`: one `POST /` endpoint that hands the body to the
  library and writes the library's events back as SSE frames, and the Agent Card endpoint.
- A2A payloads are read and written by the library's own JSON handling (Gson and protobuf);
  controllers take and return `String`, so Spring's JSON mapper never reshapes them.
- The card work lives apart, in `card/`, and reaches the library only through one executor bean.

## Consequences

- The skill streams, and the official A2A conformance suite runs against the real transport.
- The transport, its threading and its stream closing are this repo's to maintain; a library
  upgrade can change what the endpoint must hand over.
- Library defects show through unchanged, such as the blocking `SendMessage` timing out on tasks
  that completed, so clients use `SendStreamingMessage`.
- The protocol side knows nothing of PDFs or models, so the card pipeline changes without touching
  the transport.
