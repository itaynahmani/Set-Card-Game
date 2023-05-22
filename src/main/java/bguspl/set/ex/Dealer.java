package bguspl.set.ex;

import bguspl.set.Env;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.*;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    public boolean setFound;
    protected Semaphore mainLock;
    protected volatile boolean tableLock;
    protected volatile Queue<Integer> playersToCheck;
    protected int[] currentSetCards;
    protected int[] currentSetSlots;
    protected int setID;
    private int sleepTime;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        terminate = false;
        setFound = false;
        mainLock = new Semaphore(1, true);
        tableLock = false;
        playersToCheck = new LinkedList<Integer>();
        currentSetCards = new int[env.config.featureSize];
        currentSetSlots = new int[env.config.featureSize];
        setID = -1;
        sleepTime = 1000;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        // System.out.println("DEALER is starting ");
        // Create a thread for each Runnable(palyer) object
        Thread[] threads = new Thread[players.length];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(players[i]);
        }

        // Start each thread
        for (int i = 0; i < threads.length; i++) {
            threads[i].start();
            // System.out.println("player " + i + " is starting ");
        }

        updateTimerDisplay(true);

        while (!shouldFinish()) {
            placeCardsOnTable(true);
            timerLoop();
            updateTimerDisplay(true);
            // env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
            env.ui.setCountdown(0, false);
            removeAllCardsFromTable();
        }
        env.ui.setCountdown(0, false);
        announceWinners();
        // System.out.println("dealer TERMINATED ");
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        terminate();
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(setFound);
            removeCardsFromTable();
            placeCardsOnTable(false);
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        // System.out.println("terminated the
        // dealer--------------------------------------");
        env.ui.setCountdown(0, false);
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();

        }
        terminate = true;
        // System.out.println("finish terminated the
        // dealer--------------------------------------");
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        tableLock = true;
        if (setFound) {
            for (Integer card : currentSetCards) {
                deck.remove(card);
            }
            shuffleArray(currentSetSlots);
            for (int slot : currentSetSlots) {
                // System.out.println("remove token to player " + setID + " from slot " + slot);
                table.removeToken(setID, slot);
                table.removeCard(slot);
                for (Player p : players) {
                    int x = p.id;
                    // System.out.println("enter for");
                    if (x != setID && table.playersToken[slot][x]) {
                        // System.out.println("enter if");
                        table.removeToken(x, slot);
                        players[x].playerAction.remove(slot);
                        if (playersToCheck.remove(x)) {// found a player that have set waiting with the removed token
                            players[x].block = false;
                            players[x].queueIsChecked = false;
                            if (!players[x].human)
                                players[x].aiThread.interrupt();

                        }
                    }
                }

            }

        }
        tableLock = false;
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    public void placeCardsOnTable(boolean allCards) {
        tableLock = true;
        if (allCards) {
            int[] emptySlots = new int[env.config.tableSize];
            for (int j = 0; j < emptySlots.length; j++) {
                emptySlots[j] = j;
            }
            shuffleArray(emptySlots);
            for (int i = 0; i < emptySlots.length && !deck.isEmpty(); i++) { // maybe there arent cards on the deck
                Random rnd = new Random();
                int newCard = rnd.nextInt(deck.size());
                table.placeCard(deck.remove(newCard), emptySlots[i]);
            }
            // table.hints();
        } else if (setFound) {

            for (int i = 0; i < currentSetSlots.length && !deck.isEmpty(); i++) {
                Random rnd = new Random();
                int newCard = rnd.nextInt(deck.size());
                table.placeCard(deck.remove(newCard), currentSetSlots[i]);
            }

            setFound = false;
            // table.hints();
        }
        tableLock = false;

    }

    // Implementing Fisher–Yates shuffle
    private static void shuffleArray(int[] array) {
        int index, temp;
        Random random = new Random();
        for (int i = array.length - 1; i > 0; i--) {
            index = random.nextInt(i + 1);
            temp = array[index];
            array[index] = array[i];
            array[i] = temp;
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {

        synchronized (mainLock) {
            if (playersToCheck.isEmpty()) {
                try {
                    mainLock.wait(sleepTime);
                    // mainLock.wait(1);
                } catch (InterruptedException e) {
                }
            } else {

                setID = playersToCheck.remove();
                currentSetCards = slotsQueueToArray(players[setID].playerAction);
                setFound = env.util.testSet(currentSetCards);
                // setFound = true;
                if (setFound) {
                    currentSetSlots = QueueToArray(players[setID].playerAction);
                    players[setID].flag = 1;
                } else
                    players[setID].flag = -1;
                mainLock.notifyAll();

            }
        }

    }

    private int[] slotsQueueToArray(Queue<Integer> q) {
        int[] a = new int[env.config.featureSize];
        int temp;
        for (int i = 0; i < env.config.featureSize; i++) {
            temp = q.remove();
            a[i] = table.slotToCard[temp];
            // System.out.println("slot id is " + temp);
            q.add(temp);
        }
        return a;
    }

    private int[] QueueToArray(Queue<Integer> q) {
        int[] a = new int[env.config.featureSize];
        for (int i = 0; i < env.config.featureSize; i++) {
            a[i] = q.remove();
            // System.out.println("addind to current set slot:" + a[i]);
            q.add(a[i]);
        }
        return a;
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    public void updateTimerDisplay(boolean reset) {
        if (reset) {
            sleepTime = 1000;
            // System.out.println(" reset time");
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
            // env.ui.setCountdown(10000, false);
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            // reshuffleTime = System.currentTimeMillis() + 10000;
            // System.out.println(" the time is 60");

        } else if (reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis) {
            sleepTime = 10;
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true);
        } else {
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    public void removeAllCardsFromTable() {
        // System.out.println("removeAll");
        tableLock = true;
        for (int i = 0; i < env.config.tableSize; i++) {

            for (Player p : players) {
                table.removeToken(p.id, i);
                p.playerAction.clear();
                p.queueIsChecked = false;
            }

            if (table.slotToCard[i] != null) {
                deck.add(table.slotToCard[i]);
                table.removeCard(i);
            }
        }
        playersToCheck.clear();
        if (!shouldFinish())
            updateTimerDisplay(true);
        tableLock = false;
        for (Player p : players) {
            if (!p.human)
                p.aiThread.interrupt();

        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
        int counter = 0;
        int maxScore = -1;
        for (int i = 0; i < players.length; i++) {
            if (players[i].score() > maxScore) {
                maxScore = players[i].score();
                counter = 1;
            } else if (players[i].score() == maxScore) {
                counter++;
            }
        }
        int[] winners = new int[counter];
        int c = 0;
        for (int i = 0; i < players.length; i++) {
            if (players[i].score() == maxScore) {
                winners[c] = players[i].id;
                c++;
            }
        }

        env.ui.announceWinner(winners);
    }

    public int getDeckSize() {
        return deck.size();
    }
}
