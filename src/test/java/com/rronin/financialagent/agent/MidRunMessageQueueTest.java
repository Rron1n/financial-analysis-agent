package com.rronin.financialagent.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MidRunMessageQueueTest {
    @Test void failedContextConstructionDoesNotRemoveMessages() {
        var queue = new MidRunMessageQueue();
        queue.append("run", "first");
        var snapshot = queue.peek("run");
        assertThat(snapshot).hasSize(1);
        assertThat(queue.peek("run")).isEqualTo(snapshot);
    }
    @Test void acknowledgingSnapshotDoesNotDropNewArrivals() {
        var queue = new MidRunMessageQueue();
        queue.append("run", "first");
        var snapshot = queue.peek("run");
        queue.append("run", "second");
        queue.acknowledge("run", snapshot.stream().map(MidRunMessageQueue.QueuedMessage::id).toList());
        assertThat(queue.peek("run").stream().map(MidRunMessageQueue.QueuedMessage::message)).containsExactly("second");
        assertThat(queue.peek("another-run")).isEmpty();
    }
}
