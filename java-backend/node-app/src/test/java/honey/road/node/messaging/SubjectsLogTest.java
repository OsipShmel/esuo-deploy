package honey.road.node.messaging;

import honey.road.contracts.event.Subjects;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Log subjects and streams (design 5.3, 5.5) — built only in {@link Subjects}. */
class SubjectsLogTest {

    @Test
    void branchLogSubjects() {
        assertThat(Subjects.branchLog("b01", "error")).isEqualTo("branch.b01.log.error");
        assertThat(Subjects.branchLogsAll("b01")).isEqualTo("branch.b01.log.>");
        assertThat(Subjects.STREAM_BRANCH_LOG).isEqualTo("BRANCH_LOG");
    }

    @Test
    void centerLogSubjects() {
        assertThat(Subjects.centerLogPull("b01")).isEqualTo("center.log.pull.b01");
        assertThat(Subjects.CENTER_LOG_PULL_ALL).isEqualTo("center.log.pull.>");
        assertThat(Subjects.STREAM_CENTER_LOG).isEqualTo("CENTER_LOG");
    }

    @Test
    void branchIdOfLogSubject() {
        assertThat(Subjects.branchIdOf("branch.b01.log.warn")).isEqualTo("b01");
    }
}
