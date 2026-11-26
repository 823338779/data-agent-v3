package com.datagent.v3.memory;

import java.util.*;
import com.datagent.v3.memory.MemoryIntent.Kind;

/** 将显式指令映射为唯一允许的操作，匹配仅在内存中按行进行。 */
final class MemoryPolicy {
    static final String READ = "readMemory", ADD = "add_memory", EDIT = "edit_memory";
    static final String CONTENT = "content", OLD = "old_string", NEW = "new_string";
    static final String NO_MATCH = "NO_MATCH", CONFLICT = "CONFLICT", FULL = "FULL";
    private static final String LF = "\n";
    private static final String ADDED = "added", UPDATED = "updated", DELETED = "deleted", UNCHANGED = "unchanged";

    record Grant(int sourceSeq, long revision, String tool, Map<String, String> arguments) {}
    record Decision(Kind kind, Grant grant, String reason) {}
    record Edit(String content, String operation, String entry, String error, int bytesNeeded) {}

    static Decision authorize(List<MemoryRequestAssembly.UserInput> inputs, MemoryRepository.Snapshot snapshot, boolean spent) {
        var selected = MemoryIntent.parse("");
        int sequence = -1;
        boolean ambiguous = false;
        for (var entry : inputs) {
            var intent = MemoryIntent.parse(entry.rawText());
            if (intent.kind() == Kind.IGNORE) return new Decision(Kind.IGNORE, null, null);
            if (intent.kind() == Kind.NORMAL) continue;
            if (isWrite(intent.kind()) && isWrite(selected.kind()) && !intent.equals(selected)) ambiguous = true;
            else if (!isWrite(intent.kind())) ambiguous = false;
            selected = intent;
            sequence = entry.messageSeq();
        }
        if (ambiguous) return new Decision(Kind.CLARIFY, null, CONFLICT);
        if (!isWrite(selected.kind()) || spent) return new Decision(selected.kind(), null, null);
        var entries = lines(snapshot.content());
        var next = selected.newText();
        var prior = selected.oldText();
        if (selected.kind() == Kind.SAVE) {
            if (entries.contains(next)) return decision(selected.kind(), sequence, snapshot, ADD, Map.of(CONTENT, next));
            var scope = MemoryIntent.scope(next);
            if (scope != null) {
                var matching = entries.stream().filter(e -> scope.equals(MemoryIntent.scope(e))).toList();
                if (matching.size() > 1) return new Decision(Kind.CLARIFY, null, CONFLICT);
                if (!matching.isEmpty()) prior = matching.getFirst();
            }
            if (prior == null) return decision(selected.kind(), sequence, snapshot, ADD, Map.of(CONTENT, next));
        }
        if (Collections.frequency(entries, prior) != 1) return new Decision(Kind.CLARIFY, null, NO_MATCH);
        return decision(selected.kind(), sequence, snapshot, EDIT, Map.of(OLD, prior, NEW, next));
    }

    private static boolean isWrite(Kind kind) { return kind == Kind.SAVE || kind == Kind.EDIT || kind == Kind.FORGET; }

    private static Decision decision(Kind kind, int seq, MemoryRepository.Snapshot snapshot,
                                     String tool, Map<String,String> args) {
        return new Decision(kind, new Grant(seq, snapshot.revision(), tool, args), null);
    }

    static Edit apply(MemoryRepository.Snapshot current, Grant grant) {
        var entries = lines(current.content());
        String next;
        String operation;
        String entry;
        if (ADD.equals(grant.tool())) {
            next = grant.arguments().get(CONTENT);
            if (entries.contains(next)) return unchanged(current, next);
            if (conflicts(entries, next)) return error(current, CONFLICT, 0);
            entries.add(next);
            entry = next;
            operation = ADDED;
        } else {
            var old = grant.arguments().get(OLD);
            next = grant.arguments().get(NEW);
            if (Collections.frequency(entries, old) != 1) return error(current, NO_MATCH, 0);
            // 仅范围分隔符周围的空白改变不产生新版本；保留已存原文。
            if (old.equals(next) || MemoryIntent.scope(old) != null
                    && MemoryIntent.scope(old).equals(MemoryIntent.scope(next))
                    && Objects.equals(MemoryIntent.defaultValue(old), MemoryIntent.defaultValue(next))) {
                return unchanged(current, old);
            }
            int pos = entries.indexOf(old);
            entries.remove(pos);
            if (!next.isEmpty()) {
                if (entries.contains(next) || conflicts(entries, next)) return error(current, CONFLICT, 0);
                entries.add(pos, next);
            }
            entry = next.isEmpty() ? old : next;
            operation = next.isEmpty() ? DELETED : UPDATED;
        }
        var content = String.join(LF, entries);
        int over = MemoryRepository.bytes(content) - MemoryRepository.LIMIT;
        return over > 0 ? error(current, FULL, over) : new Edit(content, operation, entry, null, 0);
    }

    private static boolean conflicts(List<String> entries, String next) {
        var scope = MemoryIntent.scope(next);
        return scope != null && entries.stream().anyMatch(e -> scope.equals(MemoryIntent.scope(e)));
    }

    private static Edit unchanged(MemoryRepository.Snapshot snapshot, String entry) {
        return new Edit(snapshot.content(), UNCHANGED, entry, null, 0);
    }

    private static Edit error(MemoryRepository.Snapshot snapshot, String code, int bytes) {
        return new Edit(snapshot.content(), null, null, code, bytes);
    }

    private static ArrayList<String> lines(String text) {
        return new ArrayList<>(text.isEmpty() ? List.of() : List.of(text.split(LF, -1)));
    }
}
