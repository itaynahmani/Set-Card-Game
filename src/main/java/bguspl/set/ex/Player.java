package bguspl.set.ex;

import java.util.logging.Level;
import java.util.*;
import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    protected Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    protected final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    // addition
    protected Queue<Integer> playerAction;
    protected boolean chooseCards;
    Dealer dealer;
    protected int flag;
    protected volatile boolean block;
    protected volatile boolean queueIsChecked;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        playerAction = new LinkedList<Integer>();
        block = false;
        queueIsChecked = false;
        flag = 0;
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human)
            createArtificialIntelligence();

        while (!terminate) {
            // if (terminate)
            // System.out.println("Player AI of " + id + " enter to run");
            synchronized (dealer.mainLock) {
                while (flag == 0 && !terminate) {
                    // System.out.println("players thread awake");
                    try {
                        dealer.mainLock.wait();
                    } catch (InterruptedException e) {

                    }
                }
            }
            // if (terminate)
            // System.out.println("Player AI of " + id + " enter to run");
            if (!terminate) {
                // System.out.println("----------------------------------------");
                GivePointOrPenalty(flag);
                block = false;
                flag = 0;
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        if (!human)
            try {
                // System.out.println("Player AI of " + id + " enter to try terminated." +
                // terminate);
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            // System.out.println("AI THREAD is starting ");
            while (!terminate) {
                try {
                    synchronized (this) {
                        // System.out.println("enter to ai" + " " + Thread.currentThread().getName());
                        Random rnd = new Random();
                        int randomSlot = rnd.nextInt(env.config.tableSize);
                        // System.out.println("the slot chosen by computr is " + randomSlot);
                        keyPressed(randomSlot);
                        if (block) {
                            wait();
                        }
                    }
                } catch (InterruptedException ignored) {
                    // System.out.println("CATCHED");
                    block = false;
                }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
        // System.out.println("terminated the
        // plaYER--------------------------------------" + id);
        try {
            // System.out.println("Player AI of " + id + " enter to try terminated." +
            // terminate);
            // if (!human)
            // aiThread.join();
            // System.out.println("bbbbbbbbbbbbbbbbbbbbbbbb");
            playerThread.interrupt();
            playerThread.join();
            // System.out.println("Player " + id + " after join terminated." + terminate);
        } catch (Exception e) {
            // System.out.println("Player " + id + " NOTTTTTTT terminated." + terminate);
        }

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (!dealer.tableLock) {
            if (!block) {
                // System.out.println("entered keyPressed ");
                if (playerAction.contains(slot)) {
                    playerAction.remove(slot);
                    table.removeToken(id, slot); // return boolean
                    queueIsChecked = false;
                } else if (playerAction.size() < env.config.featureSize && table.slotToCard[slot] != null) {
                    playerAction.add(slot);
                    table.placeToken(id, slot);
                    // System.out.println("token placed" + playerAction.size() + " " +
                    // queueIsChecked);
                }

                if (playerAction.size() == env.config.featureSize && !queueIsChecked) {
                    // System.out.println("enter ");
                    synchronized (dealer.mainLock) {
                        queueIsChecked = true;
                        block = true;
                        dealer.playersToCheck.add(id);
                        dealer.mainLock.notifyAll();
                    }
                }
            }
        }
    }

    private void GivePointOrPenalty(int flag) {
        // System.out.println(" the flag is :" + flag);
        if (flag == 1) {
            point();

        } else {
            penalty();

        }

    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // System.out.println("giving point");
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        long waitTime = env.config.pointFreezeMillis;
        long sleepTime = waitTime;
        env.ui.setFreeze(id, waitTime);
        if (waitTime >= 1000) {
            sleepTime = 1000;
        }
        while (waitTime > 0) {
            try {
                Thread.sleep(sleepTime);
            } catch (Exception e) {
            }
            waitTime -= sleepTime;
            env.ui.setFreeze(id, waitTime);
        }
        env.ui.setFreeze(id, -1);
        // System.out.println("after freeze point");
        playerAction.clear();
        queueIsChecked = false;

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // System.out.println("giving penalty");
        long waitTime = env.config.penaltyFreezeMillis;
        long sleepTime = waitTime;
        env.ui.setFreeze(id, waitTime);
        if (waitTime >= 1000) {
            sleepTime = 1000;
        }
        while (waitTime > 0) {
            try {
                Thread.sleep(sleepTime);
            } catch (Exception e) {
            }
            waitTime -= sleepTime;
            env.ui.setFreeze(id, waitTime);
        }
        env.ui.setFreeze(id, -1);
        // System.out.println("after freeze penalty");

    }

    public int score() {
        return score;
    }
}
