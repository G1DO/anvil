# 0029 — Invoice upload limit: 10 MiB and 50 pages

Status: accepted 2026-11-13 (#145).

## Context

`POST /api/documents` had no application-level request-body limit. Only the servlet
container and the JSON parser's defaults applied, so a large body cost memory before
anything refused it. Invoice PDF uploads had no byte or page limit either. The PDF
upload route (#170) depends on this choice and must use the same byte limit, and the
non-PDF `415` check belongs to #170.

The limit has to be enforced before the body is parsed, and a rejected request must
write no document or version row. The same byte limit must apply to a JSON document
and to an invoice PDF.

## Decision

- **Whole request body: 10 MiB (10,485,760 bytes).** This is the limit for
  `POST /api/documents` and for an invoice PDF upload. `RequestBodyLimitFilter`
  refuses a declared `Content-Length` over the limit without reading the body, and
  cuts a streamed body when it crosses the limit. Both paths return `413` before the
  controller runs, so no document, version or job row is written.
- **Invoice PDF: at most 50 pages.** The PDF route enforces this when it parses the
  file (#170, #172). No PDF route exists yet, so this is a contract choice today, not
  a runtime check.
- The values live in `UploadLimits` (`MAX_BYTES`, `MAX_PAGES`) and are stated in
  `contracts/openapi.yaml`.

## Evidence: intended invoice size

Invoices were generated as text PDFs and as 300 dpi JPEG-page scans (quality 80),
using the same A4 layout: a header, 20 line items per page and a total. Sizes are the
whole PDF file, which is the upload:

| Invoice | 1 page | 5 pages | 10 pages | 25 pages | 50 pages |
| --- | --- | --- | --- | --- | --- |
| Text PDF | 2,174 B | 6,674 B | 12,262 B | 29,191 B | 57,189 B |
| 300 dpi scan | — | — | 1,837,863 B | 4,594,292 B | 9,188,257 B |

A 50-page 300 dpi scanned invoice is 8.8 MiB, so 10 MiB covers the largest intended
invoice at the highest scan density tested. A 50-page text invoice is only 57 KiB, so
the page cap, not the byte cap, is what bounds text extraction work. A 10 MiB byte
cap also covers the same 50 pages at 150 dpi (about 4 MiB).

## Evidence: memory use

A small Java 25 harness held the JSON request, parsed it into `SubmitRequest`, encoded
its body as UTF-8, and built the canonical CBOR, then measured retained heap after
forcing GC (`-Xmx512m`):

| Body | Retained after parse + UTF-8 + CBOR | Multiple of body |
| --- | --- | --- |
| 1 MiB | 6.6 MiB | 6.63x |
| 5 MiB | 18.6 MiB | 3.71x |
| 10 MiB | 33.7 MiB | 3.37x |
| 15 MiB | 48.9 MiB | 3.26x |

The large fixed cost at 1 MiB is the JSON parser's buffers; from 5 MiB up the retained
cost is about 3.3 to 3.7 times the body. At 10 MiB one request retains about 34 MiB,
and the peak while parsing is higher.

A 20 MiB body is already refused by Jackson's default maximum string length
(20,000,000 characters), so an application limit above that would hand the real limit
to a parser default. 10 MiB stays below it and leaves about 2x headroom.

## Consequences

- 10 MiB and 50 pages are contract values. Changing either changes the OpenAPI
  description and `413`, the threat model, and `RequestBodyLimitTest`.
- The JSON limit is the whole request body, including its envelope. A JSON document's
  content bytes are therefore slightly below 10 MiB; the same cap is on the whole
  multipart body of a PDF upload.
- A chunked body with no `Content-Length` is refused as soon as it reads the byte
  past the limit, so only the allowed bytes plus one are read.
- The page cap is not enforced until the PDF upload and text-extraction routes land.
  They must use `UploadLimits.MAX_PAGES`; until then an invoice PDF that would exceed
  50 pages has no route to be uploaded through.
