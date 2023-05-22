package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class DealerTest {

    Dealer dealer;
    @Mock
    Util util;
    @Mock
    private UserInterface ui;
    @Mock
    private Table table;
    @Mock
    private Player player;
    @Mock
    private Logger logger;

    void assertInvariants() {
        // assertTrue(dealer.getDeckSize() >= 0);
    }

    @BeforeEach
    void setUp() {
        Env env = new Env(logger, new Config(logger, (String) null), ui, util);
        Player[] players = { player };
        dealer = new Dealer(env, table, players);
        assertInvariants();
    }

    @AfterEach
    void tearDown() {
        assertInvariants();
    }

    @Test
    void timerResetCheck() {

        long expectedTime = 60000;

        dealer.updateTimerDisplay(true);

        verify(ui).setCountdown(eq(expectedTime), eq(false));

    }

    @Test
    void checkFoundSet() {

        // force table.countCards to return 3
        // when(table.countCards()).thenReturn(12);

        // calculate the expected size for later
        boolean expected = false;

        // call the method we are testing
        dealer.placeCardsOnTable(false);

        // check that the score was increased correctly
        assertEquals(expected, dealer.setFound);

    }

}