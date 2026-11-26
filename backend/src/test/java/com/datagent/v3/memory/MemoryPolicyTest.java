package com.datagent.v3.memory;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.datagent.v3.memory.MemoryRepository.Snapshot;
import com.datagent.v3.memory.MemoryRequestAssembly.UserInput;

class MemoryPolicyTest {
    private static final String OLD = "经营周报默认用万元";
    private static final String NEXT = "经营周报默认用元";
    private static final String DAILY = "经营日报默认用元";

    @Test void exactScopeAndRepeatedValuePreserveTheOtherText() {
        var original = new Snapshot(OLD + "\n" + DAILY, 3);
        var grant = grant(original, "以后" + NEXT);
        assertThat(grant.tool()).isEqualTo("edit_memory");
        assertThat(MemoryPolicy.apply(original, grant).content()).isEqualTo(NEXT + "\n" + DAILY);
        var repeated = grant(original, "以后" + OLD);
        assertThat(MemoryPolicy.apply(original, repeated).operation()).isEqualTo("unchanged");
        var deletion = grant(original, "忘记「" + OLD + "」");
        assertThat(MemoryPolicy.apply(original, deletion).content()).isEqualTo(DAILY);
        var collision = grant(original, "把记忆「" + OLD + "」改为「" + DAILY + "」");
        assertThat(MemoryPolicy.apply(original, collision).error()).isEqualTo("CONFLICT");
    }

    @Test void onlyCurrentExplicitCommandsGrantWrite() {
        var empty = new Snapshot("", 0);
        assertThat(List.of("这次周报用元", "对", "「以后经营周报默认用元」", "用户说：以后经营周报默认用元",
                "以后如果是周报默认用元", "以后周报默认用元。\n另外...", "记住我偏好密码abc", "以后周报默认用元？",
                "以后周报默认用元，同时忘记日报", "忘记周报单位"))
                .allSatisfy(text -> assertThat(grant(empty, text)).isNull());
        assertThat(grant(empty, "今后：周报默认不展示明细。").arguments().get("content")).isEqualTo("周报默认不展示明细");
        var canceled = MemoryPolicy.authorize(List.of(new UserInput(0, "以后" + OLD), new UserInput(4, "不用记住")), empty, false);
        assertThat(canceled.grant()).isNull();
        var conflicted = MemoryPolicy.authorize(List.of(new UserInput(0, "以后" + OLD), new UserInput(4, "以后" + NEXT)), empty, false);
        assertThat(conflicted.grant()).isNull();
        assertThat(MemoryPolicy.authorize(List.of(new UserInput(0, "以后" + OLD)), empty, true).grant()).isNull();
    }

    @Test void overflowUsesUtf8BytesAndDoesNotProducePartialBody() {
        String content = "字".repeat(666);
        var snapshot = new Snapshot(content, 1);
        var edit = MemoryPolicy.apply(snapshot, grant(snapshot, "记住我偏好按日汇报"));
        assertThat(edit.error()).isEqualTo("FULL");
        assertThat(edit.bytesNeeded()).isEqualTo(MemoryRepository.bytes("我偏好按日汇报") - 1);
        assertThat(edit.content()).isEqualTo(content);
    }

    private MemoryPolicy.Grant grant(Snapshot snapshot, String text) {
        return MemoryPolicy.authorize(List.of(new UserInput(0, text)), snapshot, false).grant();
    }
}
