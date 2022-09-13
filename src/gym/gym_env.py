import random
import jpype
from jpype import *
import jpype.imports
from jpype.types import *

# jpype.startJVM()
jpype.addClassPath("ModernBoardGame.jar")
# Game = jpype.JClass("core.Game")
jpype.startJVM()
# jpype.startJVM(classpath=[""])
java.lang.System.out.println("Hello World!!")
# from games.pandemic import PandemicGame
# PandemicGame.main([""])
# from games.tictactoe import TicTacToeGame
# TicTacToeGame.main([""])
import java
from core import GYMEnv
from core import Game, AbstractGameState
from games import GameType
from players.human import ActionController
from players.simple import RandomPlayer
from players.mcts import MCTSPlayer
from players.python import PythonAgent
from games.tictactoe import TicTacToeStateVector
from utilities import Utils
# gameType = "pandemic"

class TAG():
    def __init__(self, seed=42, game="TicTacToe"):
        null = jpype.java.lang.String @ None
        null_list = jpype.java.util.List @ None
        gameType = Utils.getArg([""], "game", game)
        players = jpype.java.util.ArrayList()
        players.add(PythonAgent())
        players.add(RandomPlayer())
        # players.add(PythonAgent())
        self.env = GYMEnv(GameType.valueOf(gameType), null, players, java.lang.Long(seed))

    def getObs(self):
        return self.env.getFeatures()

    def reset(self):
        gs = self.env.reset()
        # obs = TicTacToeStateVector.featureVector(gs, 0)
        obs = self.env.getFeatures()
        print(f"reset obs = {obs}")
        return obs

    def getActions(self):
        return self.env.getActions()

    def step(self, action):
        gs = self.env.step(action)
        # features = gs.featureVector(gs, 0)
        obs = self.env.getFeatures()
        print(f"step {obs}")
        reward = self.env.getReward()
        done = self.env.isDone()
        return gs, reward, done, ""

    def close(self):
        jpype.shutdownJVM()

if __name__ == "__main__":
    env = TAG()
    done = False
    steps = 0
    gs = env.reset()
    while not done:
        steps +=1
        rnd_action = random.randint(0, len(env.getActions())-1)
        print(f"player {env.env.getPlayerID()} choose action {rnd_action}")
        gs, reward, done, info = env.step(rnd_action)
        if done:
            print(f"Game over {reward} in {steps} steps")

