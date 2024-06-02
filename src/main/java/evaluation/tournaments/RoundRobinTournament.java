package evaluation.tournaments;

import core.AbstractParameters;
import core.AbstractPlayer;
import evaluation.RunArg;
import evaluation.listeners.IGameListener;
import evaluation.listeners.TournamentMetricsGameListener;
import evaluation.tournaments.AbstractTournament.TournamentMode;
import games.GameType;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import players.IAnyTimePlayer;
import utilities.LinearRegression;
import utilities.Pair;

import java.io.File;
import java.io.FileWriter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static core.CoreConstants.GameResult;
import static evaluation.tournaments.AbstractTournament.TournamentMode.*;
import static java.lang.Math.sqrt;
import static java.util.stream.Collectors.toList;

public class RoundRobinTournament extends AbstractTournament {
    private static boolean debug = false;
    public TournamentMode tournamentMode;
    final int gamesPerMatchUp;
    protected List<IGameListener> listeners = new ArrayList<>();
    public boolean verbose = true;
    public boolean alphaRankDetails = true;
    double[] pointsPerPlayer, winsPerPlayer;
    int[] nGamesPlayed, gamesPerPlayer;
    int[][] nGamesPlayedPerOpponent;
    int[][] winsPerPlayerPerOpponent;
    int[][] ordinalDeltaPerOpponent;
    double[] alphaRankByWin;
    double[] alphaRankByOrdinal;
    double[] pointsPerPlayerSquared;
    double[] rankPerPlayer;
    double[] rankPerPlayerSquared;
    protected LinkedHashMap<Integer, Pair<Double, Double>> finalWinRanking; // contains index of agent in agents
    protected LinkedHashMap<Integer, Pair<Double, Double>> finalOrdinalRanking; // contains index of agent in agents
    LinkedList<Integer> allAgentIds;
    private int totalGamesRun;
    private int budget;
    protected boolean randomGameParams;
    public String name;
    public boolean byTeam;

    protected long randomSeed = System.currentTimeMillis();
    List<Integer> gameSeeds = new ArrayList<>();
    int tournamentSeeds;
    String seedFile;
    Random seedRnd = new Random(randomSeed);


    /**
     * Create a round robin tournament, which plays all agents against all others.
     *
     * @param agents         - players for the tournament.
     * @param gameToPlay     - game to play in this tournament.
     * @param playersPerGame - number of players per game.
     */
    public RoundRobinTournament(List<? extends AbstractPlayer> agents, GameType gameToPlay, int playersPerGame,
                                AbstractParameters gameParams, TournamentMode tournamentMode,
                                Map<RunArg, Object> config) {
        super(tournamentMode, agents, gameToPlay, playersPerGame, gameParams);
        int nTeams = game.getGameState().getNTeams();
        if (tournamentMode == NO_SELF_PLAY && nTeams > this.agents.size()) {
            throw new IllegalArgumentException("Not enough agents to fill a match without self-play." +
                    "Either add more agents, reduce the number of players per game, or allow self-play.");
        }

        this.allAgentIds = new LinkedList<>();
        for (int i = 0; i < this.agents.size(); i++)
            this.allAgentIds.add(i);

        this.gamesPerMatchUp = (int) config.getOrDefault(RunArg.matchups, 100);
        this.tournamentMode = tournamentMode;
        this.budget = (int) config.get(RunArg.budget);
        for (AbstractPlayer player : agents) {
            if (player instanceof IAnyTimePlayer) {
                ((IAnyTimePlayer) player).setBudget(this.budget);
            }
        }
        this.pointsPerPlayer = new double[agents.size()];
        this.pointsPerPlayerSquared = new double[agents.size()];
        this.winsPerPlayer = new double[agents.size()];
        this.nGamesPlayed = new int[agents.size()];
        this.nGamesPlayedPerOpponent = new int[agents.size()][];
        this.ordinalDeltaPerOpponent = new int[agents.size()][];
        this.winsPerPlayerPerOpponent = new int[agents.size()][];
        for (int i = 0; i < agents.size(); i++) {
            this.winsPerPlayerPerOpponent[i] = new int[agents.size()];
            this.nGamesPlayedPerOpponent[i] = new int[agents.size()];
            this.ordinalDeltaPerOpponent[i] = new int[agents.size()];
        }
        this.rankPerPlayer = new double[agents.size()];
        this.rankPerPlayerSquared = new double[agents.size()];
        this.gamesPerPlayer = new int[agents.size()];
        this.byTeam = (boolean) config.getOrDefault(RunArg.byTeam, false);
        this.tournamentSeeds = (int) config.getOrDefault(RunArg.distinctRandomSeeds, 0);
        this.seedFile = (String) config.getOrDefault(RunArg.seedFile, "");
        if (!seedFile.isEmpty()) {
            this.gameSeeds = loadSeedsFromFile();
            if (gameSeeds.isEmpty()) {
                throw new AssertionError("No seeds found in file " + seedFile);
            }
            this.tournamentSeeds = gameSeeds.size();
        }
        this.name = String.format("Game: %s, Players: %d, GamesPerMatchup: %d, Mode: %s",
                gameToPlay.name(), playersPerGame, gamesPerMatchUp, tournamentMode.name());
    }

