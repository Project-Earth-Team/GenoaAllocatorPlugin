package dev.projectearth.genoa_allocator_plugin.utils;

import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;
import com.sun.org.apache.xerces.internal.impl.dv.util.HexBin;
import dev.projectearth.genoa_allocator_plugin.GenoaAllocatorPlugin;
import org.cloudburstmc.server.CloudServer;
import org.cloudburstmc.server.utils.genoa.GenoaUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class ApiClient extends WebSocketClient {

    private AuthStages currentStage = AuthStages.NotAuthed;

    public ApiClient(URI serverUri) {
        super(serverUri);
    }

    public ApiClient(URI serverUri, Draft draft) {
        super(serverUri, draft);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        GenoaAllocatorPlugin.get().getLogger().info("WebSocket connection established!");
    }

    @Override
    public void onMessage(String message) {
        // TODO: Add actual logic here
        // TODO: GET WORKING :)
        switch (currentStage) {
            case NotAuthed:
                send(encodeChallenge(message));
                currentStage = AuthStages.AuthStage1;
                break;

            case AuthStage1:
                if (Boolean.parseBoolean(message)) currentStage = AuthStages.AuthStage2;
                else {
                    GenoaAllocatorPlugin.get().getLogger().error("Core API verification failed!");
                    CloudServer.getInstance().shutdown();
                }
                break;

            case AuthStage2:
                GenoaUtils.setServerApiKey(message);
                currentStage = AuthStages.Authed;
                break;

            case Authed:
                new Thread(() -> GenoaAllocatorPlugin.get().onBuildplateLoadRequest(message)).start();
                break;

        }
    }

    private String encodeChallenge(String challenge) {
        try {
            String key = Files.lines(Paths.get(GenoaAllocatorPlugin.get().getDataDirectory() + "/key.txt")).collect(Collectors.joining());
            byte[] keyBytes = Base64.decode(key);
            Mac HmacSha256 = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(keyBytes, "HmacSHA256");
            HmacSha256.init(secret_key);

            return HexBin.encode(HmacSha256.doFinal(challenge.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            GenoaAllocatorPlugin.get().getLogger().error("Error while trying to encode Challenge!");
            e.printStackTrace();
        }

        return null;
    }


    // https://github.com/TooTallNate/Java-WebSocket/blob/master/src/main/example/ExampleClient.java

    @Override
    public void onError(Exception ex) {
        ex.printStackTrace();
        // if the error is fatal then onClose will be called additionally
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println(
                "Connection closed by " + (remote ? "remote peer" : "us") + " Code: " + code + " Reason: "
                        + reason);
    }

}
