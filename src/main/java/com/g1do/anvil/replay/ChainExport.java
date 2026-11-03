package com.g1do.anvil.replay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.g1do.anvil.replay.ReplayService.ChainEvent;
import com.g1do.anvil.replay.ReplayService.Checkpoint;
import com.g1do.anvil.replay.ReplayService.JobChain;

/**
 * The exported chain file (issue #75, record 0026): one job's
 * {@link JobChain}, what replay reads, as JSON, so it can be verified with no
 * database.
 *
 * <pre>{@code
 * {"format": "anvil-chain/1", "tenant_id": "...", "job_id": "...",
 *  "state": "SUCCEEDED", "input_sha": "...",
 *  "events": [{"seq": 0, "prev_hash": "GENESIS", "event_hash": "...", "type": "submit",
 *              "tenant_id": "...", "job_id": "...", "payload": {...}}, ...],
 *  "checkpoints": [{"seq": 100, "sha": "..."}, ...]}
 * }</pre>
 *
 * <p>A file is untrusted input: {@link #read} takes at most
 * {@link #MAX_BYTES}, refuses duplicate keys, unknown fields, a missing field
 * or a wrong type, and never trusts a hash it reads. Whether the content is
 * intact is the verifier's job, not the parser's.
 */
public final class ChainExport {

    /** The format name; a later format gets a new one. */
    public static final String FORMAT = "anvil-chain/1";

    /**
     * The largest export read. Measured (record 0026): a worst-case 8 MiB file
     * loads in a 64 MiB heap, while a 63 MiB one needs 256 to 512 MiB, so the cap
     * bounds the verifier's memory. A job's chain is tens of events, far under it.
     */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final Set<String> TOP = Set.of(
            "format", "tenant_id", "job_id", "state", "input_sha", "events", "checkpoints");
    private static final Set<String> EVENT = Set.of(
            "seq", "prev_hash", "event_hash", "type", "tenant_id", "job_id", "payload");
    private static final Set<String> CHECKPOINT = Set.of("seq", "sha");

    private ChainExport() {
    }