    /**
     * Runs the round robin tournament.
     */
    @Override
    public void run() {
        if (verbose)
            System.out.println("Playing " + game.getGameType().name());

        Set<String> agentNames = agents.stream()
                //           .peek(a -> System.out.println(a.toString()))
                .map(AbstractPlayer::toString).collect(Collectors.toSet());

        for (IGameListener gameTracker : listeners) {
            gameTracker.init(game, nPlayers, agentNames);
            game.addListener(gameTracker);
        }

        LinkedList<Integer> matchUp = new LinkedList<>();
        // add outer loop if we have tournamentSeeds enabled; if not this will just run once
        List<Integer> allSeeds = new ArrayList<>(gameSeeds);
        for (int iter = 0; iter < Math.max(1, tournamentSeeds); iter++) {
            if (tournamentSeeds > 0) {
                // use the same seed for each game in the tournament
                // allSeeds contains the ones loaded from file - if empty then use a random one
                int nextRnd = allSeeds.isEmpty() ? seedRnd.nextInt() : allSeeds.get(iter);
                gameSeeds = IntStream.range(0, gamesPerMatchUp).mapToObj(i -> nextRnd).collect(toList());
            } else {
                // use a seed per matchup
                gameSeeds = IntStream.range(0, gamesPerMatchUp).mapToObj(i -> seedRnd.nextInt()).collect(toList());
            }
            createAndRunMatchUp(matchUp);
        }
        reportResults();

        for (IGameListener listener : listeners)
            listener.report();
    }

    protected List<Integer> loadSeedsFromFile() {
        // we open seedFile, and read in the comma-delimited list of seeds, and put this in an array
        try {
            Scanner scanner = new Scanner(new File(seedFile)).useDelimiter("\\s*,\\s*");
            List<Integer> seeds = new ArrayList<>();
            while (scanner.hasNextInt()) {
                seeds.add(scanner.nextInt());
            }
            return new ArrayList<>(seeds);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not load seeds from file " + seedFile);
        }

    }

    public int getWinnerIndex() {
        if (finalWinRanking == null || finalWinRanking.isEmpty())
            throw new UnsupportedOperationException("Cannot get winner before results have been calculated");

        // The winner is the first key in finalRanking
        for (Integer key : finalWinRanking.keySet()) {
            return key;
        }
        throw new AssertionError("Should not be reachable");
    }

    public AbstractPlayer getWinner() {
        return agents.get(getWinnerIndex());
    }

