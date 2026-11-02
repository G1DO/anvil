package com.g1do.anvil.extract;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A test model: records what it was shown and replies from a script. No network. */
public final class ScriptedModel implements ExtractionModel {

    private final List<BoundedText> calls = new CopyOnWriteArrayList<>();
    private volatile Function<BoundedText, ModelReply> script;

    public ScriptedModel(Function<BoundedText, ModelReply> script) {
        this.script = script;
    }

    /** Replies {@code {"facts": []}}. */
    public static ScriptedModel empty() {
        return new ScriptedModel(d -> ok("{\"facts\":[]}"));
    }

    public static ModelReply ok(String json) {
        return new ModelReply(json, "end_turn");
    }

    public void script(Function<BoundedText, ModelReply> next) {
        this.script = next;
    }

    public List<BoundedText> calls() {
        return calls;
    }

    @Override
    public String id() {
        return "scripted";
    }

    @Override
    public ModelReply complete(BoundedText document) {
        calls.add(document);
        return script.apply(document);
    }
}
