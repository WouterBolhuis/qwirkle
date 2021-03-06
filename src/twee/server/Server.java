package twee.server;

import twee.Board;
import twee.Game;
import twee.player.Mark;
import twee.player.Player;
import twee.strategy.Smart;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * 
 * @author Wouter Bolhuis & Sebastiaan den Boer
 * @version 1.6
 */
public class Server {

    public static void main(String[] args) {
        new Server();
    }
    
    private int port = 1337;
    private /*@ spec_public @*/ List<ClientHandler> threads;
    private /*@ spec_public @*/ List<ClientHandler> joined;
    private /*@ spec_public @*/ List<ClientHandler> playing;
    private /*@ spec_public @*/ Map<Game, ClientHandler[]> gameMap
    = new HashMap<Game, ClientHandler[]>();
    private /*@ spec_public @*/ ServerSocket sock;

    /**
     * Waits for user input on the port if the user inputs nothing it will use port 1337
     * calls startServer() and displays the used IP and port
     */
    //@ ensures threads != null;
    //@ ensures joined != null;
    //@ ensures playing != null;
    public Server() {
        threads = new ArrayList<ClientHandler>();
        joined = new ArrayList<ClientHandler>();
        playing = new ArrayList<ClientHandler>();
        try {
            String portInput = getInput("Please enter the port of the server "
                    + "(leave empty for port 1337)");
            if (portInput.equals("")) {
                port = 1337;
            } else {
                port = Integer.parseInt(portInput);
            }
            try {
                System.err.println("          STARTING SERVER WITH IP: " 
            + InetAddress.getLocalHost() + "\n               AND PORT: " + port + "\n");
            } catch (UnknownHostException e) {
                System.err.println("ERROR: couldn't figure out and display the ip, "
                        + "please try again");
                new Server();
            }
            startServer();
        } catch (NumberFormatException e) {
            System.err.println("ERROR: not a valid portnummer!");
            new Server();
        }
    }
    
    /**
     * Opens the ServerSocket
     * Whenever someone connects it will assign a clienthandler to them
     * and output a join message
     */
    //@ ensures sock != null;
    public void startServer() {
        try {
            sock = new ServerSocket(port);
            int telInt = 0;
            while (true) {
                Socket socket = sock.accept();
                ClientHandler handler = new ClientHandler(this, socket);
                handler.start();
                addHandler(handler);
                System.out.println("\n   CONNECTING: [Client no. " + (++telInt) + "]" 
                + "connected\n");
            }
        } catch (IOException e) {
            System.err.println("ERROR: couldn't create a socket, \n"
                    + "is the port already in use?");
            new Server();
        }
    }

    /**
     * This message gets called by a handler to analyze the message from the client
     * Depending on the input from said client the server will call methods and/or 
     * change variable values
     * @param handler
     * @param msg
     */
    //@ requires !threads.isEmpty() || !joined.isEmpty() || !playing.isEmpty();
    public synchronized void analyzeString(ClientHandler handler, String msg) {
        System.err.println("\nINPUT FROM " + handler.getClientName() + ": " + msg);
        String[] input = msg.split(ProtocolConstants.msgSeperator);
        if (input[0].equals(ProtocolControl.joinRequest)) {
            threads.remove(handler);
            if (joined.size() == 0) {
                joined.add(handler);
                Player one = Player.createPlayer(Mark.YELLOW, handler.getClientName());
                handler.setPlayer(one);
                acceptRequest(handler, one.getMark());
                System.out.println("JOINING: " + handler.getClientName()
                        + " is waiting for a game with mark:" + one.getMark());
            } else {
                if (!joined.get(0).getClientName().equals(handler.getClientName())) {
                    joined.add(handler);
                    Player two = Player.createPlayer(Mark.RED, handler.getClientName());
                    handler.setPlayer(two);
                    acceptRequest(handler, two.getMark());
                    System.out.println("JOINING: " + handler.getClientName()
                            + " is waiting for a game with mark:" + two.getMark());
                } else {
                    sendMessage(handler, ProtocolConstants.invalidCommand
                            + ProtocolConstants.msgSeperator + ProtocolConstants.usernameInUse);
                    removeHandler(handler);
                }
            }
            if (joined.size() == 2) {
                Game game = new Game(joined.get(0).getPlayer(), joined.get(1).getPlayer());
                ClientHandler[] clients = new ClientHandler[2];
                int telInt = 0;
                for (ClientHandler handle : joined) {
                    clients[telInt] = handle;
                    telInt++;
                }
                ClientHandler firstPlayer;
                if (joined.get(0).getPlayer().getMark().equals(Mark.YELLOW)) {
                    firstPlayer = joined.get(0);
                } else {
                    firstPlayer = joined.get(1);
                }
                gameMap.put(game, clients);
                for (ClientHandler handle : joined) {
                    handle.setGame(game);
                    sendMessage(handle, ProtocolControl.startGame 
                            + ProtocolConstants.msgSeperator + firstPlayer.getClientName() 
                            + ProtocolConstants.msgSeperator
                            + otherHandler(firstPlayer).getClientName());
                    playing.add(handle);
                }
                joined.clear();
                game.start();

            }

        } else if (input[0].equals(ProtocolControl.getBoard)) {
            sendBoard(handler);
        } else if (input[0].equals(ProtocolControl.doMove)) {
            doMove(handler, Integer.parseInt(input[1]));
        } else if (input[0].equals(ProtocolControl.playerTurn)) {
            turn(handler);
        } else if (input[0].equals(ProtocolControl.rematch)) {
            if (!handler.getGame().getRunning()) {
                if (otherHandler(handler).rematch) {
                    otherHandler(handler).rematch = false;
                    Game game = new Game(handler.getPlayer(),
                            otherHandler(handler).getPlayer());
                    ClientHandler[] clients = new ClientHandler[2];
                    clients[0] = handler;
                    clients[1] = otherHandler(handler);
                    gameMap.put(game, clients);
                    for (ClientHandler handle : clients) {
                        handle.setGame(game);
                        sendMessage(handle, ProtocolControl.rematchConfirm);
                        sendMessage(handle, ProtocolControl.startGame 
                                + ProtocolConstants.msgSeperator + clients[0].getClientName() 
                                + ProtocolConstants.msgSeperator
                                + otherHandler(clients[0]).getClientName());
                    }
                    game.start();
                } else {
                    handler.rematch = true;
                }
            }
        }
    }

