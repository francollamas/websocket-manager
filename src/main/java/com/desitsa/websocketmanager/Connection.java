package com.desitsa.websocketmanager;

import com.google.gson.*;
import javafx.application.Platform;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.UUID;

/**
 * Maneja la conexión con el servidor
 */
public class Connection {

    private static final String EMPTY_GUID = "00000000-0000-0000-0000-000000000000";

    // Referencia al servidor
    private URI uri;

    // WebSocket
    private WebSocketClient websocket;

    // ID de websoket asignada por el servidor
    private String connectionID;

    // Objeto que contiene los mensajes
    private MessagesHandler messages;

    private HashMap<String, Result> waitingResult;

    private boolean logsEnabled;


    public Connection(String url, Class<? extends MessagesHandler> messages) {
        this(url, messages, false);
    }

    public Connection(String url, Class<? extends MessagesHandler> messages, boolean logsEnabled) {

        this.logsEnabled = logsEnabled;

        // Setea la URL
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

        // Crea el messages de mensajes
        try {
            this.messages = messages.newInstance();
            this.messages.setConnection(this);
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }

        waitingResult = new HashMap<>();
    }


    /**
     * Se conecta con el servidor
     */
    public void start() {

        // Si la URL es inválida, no se puede conectar
        if (uri == null) return;

        // Si había una conexión, la cerramos
        close();

        // Creamos la conexión con el servidor
        websocket = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                Platform.runLater(() -> Connection.this.messages.onOpen(serverHandshake));
            }

            @Override
            public void onMessage(String s) {

                // Le decimos al hilo de JavaFX que ejecute las acciones.
                Platform.runLater(() -> {

                    Gson json = new Gson();
                    Message msg = json.fromJson(s, Message.class);

                    if (logsEnabled) System.out.println("[WS LOG] " + s);

                    switch (msg.getMessageType()) {
                        case ConnectionEvent:
                            Connection.this.connectionID = msg.getData();
                            Connection.this.messages.onConnected(Connection.this.connectionID);
                            break;

                        case Text:
                            Connection.this.messages.onTextMessage(msg.getData());
                            break;

                        case MethodInvocation:

                            InvocationDescriptor invDesc = json.fromJson(msg.getData(), InvocationDescriptor.class);

                            Object[] array = invDesc.getArguments();

                            Object[] args = (Object[])array[0];
                            Class[] classes = (Class[])array[1];

                            // Ejecuta el Método
                            try {
                                Method m = Connection.this.messages.getClass().getDeclaredMethod(invDesc.getMethodName(), classes);
                                Object result = m.invoke(messages, args);

                                // Si la invocación desde servidor espera una respuesta, y mi método dio una respuesta...
                                if (!invDesc.getIdentifier().equals(EMPTY_GUID)) {
                                    InvocationResult invRes = new InvocationResult();
                                    invRes.setIdentifier(invDesc.getIdentifier());

                                    if (result != null)
                                        invRes.setResult(result);
                                    else
                                        invRes.setException("El método del cliente no tiene retorno o devolvió 'null'");

                                    Message msgResult = new Message();
                                    msgResult.setMessageType(MessageType.MethodReturnValue);
                                    msgResult.setData(json.toJsonTree(invRes).toString());

                                    websocket.send(json.toJsonTree(msgResult).toString());
                                }

                            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                                // Si hubo un problema al llamar al método... mostramos la excepción
                                e.printStackTrace();

                                // Le avisamos al servidor en el caso de que haya esperado una respuesta
                                if (!invDesc.getIdentifier().equals(EMPTY_GUID)) {
                                    InvocationResult invRes = new InvocationResult();
                                    invRes.setIdentifier(invDesc.getIdentifier());
                                    invRes.setException("El método del cliente no existe o no está correctamente definido");

                                    Message msgResult = new Message();
                                    msgResult.setMessageType(MessageType.MethodReturnValue);
                                    msgResult.setData(json.toJsonTree(invRes).toString());

                                    websocket.send(json.toJsonTree(msgResult).toString());
                                }
                            }

                            break;

                        case MethodReturnValue:
                            InvocationResult invRes = json.fromJson(msg.getData(), InvocationResult.class);
                            Object[] result = invRes.getResult();

                            Object resultValue;
                            Object resultType; // TODO: darle un uso al tipo

                            if (result != null) {
                                resultValue = result[0];
                                resultType = (Class)result[1];
                            }
                            else {
                                resultValue = null;
                                resultType = null;
                            }
                            Object exception = invRes.getException();

                            Result res = waitingResult.get(invRes.getIdentifier());
                            res.getResultListener().onResult(resultValue, exception);
                            waitingResult.remove(invRes.getIdentifier());

                            break;
                    }
                });
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                Platform.runLater(() -> Connection.this.messages.onClose(i, s, b));
            }

            @Override
            public void onError(Exception e) {
                Platform.runLater(() -> Connection.this.messages.onError(e));
            }
        };

        // Agregado: soporte a WSS (TODO: hacerlo configurable)


        /*if (uri.getScheme().equals("wss")) {
            try {
                SSLContext sslContext = SSLContext.getInstance( "TLS" );
                //sslContext.init( kmf.getKeyManagers(), tmf.getTrustManagers(), null );
                sslContext.init( null, null, null ); // will use java's default key and trust store which is sufficient unless you deal with self-signed certificates


                SSLSocketFactory factory = sslContext.getSocketFactory();// (SSLSocketFactory) SSLSocketFactory.getDefault();

                websocket.setSocket( factory.createSocket() );

                websocket.connectBlocking();
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        else {
            websocket.connect();
        }*/


        // Conectamos con el servidor
        websocket.connect();
    }


    /**
     * Cierra la conexión actual si es que existe y no está cerrada
     */
    public void close() {
        if (websocket != null && !websocket.isClosed())
           websocket.close();
    }


    /**
     * Invoca un método del servidor esperando una respuesta
     *
     * @param methodName nombre del método a llamar
     * @param args objetos que luego son serializados a JSON.
     * @return Un objeto result, para agregarle comportamiento según un resultado o error
     */
    public Result invoke(String methodName, Object... args) {

        Gson json = new Gson();

        InvocationDescriptor invDesc = new InvocationDescriptor();
        invDesc.setMethodName(methodName);
        invDesc.setArguments(args);
        invDesc.setIdentifier(UUID.randomUUID().toString());

        Message msg = new Message();
        msg.setMessageType(MessageType.MethodInvocation);
        msg.setData(json.toJsonTree(invDesc).toString());

        Result result = new Result();

        // setear la guid en la lista
        waitingResult.put(invDesc.getIdentifier(), result);

        if (websocket.isOpen())
            websocket.send(json.toJsonTree(msg).toString());

        return result;
    }


    /**
     * Invoca un método del servidor sin esperar una respuesta
     * @param methodName
     * @param args
     */
    public void invokeOnly(String methodName, Object... args) {

        Gson json = new Gson();

        InvocationDescriptor invDesc = new InvocationDescriptor();
        invDesc.setMethodName(methodName);
        invDesc.setArguments(args);
        invDesc.setIdentifier(EMPTY_GUID);

        Message msg = new Message();
        msg.setMessageType(MessageType.MethodInvocation);
        msg.setData(json.toJsonTree(invDesc).toString());

        if (websocket.isOpen())
            websocket.send(json.toJsonTree(msg).toString());
    }


    public MessagesHandler getMessages() {
        return messages;
    }


    public String getConnectionID() {
        return connectionID;
    }

}