    /**
     * Recursively creates one combination of players and evaluates it.
     *
     * @param matchUp - current combination of players, updated recursively.
     */
    public void createAndRunMatchUp(List<Integer> matchUp) {

        int nTeams = byTeam ? game.getGameState().getNTeams() : nPlayers;

        if (tournamentMode == ONE_VS_ALL) {
            // In this case agents.get(0) must always play
            List<Integer> agentOrder = new ArrayList<>(this.allAgentIds);
            agentOrder.remove(Integer.valueOf(0));
            for (int p = 0; p < nTeams; p++) {
                // we put the focus player at each position (p) in turn
                if (agentOrder.size() == 1) {
                    // to reduce variance in this case we can use the same set of seeds for each case
                    List<Integer> matchup = new ArrayList<>(nTeams);
                    for (int j = 0; j < nTeams; j++) {
                        if (j == p)
                            matchup.add(0); // focus player
                        else {
                            matchup.add(agentOrder.get(0));
                        }
                    }
                    // We split the total budget equally across the possible positions the focus player can be in
                    // We will therefore use the first chunk of gameSeeds only (but use the same gameSeeds for each position)
                    evaluateMatchUp(matchup, gamesPerMatchUp / nTeams, gameSeeds);
                } else {
                    for (int m = 0; m < this.gamesPerMatchUp; m++) {
                        Collections.shuffle(agentOrder, seedRnd);
                        List<Integer> matchup = new ArrayList<>(nTeams);
                        for (int j = 0; j < nTeams; j++) {
                            if (j == p)
                                matchup.add(0); // focus player
                            else {
                                matchup.add(agentOrder.get(j % agentOrder.size()));
                            }
                        }
                        evaluateMatchUp(matchup, 1, Collections.singletonList(gameSeeds.get(m)));
                    }
                }
            }
        } else {
            // in this case we are in exhaustive mode, so we recursively construct all possible combinations of players
            if (matchUp.size() == nTeams) {
                evaluateMatchUp(matchUp, gamesPerMatchUp, gameSeeds);
            } else {
                for (Integer agentID : this.allAgentIds) {
                    if (tournamentMode == SELF_PLAY || !matchUp.contains(agentID)) {
                        matchUp.add(agentID);
                        createAndRunMatchUp(matchUp);
                        matchUp.remove(agentID);
                    }
                }
            }
        }
    }

    /**
     * Evaluates one combination of players.
     *
     * @param agentIDsInThisGame - IDs of agents participating in this run.
     */
    protected void evaluateMatchUp(List<Integer> agentIDsInThisGame, int nGames, List<Integer> seeds) {
        if (seeds.size() < nGames)
            throw new AssertionError("Not enough seeds for the number of games requested");
        if (debug)
            System.out.printf("Evaluate %s at %tT%n", agentIDsInThisGame.toString(), System.currentTimeMillis());
        LinkedList<AbstractPlayer> matchUpPlayers = new LinkedList<>();

        // If we are in self-play mode, we need to create a copy of the player to avoid them sharing the same state
        // If not in self-play mode then this is unnecessary, as the same agent will never be in the same game twice
        for (int agentID : agentIDsInThisGame)
            matchUpPlayers.add(tournamentMode == SELF_PLAY ? this.agents.get(agentID).copy() : this.agents.get(agentID));

        if (verbose) {
            StringBuffer sb = new StringBuffer();
            sb.append("[");
            for (int agentID : agentIDsInThisGame)
                sb.append(this.agents.get(agentID).toString()).append(",");
            sb.setCharAt(sb.length() - 1, ']');
            System.out.println(sb);
        }

        // TODO : Not sure this is the ideal place for this...ask Raluca
        Set<String> agentNames = agents.stream().map(AbstractPlayer::toString).collect(Collectors.toSet());
        for (IGameListener listener : listeners) {
            if (listener instanceof TournamentMetricsGameListener) {
                ((TournamentMetricsGameListener) listener).tournamentInit(game, nPlayers, agentNames, new HashSet<>(matchUpPlayers));
            }
        }

        // Run the game N = gamesPerMatchUp times with these players
        for (int i = 0; i < nGames; i++) {
            // if tournamentSeeds > 0, then we are running this many tournaments, each with a different random seed fixed for the whole tournament
            // so we override the standard random seeds
            game.reset(matchUpPlayers, seeds.get(i));

            // Randomize parameters
            if (randomGameParams) {
                game.getGameState().getGameParameters().randomize();
                System.out.println("Game parameters: " + game.getGameState().getGameParameters());
            }

            game.run();  // Always running tournaments without visuals
            GameResult[] results = game.getGameState().getPlayerResults();

            int numDraws = 0;
            for (int j = 0; j < matchUpPlayers.size(); j++) {
                nGamesPlayed[agentIDsInThisGame.get(j)] += 1;
                for (int k = 0; k < matchUpPlayers.size(); k++) {
                    if (k != j) {
                        nGamesPlayedPerOpponent[agentIDsInThisGame.get(j)][agentIDsInThisGame.get(k)] += 1;
                    }
                }

                // now we need to be careful if we have a team game, as the agents are indexed by Team, not player
                if (byTeam) {
                    for (int player = 0; player < game.getGameState().getNPlayers(); player++) {
                        if (game.getGameState().getTeam(player) == j) {
                            numDraws += updatePoints(results, agentIDsInThisGame, agentIDsInThisGame.get(j), player);
                            break; // we stop after one player on the team to avoid double counting
                        }
                    }
                } else {
                    numDraws += updatePoints(results, agentIDsInThisGame, agentIDsInThisGame.get(j), j);
                }
            }

            if (numDraws > 0) {
                double pointsPerDraw = 1.0 / numDraws;
                for (int j = 0; j < matchUpPlayers.size(); j++) {
                    if (results[j] == GameResult.DRAW_GAME) pointsPerPlayer[agentIDsInThisGame.get(j)] += pointsPerDraw;
                    if (results[j] == GameResult.DRAW_GAME)
                        pointsPerPlayerSquared[agentIDsInThisGame.get(j)] += pointsPerDraw * pointsPerDraw;
                }
            }

            if (verbose) {
                StringBuffer sb = new StringBuffer();
                sb.append("[");
                for (int j = 0; j < matchUpPlayers.size(); j++) {
                    for (int player = 0; player < game.getGameState().getNPlayers(); player++) {
                        if (game.getGameState().getTeam(player) == j) {
                            sb.append(results[player]).append(",");
                            break; // we stop after one player on the team to avoid double counting
                        }
                    }
                }
                sb.setCharAt(sb.length() - 1, ']');
                System.out.println(sb);
            }

        }
        totalGamesRun += nGames;
    }

