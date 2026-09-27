package com.beautica.master.entity;

import com.beautica.auth.Role;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit contract for {@link Master#detach(String, String, Instant)} — phase 294 D2/D3.
 *
 * <p><b>Why this class exists, and why it lives in {@code com.beautica.master.entity}.</b>
 * {@code detach} is package-private (it has no production caller at all — D6: the schema permits
 * detachment, phase 295 performs it), so it is unreachable from {@code MasterDetachmentContractIT}
 * in {@code com.beautica.master}. That IT drives the schema through a hand-written raw-SQL helper
 * which <i>mirrors</i> this mutator — and until this class existed, <b>nothing checked that the
 * mirror was faithful</b>. A {@code detach} that stopped writing {@code detachedAt}, or stopped
 * clearing {@code isActive}, would leave all 16 IT cases green (they never call it) while phase 295
 * shipped rows the IT's own fixture proves impossible.
 *
 * <p>Every assertion below therefore corresponds to one column the IT's helper writes:
 * <pre>
 * UPDATE masters SET user_id = NULL, detached_first_name = ?, detached_last_name = ?,
 *                    detached_at = NOW(), is_active = false WHERE id = ?
 * </pre>
 * If this class and that statement ever disagree, one of them is wrong — do not "fix" the test to
 * match a narrowed mutator without re-reading phase 294 D2 and the {@code V157} header block.
 *
 * <p>No Spring: this is a pure state transition on a detached POJO.
 */
@DisplayName("Master#detach — the only writer of the V157 detached_* columns (phase 294 D2)")
class MasterDetachTest {

    private static final Instant DETACHED_AT = Instant.parse("2026-09-04T10:15:30Z");

    private static Master attachedMaster(String firstName, String lastName) {
        User user = new User("staff@beautica.test", "hash", Role.SALON_MASTER,
                firstName, lastName, "+380501234567");
        Master master = new Master();
        master.setUser(user);
        master.setActive(true);
        return master;
    }

    @Test
    @DisplayName("an attached master reads its display name off the live users row and is not detached")
    void should_readNameFromUser_when_attached() {
        Master master = attachedMaster("Олена", "Живий");

        assertThat(master.isDetached()).isFalse();
        assertThat(master.displayFirstName()).isEqualTo("Олена");
        assertThat(master.displayLastName()).isEqualTo("Живий");
    }

    @Test
    @DisplayName("detach writes ALL FIVE columns the phase-295 UPDATE must write — the snapshot pair, "
            + "detachedAt, the severed user link and is_active = false")
    void should_writeEveryDetachmentColumn_when_detaching() {
        Master master = attachedMaster("Олена", "Живий");

        master.detach("Знята", "Майстриня", DETACHED_AT);

        assertThat(master.getUser())
                .as("the account link must be severed — this is what makes the users DELETE legal")
                .isNull();
        assertThat(master.getDetachedFirstName()).isEqualTo("Знята");
        assertThat(master.getDetachedLastName()).isEqualTo("Майстриня");
        assertThat(master.getDetachedAt())
                .as("detachedAt is the state discriminator in chk_masters_detachment_coherent — a "
                        + "detach that omits it writes a row the DB refuses")
                .isEqualTo(DETACHED_AT);
        assertThat(master.isActive())
                .as("a detached master must be invisible to every roster, catalogue and search "
                        + "result; the IT's raw-SQL helper sets is_active = false and this mutator "
                        + "must agree with it")
                .isFalse();
    }

    @Test
    @DisplayName("after detach the D3 accessors serve the snapshot, so the client's own past receipt "
            + "still names the provider")
    void should_serveSnapshotThroughAccessors_when_detached() {
        Master master = attachedMaster("Олена", "Живий");

        master.detach("Знята", "Майстриня", DETACHED_AT);

        assertThat(master.isDetached()).isTrue();
        assertThat(master.displayFirstName()).isEqualTo("Знята");
        assertThat(master.displayLastName()).isEqualTo("Майстриня");
    }

    @Test
    @DisplayName("a null last name is accepted — users.last_name is nullable and only "
            + "detached_first_name is required by chk_masters_detachment_coherent")
    void should_acceptNullLastName_when_detachingUserWithoutSurname() {
        Master master = attachedMaster("Олена", null);

        master.detach("Знята", null, DETACHED_AT);

        assertThat(master.displayFirstName()).isEqualTo("Знята");
        assertThat(master.displayLastName())
                .as("null here is the faithful snapshot of a null surname, not a lost value")
                .isNull();
        assertThat(master.getDetachedAt()).isEqualTo(DETACHED_AT);
    }
}
