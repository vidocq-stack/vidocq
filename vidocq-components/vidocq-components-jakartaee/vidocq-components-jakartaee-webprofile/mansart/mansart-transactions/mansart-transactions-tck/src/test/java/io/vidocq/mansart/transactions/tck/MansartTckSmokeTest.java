package io.vidocq.mansart.transactions.tck;

import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test du harness TCK Mansart — vérifie que le provider expose les bons singletons et
 * qu'un cycle begin/commit minimal passe. C'est l'équivalent du « assert wiring works » que le
 * TCK officiel exécute en tout premier ; le faire en dehors évite de devoir lancer le TCK
 * complet pour valider une régression du provider lui-même.
 *
 * <p>Lancé par défaut avec {@code mvn test} (profile par défaut, includes=MansartTckSmoke*).
 * Le profile {@code tck-run} ajoute en plus le scan du jar TCK officiel.
 */
class MansartTckSmokeTest {

    @Test
    void providerExposesNonNullTransactionManager() {
        TransactionManager tm = MansartTckProvider.getTransactionManager();
        assertThat(tm).isNotNull();
    }

    @Test
    void providerExposesNonNullUserTransaction() {
        UserTransaction ut = MansartTckProvider.getUserTransaction();
        assertThat(ut).isNotNull();
    }

    @Test
    void userTransactionBeginCommitCycle() throws Exception {
        UserTransaction ut = MansartTckProvider.getUserTransaction();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        ut.begin();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        ut.commit();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void userTransactionRollbackCycle() throws Exception {
        UserTransaction ut = MansartTckProvider.getUserTransaction();
        ut.begin();
        ut.rollback();
        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void singletonAcrossLookups() {
        // Le TCK fait souvent N appels — le provider doit retourner le même TM (sinon
        // le ThreadLocal d'état actif ne marche pas entre les phases).
        assertThat(MansartTckProvider.getTransactionManager())
                .isSameAs(MansartTckProvider.getTransactionManager());
    }
}
