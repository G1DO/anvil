package com.g1do.anvil.invoice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.g1do.anvil.cas.ContentStore;
import com.g1do.anvil.tenant.TenantGuard;
import com.g1do.anvil.upload.UploadLimits;

/**
 * Stores an invoice PDF and its document/version metadata (#170).
 *
 * <p>The route validates the caller first, then the PDF magic bytes and the
 * end-of-file marker. The 10 MiB byte limit is enforced by
 * {@code RequestBodyLimitFilter} and the servlet multipart limits; the service
 * checks the file size again for direct callers. It deliberately does not
 * parse pages: #172's PDF parser enforces {@link UploadLimits#MAX_PAGES}.
 *
 * <p>Bytes are written through {@link ContentStore} first, so a storage failure
 * happens before any metadata row. The document and version are then inserted
 * in one transaction together with an artifact reference, so the bytes are
 * pinned against garbage collection. A failure after the file write leaves at
 * worst an unreferenced artifact row and file, which collection reclaims; it
 * never leaves metadata without bytes, and invalid input writes no rows. No
 * job, outbox event or audit event is written.
 */
@Service
public class InvoiceUploadService {

    static final String PDF_MIME_TYPE = "application/pdf";

    private static final String DEFAULT_TITLE = "invoice.pdf";
    private static final int MAX_TITLE_LENGTH = 4096;

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PDF_EOF = {'%', '%', 'E', 'O', 'F'};

    /** The end-of-file marker must appear in the file's last 1 KiB. */
    private static final int EOF_TAIL_BYTES = 1024;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ContentStore content;

    public InvoiceUploadService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
            ContentStore content) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.content = content;
    }

    public record UploadResult(UUID docId, UUID versionId) {
    }

    public UploadResult upload(String tenantId, MultipartFile file, String title) {
        String tenant = validateTenant(tenantId);
        byte[] bytes = readPdf(file);
        requirePdf(bytes);
        String cleanTitle = title == null ? titleFromFilename(file.getOriginalFilename()) : validateTitle(title);

        String sha;
        try {
            sha = content.put(tenant, bytes);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "could not store the PDF", e);
        }

        UploadResult result = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);

            // put() leaves the artifact unreferenced; pin it in the same
            // transaction as the metadata so collection cannot remove live bytes.
            content.acquire(tenant, sha, bytes.length);

            UUID docId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                    docId, tenant, cleanTitle, PDF_MIME_TYPE);
            // A PDF version's sha256 is its content address; the bytes live in
            // the content store, so canonical_cbor stays NULL.
            jdbc.update(
                    "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256, canonical_cbor) "
                            + "VALUES (?, ?, ?, 1, ?, NULL)",
                    versionId, tenant, docId, sha);
            return new UploadResult(docId, versionId);
        });
        if (result == null) {
            throw new IllegalStateException("invoice upload transaction returned no result");
        }
        return result;
    }

    private byte[] readPdf(MultipartFile file) {
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        if (file.getSize() > UploadLimits.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "file exceeds " + UploadLimits.MAX_BYTES + " bytes");
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file is not a PDF");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "could not read the file", e);
        }
        // A part header can lie about its size; the bytes are the authority.
        if (bytes.length > UploadLimits.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "file exceeds " + UploadLimits.MAX_BYTES + " bytes");
        }
        return bytes;
    }

    /** Content, not the part's Content-Type, decides whether this is a PDF. */
    private static void requirePdf(byte[] bytes) {
        if (bytes.length < PDF_MAGIC.length || !matchesAt(bytes, 0, PDF_MAGIC)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file is not a PDF");
        }
        if (!hasEndOfFileMarker(bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF has no end-of-file marker");
        }
    }

    private static boolean matchesAt(byte[] bytes, int offset, byte[] marker) {
        for (int i = 0; i < marker.length; i++) {
            if (bytes[offset + i] != marker[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasEndOfFileMarker(byte[] bytes) {
        int first = Math.max(0, bytes.length - EOF_TAIL_BYTES);
        for (int start = bytes.length - PDF_EOF.length; start >= first; start--) {
            if (matchesAt(bytes, start, PDF_EOF)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An optional title is the same text submit accepts. Without one the stored
     * title comes from the upload's filename, reduced to a bare, control-free
     * name; the filename never reaches a filesystem path or a response header.
     */
    private static String validateTitle(String title) {
        if (title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title too long");
        }
        if (!storableText(title)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is not valid text");
        }
        return title;
    }

    private static String titleFromFilename(String filename) {
        if (filename == null) {
            return DEFAULT_TITLE;
        }
        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        StringBuilder clean = new StringBuilder();
        base.codePoints().forEach(cp -> {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                return; // unpaired surrogate: not storable text
            }
            if (Character.isISOControl(cp)) {
                return;
            }
            if (clean.length() + Character.charCount(cp) <= MAX_TITLE_LENGTH) {
                clean.appendCodePoint(cp);
            }
        });
        String title = clean.toString().trim();
        return title.isEmpty() ? DEFAULT_TITLE : title;
    }

    private String validateTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing X-Tenant-Id");
        }
        String tenant = tenantId.trim();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Integer.class, tenant);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown tenant");
        }
        return tenant;
    }

    /** Text columns refuse NUL, and an unpaired surrogate can't be encoded or stored. */
    private static boolean storableText(String text) {
        return text.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(text);
    }
}