    /**
     * send ProtocolControl.acceptRequest to the handler
     * @param handler
     * @param mark
     */
    private synchronized void acceptRequest(ClientHandler handler, Mark mark) {
        sendMessage(handler, ProtocolControl.acceptRequest 
                + ProtocolConstants.msgSeperator + mark);
    }

    /**
     * Whenever a new client connects the handler assigned to that client gets added
     * to the handler list threads
     * @param handler
     */
    //@ requires !threads.contains(handler);
    public synchronized void addHandler(ClientHandler handler) {
        threads.add(handler);
    }

    /**
     * Whenever a clients disconnects the clients ClientHandler gets remove 
     * from whatever list it was in
     * @param handler
     */
    //@ requires threads.contains(handler) || joined.contains(handler) || playing.contains(handler);
    /*@ ensures !threads.contains(handler) || !joined.contains(handler) 
    || !playing.contains(handler);@*/
    public synchronized void removeHandler(ClientHandler handler) {
        if (threads.contains(handler)) {
            threads.remove(handler);
        } else if (playing.contains(handler)) {
            playing.remove(handler);
        } else if (joined.contains(handler)) {
            joined.remove(handler);
        }
        System.out.println("\n   DISCONNECTING: " + handler.getClientName()
                + " has left the game\n");
        sendMessage(otherHandler(handler), ProtocolControl.endGame 
                + ProtocolConstants.msgSeperator
                + handler.getClientName() + ProtocolConstants.msgSeperator
                + ProtocolConstants.connectionlost);
    }

    /**
     * Creates and sends the string containing the board using ProtocolControl.sendBoard
     * @param handler
     */
    //requires handler != null;
    public synchronized void sendBoard(ClientHandler handler) {
        Mark[] marks = handler.getGame().getBoard().getFields();
        String board = "";
        for (int i = 0; i < marks.length; i++) {
            board += marks[i].name().toLowerCase() + ProtocolConstants.msgSeperator;
        }
        sendMessage(handler, ProtocolControl.sendBoard 
                + ProtocolConstants.msgSeperator + board);
    }

    /**
     * Checks whether the client requesting the move is the client which should be making a move
     * If not it will send that client 
     * ProtocolConstants.invalidCommand ProtocolControl.invalidUserTurn
     * If it is it will check if it is a valid move
     * To do so it will first change the index to the respective column and then check
     * if there is still space left in this column
     * If there is no space left it will return 
     * ProtocolConstants.invalidMove ProtocolConstants.invalidMove
     * if there is space left it will make the move 
     * and return the ProtocolControl.moveResult command to the client
     * @param handler
     * @param decision
     */
    //@ requires handler != null;
    public synchronized void doMove(ClientHandler handler, int decision) {
        boolean moveAllowed = false;
        if (handler.getGame().current.equals(handler.getPlayer())) {
            for (int row = 0; row < Board.ROWS; row++) {
                int slot = row * Board.COLUMNS + Board.getColumn(decision) - 1;

                if (handler.getGame().getRules().isMoveAllowed(slot)) {
                    moveAllowed = true;
                }
            }
            if (moveAllowed) {
                handler.getGame().takeTurn(Board.getColumn(decision));
                ClientHandler[] doMove = gameMap.get(handler.getGame());
                if (!handler.getGame().getRunning()) {
                    gameEnded(handler.getGame());
                }
                for (ClientHandler handle : doMove) {
                    sendMessage(handle, ProtocolControl.moveResult 
                            + ProtocolConstants.msgSeperator
                            + Smart.gravity(decision, getStringArray(handler.getGame())) 
                            + ProtocolConstants.msgSeperator 
                            + handler.getClientName()
                            + ProtocolConstants.msgSeperator + true
                            + ProtocolConstants.msgSeperator 
                            + handler.getGame().current.getName());
                }
            } else {
                sendMessage(handler, ProtocolConstants.invalidCommand
                        + ProtocolConstants.msgSeperator + ProtocolConstants.invalidMove
                        + ProtocolConstants.msgSeperator + handler.getClientName());

            }
        } else {
            sendMessage(handler,
                    ProtocolConstants.invalidCommand + ProtocolConstants.msgSeperator
                            + ProtocolConstants.invalidUserTurn + ProtocolConstants.msgSeperator
                            + handler.getClientName());
        }
    }