    /** Thrown for a file that isn't a well-formed export; nothing about its chain is claimed. */
    public static class MalformedExportException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MalformedExportException(String message) {
            super(message);
        }
    }

    /** Writes the chain as one export, followed by a newline. */
    public static void write(JobChain chain, OutputStream out) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", FORMAT);
        root.put("tenant_id", chain.tenantId());
        root.put("job_id", chain.jobId().toString());
        root.put("state", chain.state());
        root.put("input_sha", chain.inputSha());
        ArrayNode events = root.putArray("events");
        for (ChainEvent e : chain.events()) {
            ObjectNode n = events.addObject();
            n.put("seq", e.seq());
            n.put("prev_hash", e.prevHash());
            n.put("event_hash", e.eventHash());
            n.put("type", e.type());
            n.put("tenant_id", e.tenantId());
            n.put("job_id", e.jobId().toString());
            n.set("payload", JSON.valueToTree(e.payload()));
        }
        ArrayNode checkpoints = root.putArray("checkpoints");
        for (Checkpoint c : chain.checkpoints()) {
            checkpoints.addObject().put("seq", c.seq()).put("sha", c.sha());
        }
        out.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        out.write('\n');
        out.flush();
    }

    /**
     * Reads one export.
     *
     * @throws MalformedExportException when it is over {@link #MAX_BYTES}, not
     *         JSON, or not this format.
     */
    public static JobChain read(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new MalformedExportException("export is larger than " + MAX_BYTES + " bytes");
        }
        JsonNode root;
        try (JsonParser p = JSON.createParser(bytes)) {
            root = JSON.readTree(p);
            if (p.nextToken() != null) {
                throw new MalformedExportException("export has content after its JSON object");
            }
        } catch (MalformedExportException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MalformedExportException("export is not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new MalformedExportException("export is not a JSON object");
        }
        requireWellFormed(root, "export");
        fields(root, TOP, "export");
        String format = text(root, "format", "export");
        if (!FORMAT.equals(format)) {
            throw new MalformedExportException("export format is '" + format + "', not " + FORMAT);
        }
        String tenant = text(root, "tenant_id", "export");
        UUID jobId = uuid(root, "job_id", "export");

        List<ChainEvent> events = new ArrayList<>();
        int i = 0;
        for (JsonNode n : array(root, "events", "export")) {
            String where = "events[" + i++ + "]";
            if (!n.isObject()) {
                throw new MalformedExportException(where + " is not an object");
            }
            fields(n, EVENT, where);
            JsonNode payload = n.get("payload");
            if (payload == null || !payload.isObject()) {
                throw new MalformedExportException(where + ".payload is not an object");
            }
            events.add(new ChainEvent(seq(n, where), text(n, "prev_hash", where), text(n, "event_hash", where),
                    text(n, "type", where), text(n, "tenant_id", where), uuid(n, "job_id", where),
                    payloadOf(payload, where)));
        }

        List<Checkpoint> checkpoints = new ArrayList<>();
        i = 0;
        for (JsonNode n : array(root, "checkpoints", "export")) {
            String where = "checkpoints[" + i++ + "]";
            if (!n.isObject()) {
                throw new MalformedExportException(where + " is not an object");
            }
            fields(n, CHECKPOINT, where);
            checkpoints.add(new Checkpoint(seq(n, where), text(n, "sha", where)));
        }
        return new JobChain(tenant, jobId, text(root, "state", "export"), text(root, "input_sha", "export"),
                events, checkpoints);
    }

    /**
     * Every string in the export is Unicode text. A lone surrogate can't be
     * encoded into the hashed bytes, so it is a malformed file, never a verdict
     * or a crash.
     */
    private static void requireWellFormed(JsonNode n, String where) {
        if (n.isTextual()) {
            if (!wellFormed(n.asText())) {
                throw new MalformedExportException(where + " holds a lone surrogate, which is not Unicode text");
            }
        } else if (n.isObject()) {
            for (Map.Entry<String, JsonNode> f : n.properties()) {
                requireWellFormed(f.getValue(), where + "." + f.getKey());
            }
        } else if (n.isArray()) {
            int i = 0;
            for (JsonNode c : n) {
                requireWellFormed(c, where + "[" + i++ + "]");
            }
        }
    }

    /** Whether every surrogate in the string is paired, which is what makes it Unicode text. */
    static boolean wellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The same parse replay applies to a stored payload, so the hashed CBOR
     * matches. A payload that can't be read back is a malformed file, not a crash.
     */
    private static Map<String, Object> payloadOf(JsonNode payload, String where) {
        try {
            return ReplayService.parsePayload(JSON.writeValueAsString(payload));
        } catch (IOException | RuntimeException e) {
            throw new MalformedExportException(where + ".payload cannot be read: " + e.getMessage());
        }
    }

    /** Every field present, and none other. */
    private static void fields(JsonNode n, Set<String> expected, String where) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext();) {
            String f = it.next();
            if (!expected.contains(f)) {
                throw new MalformedExportException(where + " has an unknown field '" + f + "'");
            }
            seen.put(f, true);
        }
        for (String f : expected) {
            if (!seen.containsKey(f)) {
                throw new MalformedExportException(where + " has no '" + f + "'");
            }
        }
    }

    private static String text(JsonNode n, String field, String where) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) {
            throw new MalformedExportException(where + "." + field + " is not a string");
        }
        return v.asText();
    }

    private static UUID uuid(JsonNode n, String field, String where) {
        String s = text(n, field, where);
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new MalformedExportException(where + "." + field + " is not a UUID");
        }
    }

    private static long seq(JsonNode n, String where) {
        JsonNode v = n.get("seq");
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 0) {
            throw new MalformedExportException(where + ".seq is not a whole number of 0 or more");
        }
        return v.asLong();
    }

    private static Iterable<JsonNode> array(JsonNode n, String field, String where) {
        JsonNode v = n.get(field);
        if (v == null || !v.isArray()) {
            throw new MalformedExportException(where + "." + field + " is not an array");
        }
        return v;
    }
}
