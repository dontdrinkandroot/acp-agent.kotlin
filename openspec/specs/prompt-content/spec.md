# prompt-content Specification

## Purpose

Defines how the content blocks a client sends with `session/prompt` reach the model: which
blocks are forwarded, which degrade to placeholders, and how an empty prompt is represented.

## Requirements

### Requirement: Text content reaches the model unchanged
Text blocks of a prompt SHALL be forwarded as input text. Runs of consecutive text blocks
SHALL be joined into one text part, separated by newlines; text interrupted by a multimodal
block SHALL continue as a new part after it.

#### Scenario: Plain text prompt
- **WHEN** the client sends one text block
- **THEN** the model request carries that text as a single input part
- **Pinned by** `AgentSessionPromptContentTest` (unit), `E2ePromptCapabilitiesTest` (e2e)

#### Scenario: Text around an image stays ordered
- **WHEN** the prompt is text, image, text
- **THEN** the request carries two text parts around the image part, in that order
- **Pinned by** `AgentSessionPromptContentTest` (unit)

### Requirement: Image blocks are forwarded only to image-capable models
An image block SHALL be forwarded as a base64 data URI when the selected model lists image
input among its modalities, using `image/png` when the block carries no mime type. Otherwise
it SHALL degrade to a text placeholder stating that the selected model does not support image
input.

#### Scenario: Image to an image model
- **WHEN** the prompt carries an image block and the model supports image input
- **THEN** the request carries `data:<mime>;base64,<data>`
- **Pinned by** `AgentSessionPromptContentTest` (unit), `E2ePromptCapabilitiesTest` (e2e)

#### Scenario: Default mime type
- **WHEN** an image block carries no mime type
- **THEN** the data URI uses `image/png`
- **Pinned by** `AgentSessionPromptContentTest` (unit)

#### Scenario: Image to a text-only model
- **WHEN** the selected model does not list image input
- **THEN** the block becomes a text placeholder naming the omission, so the model knows content was dropped
- **Pinned by** `AgentSessionPromptContentTest` (unit)

### Requirement: Embedded resources are inlined, binary and audio content degrade
A text resource block SHALL be inlined as `Resource <uri>:\n<text>`. A blob resource SHALL
become a placeholder naming the omitted binary content, and an audio block SHALL become a
placeholder stating that audio is not supported.

#### Scenario: Text resource inlined
- **WHEN** the prompt carries a text resource block
- **THEN** the request carries the resource URI followed by its full text
- **Pinned by** `AgentSessionPromptContentTest` (unit), `E2ePromptCapabilitiesTest` (e2e)

#### Scenario: Binary resource placeholder
- **WHEN** the prompt carries a blob resource
- **THEN** the request carries a placeholder naming the URI, not the bytes
- **Pinned by** `AgentSessionPromptContentTest` (unit)

#### Scenario: Audio placeholder
- **WHEN** the prompt carries an audio block
- **THEN** the request carries a placeholder stating that audio is not supported
- **Pinned by** `AgentSessionPromptContentTest` (unit)

### Requirement: Resource links render as markdown links
A resource link block SHALL render as `[<title or name>](<uri>)`, falling back to the bare
URI when neither a title nor a name is given.

#### Scenario: Link with a title
- **WHEN** the prompt carries a resource link with a title
- **THEN** the request text carries `[<title>](<uri>)`
- **Pinned by** `AgentSessionPromptContentTest` (unit), `E2ePromptCapabilitiesTest` (e2e)

#### Scenario: Link without a label
- **WHEN** the resource link has no title and no name
- **THEN** the request text carries the URI alone
- **Pinned by** `AgentSessionPromptContentTest` (unit)

### Requirement: An empty prompt is represented explicitly
A prompt that yields no content part at all SHALL be represented to the model as
`(empty message)`.

#### Scenario: Empty prompt
- **WHEN** the client sends a prompt whose blocks produce no part
- **THEN** the request carries `(empty message)`
- **Pinned by** `AgentSessionPromptContentTest` (unit)
