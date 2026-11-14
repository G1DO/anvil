# 0030 — Invoice upload uses multipart, not presigned URLs

Status: accepted 2026-11-14 (#170).

## Context

`POST /api/documents` accepts only JSON text. A PDF sent there is stored as a
document body and then quarantined as `UNSUPPORTED_MEDIA_TYPE` on extraction.
Invoices need their own upload route that keeps the original PDF bytes.

#145 chose the upload bounds: a 10 MiB (10,485,760 bytes) whole request body,
the same limit for JSON documents and invoice PDFs, and at most 50 pages for an
invoice PDF. The byte limit is already enforced before parsing by
`RequestBodyLimitFilter` and the servlet multipart limits. The page limit needs
a PDF parser, which #172 owns; #170 must not parse the PDF.

A presigned upload was the alternative. It scales better for very large files
and lets the client send bytes without holding an API request, but it adds a
second protocol, a second lifecycle (issue, expire, complete, abort) and a
place where bytes can be orphaned outside the API's transaction. At 10 MiB a
multipart request is small enough for the existing servlet limits, so the
simpler API is the right first choice.

## Decision

- **Route and interface.** `POST /api/invoices` consumes
  `multipart/form-data` with a required `file` part and an optional `title`
  part. It returns `202 {doc_id, version_id}`. It requires `X-Tenant-Id` and no
  `Idempotency-Key`.
- **Validation by content.** The file must begin with `%PDF-` at offset 0 and
  end with the PDF end-of-file marker in its last 1 KiB. The part's
  `Content-Type` is not trusted or required. A missing file, a blank or
  over-long title, or a PDF without the end marker is `400`; a file that is
  not a PDF by content, an empty file, or a non-multipart request is `415`; a
  body or file over 10 MiB is `413`; an unknown tenant is `401`.
- **No parsing at upload.** The route does not parse pages, text, encryption or
  embedded content. #172's PDF parser enforces `UploadLimits.MAX_PAGES` (50)
  when it parses, so a 51-page PDF is accepted here and refused there. The
  contract and threat model state this split.
- **Filename.** The original filename is reduced to its basename, with path
  separators and control characters removed, and used only as the title when
  no `title` part is given. It never reaches a filesystem path, a response
  header or a database column other than `documents.title`.
- **Storage and metadata.** `ContentStore` owns the bytes, under the tenant's
  content-addressed path. `documents` and `document_versions` own the metadata:
  the document has `mime_type = application/pdf`, and the version has
  `version_number = 1`, `sha256` equal to the bytes' SHA-256 content address,
  and `canonical_cbor = NULL`. The version's artifact is pinned with
  `refcount = 1` in the same transaction as the metadata insert, so garbage
  collection cannot remove live bytes.
- **No job.** The route writes no `jobs`, `outbox`, `attempts` or audit row.
  #175 adds job enqueueing and extraction.
- **Failure behaviour.** Invalid input is refused before any write. A content
  store failure returns `500` before metadata is written; a crash between the
  bytes and the metadata leaves at worst an unreferenced artifact row and file
  that collection reclaims. No path leaves metadata without bytes.
- **Not idempotent.** A retried upload creates another document and version.
  The bytes are shared through the tenant's content address, but the metadata
  is not deduplicated. Add an idempotency key if #175's job enqueueing makes a
  duplicate submission costly.
- **Why not presigned.** One protocol and the existing 10 MiB limits keep the
  first slice small. Revisit this decision if the byte limit grows enough that
  a multipart request is no longer suitable, or if clients need to upload
  directly to object storage.

## Consequences

- A client uploads with one request and gets the document and version IDs. No
  presigned URL is issued, stored or expired, and there is no separate abort
  path.
- `/api/invoices` is a new trust boundary: untrusted PDF bytes, multipart
  framing, a filename and a title. The threat model records the checks and the
  tests that hold them.
- A document version can now point at content-store bytes instead of canonical
  CBOR. Any reader that wants a version's text must branch on the document's
  `mime_type`; the existing extraction path treats a null `canonical_cbor` as a
  missing body until #175 handles PDFs.
- The 50-page cap is not a runtime check on upload. Until #172 lands, a PDF
  under 10 MiB with more than 50 pages is stored and must be refused when it is
  parsed.
- Retrying an upload after a lost response creates a duplicate document. The
  content bytes are one artifact with a higher refcount; the metadata rows are
  not deduplicated.