    private int updatePoints(GameResult[] results, List<Integer> matchUpPlayers, int j, int player) {
        // j is the index of the agent in the matchup; player is the corresponding player number in the game
        int ordinalPos = game.getGameState().getOrdinalPosition(player);
        rankPerPlayer[j] += ordinalPos;
        rankPerPlayerSquared[j] += ordinalPos * ordinalPos;

        for (int playerPos = 0; playerPos < game.getGameState().getNPlayers(); playerPos++) {
            if (playerPos != player) {
                int ordinalOther = game.getGameState().getOrdinalPosition(playerPos);
                ordinalDeltaPerOpponent[j][matchUpPlayers.get(playerPos)] += ordinalOther - ordinalPos;
            }
        }

        if (results[player] == GameResult.WIN_GAME) {
            pointsPerPlayer[j] += 1;
            winsPerPlayer[j] += 1;
            pointsPerPlayerSquared[j] += 1;
            for (int k : matchUpPlayers) {
                if (k != j) {
                    winsPerPlayerPerOpponent[j][k] += 1;
                }
            }
        }
        if (results[player] == GameResult.DRAW_GAME)
            return 1;
        return 0;
    }


    protected void calculateFinalResults() {
        finalWinRanking = new LinkedHashMap<>();
        finalOrdinalRanking = new LinkedHashMap<>();
        for (int i = 0; i < this.agents.size(); i++) {
            // We calculate the standard deviation, and hence the standard error on the mean value
            // (using a normal approximation, which is valid for large N)
            double stdDev = Math.sqrt(pointsPerPlayerSquared[i] / nGamesPlayed[i] - (pointsPerPlayer[i] / nGamesPlayed[i])
                    * (pointsPerPlayer[i] / nGamesPlayed[i]));
            finalWinRanking.put(i, new Pair<>(pointsPerPlayer[i] / nGamesPlayed[i], stdDev / sqrt(nGamesPlayed[i])));
            stdDev = Math.sqrt(rankPerPlayerSquared[i] / nGamesPlayed[i] - (rankPerPlayer[i] / nGamesPlayed[i]) * (rankPerPlayer[i] / nGamesPlayed[i]));
            finalOrdinalRanking.put(i, new Pair<>(rankPerPlayer[i] / nGamesPlayed[i], stdDev / sqrt(nGamesPlayed[i])));
        }
        // Sort by points.
        finalWinRanking = finalWinRanking.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((o1, o2) -> o2.a.compareTo(o1.a)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1,
                        LinkedHashMap::new));
        finalOrdinalRanking = finalOrdinalRanking.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((o1, o2) -> o2.a.compareTo(o1.a)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1,
                        LinkedHashMap::new));

    }

    protected void reportResults() {
        calculateFinalResults();
        boolean toFile = resultsFile != null && !resultsFile.isEmpty();
        List<String> dataDump = new ArrayList<>();
        dataDump.add(name + "\n");

        dataDump.add("Alpha calculations using Delta Ordinal\n");
        if (verbose)
            System.out.println("Alpha calculations using Delta Ordinal");
        alphaRankByOrdinal = reportAlphaRank(dataDump, ordinalDeltaPerOpponent);
        dataDump.add("Alpha calculations using Win Rate\n");
        if (verbose)
            System.out.println("Alpha calculations using Win Rate");
        int[][] symmetrisedWins = new int[agents.size()][agents.size()];
        for (int i = 0; i < agents.size(); i++) {
            for (int j = 0; j < agents.size(); j++) {
                symmetrisedWins[i][j] = (winsPerPlayerPerOpponent[i][j] - winsPerPlayerPerOpponent[j][i]);
            }
        }
        alphaRankByWin = reportAlphaRank(dataDump, symmetrisedWins);

        // To console
        if (verbose)
            System.out.printf("============= %s - %d games played ============= \n", game.getGameType().name(), totalGamesRun);
        for (int i = 0; i < this.agents.size(); i++) {
            String str = String.format("%s got %.2f points. ", agents.get(i), pointsPerPlayer[i]);
            if (toFile) dataDump.add(str);
            if (verbose) System.out.print(str);

            str = String.format("%s won %.1f%% of the %d games of the tournament. ",
                    agents.get(i), 100.0 * winsPerPlayer[i] / totalGamesRun, totalGamesRun);
            if (toFile) dataDump.add(str);
            if (verbose) System.out.print(str);

            str = String.format("%s won %.1f%% of the %d games it played during the tournament.\n",
                    agents.get(i), 100.0 * winsPerPlayer[i] / nGamesPlayed[i], nGamesPlayed[i]);
            if (toFile) dataDump.add(str);
            if (verbose) System.out.print(str);

            for (int j = 0; j < this.agents.size(); j++) {
                if (i != j) {
                    str = String.format("%s won %.1f%% of the %d games against %s.\n",
                            agents.get(i), 100.0 * winsPerPlayerPerOpponent[i][j] / nGamesPlayedPerOpponent[i][j], nGamesPlayedPerOpponent[i][j], agents.get(j));
                    if (toFile) dataDump.add(str);
                    if (verbose) System.out.print(str);
                }
            }

            if (toFile) dataDump.add("\n");
            if (verbose) System.out.println();
        }

        String str = "---- Ranking ---- (+/- are standard errors on the mean calculated using a Normal approximation) \n";
        if (toFile) dataDump.add(str);
        if (verbose) System.out.print(str);

        for (Integer i : finalWinRanking.keySet()) {
            str = String.format("%s: Win rate %.2f +/- %.3f\tMean Ordinal %.2f +/- %.2f\n",
                    agents.get(i).toString(),
                    finalWinRanking.get(i).a, finalWinRanking.get(i).b,
                    finalOrdinalRanking.get(i).a, finalOrdinalRanking.get(i).b);
            if (toFile) dataDump.add(str);
            if (verbose) System.out.print(str);
        }

        // now report alpha-rank
        str = "\nAlpha-rank by Win Rate\n";
        if (toFile) dataDump.add(str);
        if (verbose) System.out.print(str);
        List<Pair<String, Double>> sortedAlphaRank = IntStream.range(0, agents.size())
                .mapToObj(i -> new Pair<>(agents.get(i).toString(), alphaRankByWin[i]))
                .sorted((o1, o2) -> o2.b.compareTo(o1.b))
                .toList();
        for (Pair<String, Double> pair : sortedAlphaRank) {
            str = String.format("\t%-30s\t%.2f\n", pair.a, pair.b);
            if (toFile) dataDump.add(str);
            if (verbose) System.out.print(str);
        }

        // and then by ordinal
        str = "\nAlpha-rank by Ordinal Position\n";
        if (toFile) dataDump.add(str);
        if (verbose) System.out.print(str);
        sortedAlphaRank = IntStream.range(0, agents.size())
                .mapToObj(i -> new Pair<>(agents.get(i).toString(), alphaRankByOrdinal[i]))
                .sorted((o1, o2) -> o2.b.compareTo(o1.b))
                .toList();
        for (Pair<String, Double> pair : sortedAlphaRank) {
            str = String.format("\t%-30s\t%.2f\n", pair.a, pair.b);
            if (toFile) dataDump.add(str);
            if (verbose) System.out.print(str);
        }

        // To file
        if (toFile) {
            try {
                FileWriter writer = new FileWriter(resultsFile, true);
                for (String line : dataDump)
                    writer.write(line);
                writer.write("\n");
                writer.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    protected double[] reportAlphaRank(List<String> dataDump, int[][] values) {
        // alpha-rank calculations
        double[] alphaValues = new double[]{30.0};
        double[] retValue = new double[agents.size()];
        for (int alphaIndex = 0; alphaIndex < alphaValues.length; alphaIndex++) {
            // T is our transition matrix
            double alpha = alphaValues[alphaIndex];
            double[][] T = new double[agents.size()][agents.size()];
            for (int i = 0; i < agents.size(); i++) {
                for (int j = 0; j < agents.size(); j++) {
                    if (i == j) {
                        T[i][j] = Math.exp(0);
                    } else {
                        double baseValue = values[i][j] / (double) nGamesPlayedPerOpponent[i][j];
                        T[i][j] = Math.exp(-alpha * baseValue);
                    }
                }
                // then normalise the row
                double rowSum = Arrays.stream(T[i]).sum();
                for (int j = 0; j < agents.size(); j++) {
                    T[i][j] /= rowSum;
                }
            }
            RealMatrix transitionMatrix = MatrixUtils.createRealMatrix(T);

            // We now find the stationary distribution of this transition matrix
            // i.e. the pi for which T^T pi = pi
            try {
                EigenDecomposition eig = new EigenDecomposition(transitionMatrix.transpose());
                double[] eigenValues = eig.getRealEigenvalues();
                // we now expect one of these to have a value of +1.0
                for (int eigenIndex = 0; eigenIndex < eigenValues.length; eigenIndex++) {
                    if (Math.abs(eigenValues[eigenIndex] - 1.0) < 1e-6) {
                        // we have found the eigenvector we want
                        double[] pi = eig.getEigenvector(eigenIndex).toArray();
                        // normalise pi
                        double piSum = Arrays.stream(pi).sum();
                        for (int i = 0; i < agents.size(); i++) {
                            pi[i] /= piSum;
                        }
                        String str = "Alpha: " + alpha;
                        dataDump.add(str + "\n");
                        if (verbose) System.out.println(str);
                        for (int i = 0; i < agents.size(); i++) {
                            retValue[i] = pi[i];
                            str = String.format("\t%.3f\t%s%n", pi[i], agents.get(i));
                            dataDump.add(str);
                            if (verbose) System.out.print(str);
                        }
                        dataDump.add("\n");
                        if (verbose) System.out.println();
                        break;
                    }
                }
            } catch (Exception e) {
                System.out.println("Error in eigen decomposition - unable to calculate alpha-rank.");
                return new double[agents.size()];
            }

            // print the transition matrix
            if (alphaRankDetails) {
                String str = "Transition matrix for alpha = " + alpha;
                dataDump.add(str + "\n");
                if (verbose) System.out.println(str);
                for (int i = 0; i < agents.size(); i++) {
                    for (int j = 0; j < agents.size(); j++) {
                        str = String.format("%.3f\t", T[i][j]);
                        dataDump.add(str);
                        if (verbose) System.out.print(str);
                    }
                    dataDump.add("\n");
                    if (verbose) System.out.println();
                }
            }

            // B = A^TA + A A^T
            RealMatrix B = transitionMatrix.transpose().multiply(transitionMatrix).add(transitionMatrix.multiply(transitionMatrix.transpose()));
            // This provides useful clustering information

            // print the B matrix
            if (alphaRankDetails) {
                String str = "B matrix for alpha = " + alpha;
                dataDump.add(str + "\n");
                if (verbose) System.out.println(str);
                for (int i = 0; i < agents.size(); i++) {
                    for (int j = 0; j < agents.size(); j++) {
                        str = String.format("%.3f\t", B.getEntry(i, j));
                        dataDump.add(str);
                        if (verbose) System.out.print(str);
                    }
                    dataDump.add("\n");
                    if (verbose) System.out.println();
                }
            }

            // Now we cluster based on the bibliometrically symmetrised matrix B
            double thresholdForCluster = 0.2 * sqrt(agents.size());
            String[] clusterMembership = new String[agents.size()];
            for (int i = 0; i < agents.size(); i++) {
                for (int j = i; j < agents.size(); j++) {
                    if (i != j) {
                        // we look at the Euclidean distance between the two rows
                        double distance = 0.0;
                        for (int k = 0; k < agents.size(); k++) {
                            distance += (B.getEntry(i, k) - B.getEntry(j, k)) * (B.getEntry(i, k) - B.getEntry(j, k));
                        }
                        distance = sqrt(distance);
                        if (distance < thresholdForCluster) {
                            if (clusterMembership[i] == null) {
                                if (clusterMembership[j] == null) {
                                    // neither in cluster, so new cluster
                                    clusterMembership[i] = agents.get(i).toString();
                                    clusterMembership[j] = agents.get(i).toString();
                                } else {
                                    // j is in a cluster, so i joins it
                                    clusterMembership[i] = clusterMembership[j];
                                }
                            } else {
                                if (clusterMembership[j] == null) {
                                    // i is in a cluster, so j joins it
                                    clusterMembership[j] = clusterMembership[i];
                                } else {
                                    // both in clusters, so merge
                                    String cluster = clusterMembership[i];
                                    for (int k = 0; k < agents.size(); k++) {
                                        if (clusterMembership[k] != null && clusterMembership[k].equals(clusterMembership[j])) {
                                            clusterMembership[k] = cluster;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // print the cluster membership
            if (alphaRankDetails && Arrays.stream(clusterMembership).anyMatch(Objects::nonNull)) {

                String str = "The following agents cluster together, and may be considered equivalent: ";
                dataDump.add(str + "\n");
                if (verbose) System.out.println(str);
                for (int i = 0; i < agents.size(); i++) {
                    // get all agents in this cluster
                    boolean clusterExists = false;
                    for (int j = 0; j < agents.size(); j++) {
                        if (clusterMembership[j] != null && clusterMembership[j].equals(agents.get(i).toString())) {
                            if (!clusterExists) {
                                str = String.format("\tCluster for %s%n", agents.get(i));
                                dataDump.add(str);
                                if (verbose) System.out.print(str);
                            }
                            clusterExists = true;
                            str = String.format("\t\t%s%n", agents.get(j));
                            dataDump.add(str);
                            if (verbose) System.out.print(str);
                        }
                    }
                }
                dataDump.add("\n");
                if (verbose) System.out.println();
            }
        }
        return retValue;
    }

    public double getWinRate(int agentID) {
        return finalWinRanking.get(agentID).a;
    }

    public double getWinStdErr(int agentID) {
        return finalWinRanking.get(agentID).b;
    }

    public double getOrdinalRank(int agentID) {
        return finalOrdinalRanking.get(agentID).a;
    }

    public double getOrdinalStdErr(int agentID) {
        return finalOrdinalRanking.get(agentID).b;
    }

    public List<IGameListener> getListeners() {
        return listeners;
    }

    public void setListeners(List<IGameListener> listeners) {
        this.listeners = listeners;
    }

    public void addListener(IGameListener gameTracker) {
        listeners.add(gameTracker);
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setRandomSeed(Number randomSeed) {
        this.randomSeed = randomSeed.longValue();
        seedRnd = new Random(this.randomSeed);
    }

    public void setRandomGameParams(boolean randomGameParams) {
        this.randomGameParams = randomGameParams;
    }

    public void setResultsFile(String resultsFile) {
        this.resultsFile = resultsFile;
    }

    public int getNumberOfAgents() {
        return agents.size();
    }
}