    /**
     * Changes the game.getBoard().getFields Mark[] into a String[]
     * @param game
     * @return String[] marks[].name().toLowerCase()
     */
    //@ requires game != null;
    //@ ensures \result.length == 42;
    public static String[] getStringArray(Game game) {
        String[] array = new String[42];
        Mark[] marks = game.getBoard().getFields();
        for (int integer = 0; integer < 42; integer++) {
            array[integer] = marks[integer].name().toLowerCase();
        }
        return array;
    }

    /**
     * Checks whether the game has ended and sends the ProtocolControl.endGame command 
     * with the correct results to both participating clients 
     * @param game
     */
    //@ requires !game.getRunning();
    private void gameEnded(Game game) {
        ClientHandler[] clients = gameMap.get(game);
        if (!game.getRunning()) {
            if (game.getRules().hasWinner()) {
                for (ClientHandler client : clients) {
                    if (game.getRules().isWinner(client.getPlayer())) {
                        sendMessage(client, ProtocolControl.endGame
                                + ProtocolConstants.msgSeperator + client.getClientName()
                                + ProtocolConstants.msgSeperator + ProtocolConstants.winner);
                        sendMessage(otherHandler(client), ProtocolControl.endGame
                                + ProtocolConstants.msgSeperator + client.getClientName()
                                + ProtocolConstants.msgSeperator + ProtocolConstants.winner);
                    }
                }
            } else {
                for (ClientHandler client : clients) {
                    sendMessage(client, ProtocolControl.endGame + ProtocolConstants.msgSeperator
                            + client.getClientName() + ProtocolConstants.msgSeperator
                            + ProtocolConstants.draw);
                }
            }
        }
    }

    /**
     * returns the player who is currently making a turn
     * @param handler
     */
    //@pure
    public synchronized void turn(ClientHandler handler) {
        sendMessage(handler,
                ProtocolControl.turn + ProtocolConstants.msgSeperator + handler.getGame()
                .current.getName());
    }

    /**
     * Closes the socket and terminates the server
     */
    //@ ensures sock.isClosed();
    public void shutdown() {
        System.err.println("          STOPPING SERVER");
        try {
            sock.close();
        } catch (IOException e) {
            System.exit(0);
        }
        System.exit(0);
    }
    
    /**
     * Prints the String variable and returns the user input
     * @param variable
     * @return
     */
    //@ensures \result != "";
    public String getInput(String variable) {
        String input = null;
        try {
            if (variable != "") {
                System.out.println(variable);
            }
            input = new BufferedReader(new InputStreamReader(System.in)).readLine().trim();
            if (input.equalsIgnoreCase("exit")) {
                shutdown();
            }

        } catch (IOException e) {
            System.err.println("Something went wrong");
            System.out.println("But you know shit happens");
            shutdown();
        }

        return input;

    }
    
    /**
     * Sends the String message to the ClientHandler handler
     * @param handler
     * @param message
     */
    //@pure
    public void sendMessage(ClientHandler handler, String message) {
        if (joined.contains(handler) || playing.contains(handler) 
                || threads.contains(handler)) {
            System.err.println("    SENDING TO " + handler.getClientName() + ": " + message);
            handler.sendMessage(message);
        }
    }
    
    /**
     * Returns the other handler
     * @param handler
     * @return
     */
    //@ requires gameMap.get(handler.getGame())[0] == handler;
    //@ ensures \result == gameMap.get(handler.getGame())[1];
    //@ also
    //@ requires gameMap.get(handler.getGame())[0] != handler;
    //@ ensures \result == gameMap.get(handler.getGame())[0];
    //@pure
    public ClientHandler otherHandler(ClientHandler handler) {
        ClientHandler[] handlers = gameMap.get(handler.getGame());
        if (handlers[0].equals(handler)) {
            return handlers[1];
        } else {
            return handlers[0];
        }
    }
}
