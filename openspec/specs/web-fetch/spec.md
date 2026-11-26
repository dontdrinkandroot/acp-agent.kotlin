# web-fetch Specification

## Purpose

Defines `web_fetch`: which URLs the agent may retrieve, how redirects and non-public hosts are
handled, how the body is bounded and converted to text, and how the result is paged.

## Requirements

### Requirement: web_fetch retrieves http and https only, without writing to disk
`web_fetch` SHALL retrieve a URL over http or https and SHALL return its content as text in the
tool result. Any other scheme SHALL be refused, and the tool SHALL never write a file.

#### Scenario: A non-http scheme is refused
- **WHEN** the URL uses `file:`, `ftp:` or `data:`
- **THEN** the call fails naming the allowed schemes
- **Pinned by** `WebFetchToolTest` (unit)

#### Scenario: The content is returned in the result
- **WHEN** a text resource is fetched
- **THEN** the result carries the content and no file is created
- **Pinned by** `WebFetchToolTest` (unit), `E2eWebFetchTest` (e2e)

### Requirement: Non-public hosts are blocked by default
Unless `tools.webFetch.allowPrivate` is enabled, the tool SHALL refuse a host that is
loopback, link-local, site-local, any-local, multicast, an IPv6 unique-local or one of the
IANA special-purpose ranges, and SHALL refuse `localhost`, `*.localhost`, `*.local` and
`*.internal` by name.

#### Scenario: Loopback is refused
- **WHEN** the URL names `127.0.0.1` or `localhost`
- **THEN** the call fails with a blocked-host message
- **Pinned by** `WebFetchToolTest` (unit), `E2eWebFetchTest` (e2e)

#### Scenario: Special-purpose ranges are refused
- **WHEN** the host is a CGNAT, unique-local, benchmarking or metadata address
- **THEN** the call fails as blocked
- **Pinned by** `WebFetcherTest` (unit)

#### Scenario: Addresses just outside every blocked range pass
- **WHEN** the host is the address next to a blocked range
- **THEN** it is not refused
- **Pinned by** `WebFetcherTest` (unit)

#### Scenario: The opt-out enables private hosts
- **WHEN** `tools.webFetch.allowPrivate` is true
- **THEN** a loopback fetch succeeds
- **Pinned by** `E2eWebFetchTest` (e2e), `WebFetchToolTest` (unit)

### Requirement: Every redirect hop is re-validated
The tool SHALL follow at most five redirects itself and SHALL apply the host policy to every
hop, so a public URL cannot redirect the fetch into a private address.

#### Scenario: A redirect into a private host is refused
- **WHEN** a public URL redirects to a loopback address
- **THEN** the call fails before the private host is contacted
- **Pinned by** `WebFetcherTest` (unit)

#### Scenario: Too many redirects
- **WHEN** the chain exceeds five redirects
- **THEN** the call fails naming the limit
- **Pinned by** `WebFetchToolTest` (unit)

### Requirement: HTTP errors fail loudly
A non-success status after the redirects SHALL fail the call with the status and URL instead
of returning the error page's body.

#### Scenario: A 404 is an error
- **WHEN** the server answers 404
- **THEN** the call fails naming the status and URL
- **Pinned by** `WebFetchToolTest` (unit)

### Requirement: The body is bounded and must be text
The tool SHALL refuse a declared binary content type before downloading and SHALL refuse a body
larger than 20 MB, checking the declared length and the progressive read. A payload that
decodes to text containing a NUL byte SHALL be refused as binary.

#### Scenario: A declared binary type is refused
- **WHEN** the server declares an image or archive content type
- **THEN** the call fails with a hint to download it another way
- **Pinned by** `WebFetchToolTest` (unit)

#### Scenario: An oversize body is refused
- **WHEN** the body exceeds the cap, declared or while streaming
- **THEN** the call fails instead of loading it
- **Pinned by** `WebFetchToolTest` (unit)

#### Scenario: A lying content type is caught
- **WHEN** a text-typed response actually carries binary data
- **THEN** the call fails as binary
- **Pinned by** `WebFetchToolTest` (unit)

#### Scenario: Compressed responses are decoded
- **WHEN** the server answers with gzip or deflate encoding
- **THEN** the body is decoded transparently
- **Pinned by** `WebFetchToolTest` (unit)

### Requirement: HTML is converted to line-based text and paged like a file
HTML SHALL be converted to plain lines - block elements on their own lines, preformatted text
verbatim, list items bulleted, scripts and styles stripped - and rendered with the same
numbered-line window, per-line clamp, empty-content marker and continue footer as `read_file`.
Non-HTML text SHALL pass through unchanged.

#### Scenario: HTML becomes readable lines
- **WHEN** a page is fetched
- **THEN** its text is returned as plain numbered lines without markup or scripts
- **Pinned by** `WebFetchToolTest` (unit), `E2eWebFetchTest` (e2e)

#### Scenario: Paging works like a file read
- **WHEN** the page is longer than the requested window
- **THEN** a continue footer names the shown range and the next start line
- **Pinned by** `WebFetchToolTest` (unit)

#### Scenario: JSON passes through
- **WHEN** the fetched resource is JSON
- **THEN** the content is returned unchanged, with line numbers
- **Pinned by** `WebFetchToolTest` (unit)

### Requirement: web_fetch never asks for permission
`web_fetch` SHALL be offered in every mode, SHALL be classified as a fetch tool, SHALL have no
filesystem target and SHALL never ask for permission.

#### Scenario: Prompt-free in plan mode
- **WHEN** the session runs in plan mode and the model calls `web_fetch`
- **THEN** no permission request is sent
- **Pinned by** `E2eWebFetchTest` (e2e), `WebFetchToolTest` (unit)
