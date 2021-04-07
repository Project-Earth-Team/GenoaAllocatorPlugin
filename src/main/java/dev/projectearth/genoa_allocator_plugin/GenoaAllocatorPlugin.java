package dev.projectearth.genoa_allocator_plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.sun.net.httpserver.HttpServer;
import dev.projectearth.genoa_allocator_plugin.utils.IpInformation;
import dev.projectearth.genoa_allocator_plugin.utils.ServerBuildplateRequest;
import dev.projectearth.genoa_allocator_plugin.utils.ServerInstanceInfo;
import dev.projectearth.genoa_allocator_plugin.utils.ServerInstanceRequestInfo;
import dev.projectearth.genoa_plugin.GenoaPlugin;
import dev.projectearth.genoa_plugin.utils.BuildplateLoader;
import lombok.Getter;
import org.apache.logging.log4j.core.util.IOUtils;
import org.cloudburstmc.api.plugin.PluginContainer;
import org.cloudburstmc.server.CloudServer;
import org.cloudburstmc.server.event.player.PlayerJoinEvent;
import org.cloudburstmc.server.event.player.PlayerQuitEvent;
import org.cloudburstmc.server.level.Location;
import org.cloudburstmc.server.player.Player;
import org.cloudburstmc.server.utils.Identifier;
import org.cloudburstmc.server.utils.genoa.GenoaServerCommand;
import org.cloudburstmc.server.utils.genoa.GenoaUtils;
import org.iq80.leveldb.util.FileUtils;
import org.slf4j.Logger;
import org.cloudburstmc.api.plugin.Plugin;
import org.cloudburstmc.api.plugin.PluginDescription;
import org.cloudburstmc.server.event.Listener;
import org.cloudburstmc.server.event.server.ServerInitializationEvent;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Plugin(id = "GenoaAllocatorPlugin", name = "Genoa Allocator Plugin", version = "1.0.0")
public class GenoaAllocatorPlugin implements PluginContainer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static GenoaAllocatorPlugin INSTANCE;

    @Getter
    private final Logger logger;
    @Getter
    private final PluginDescription description;
    @Getter
    private final Path dataDirectory;
    private final CloudServer server;

    private final GenoaPlugin normalPlugin;

    private final Map<String, UUID> playerInstanceMap = new HashMap<>();
    private final Map<UUID, UUID> instanceBuildplateMap = new HashMap<>();

    private UUID serverApiKey;

    @Inject
    private GenoaAllocatorPlugin(Logger logger, PluginDescription description, Path dataDirectory) {
        this.logger = logger;
        this.description = description;
        this.dataDirectory = dataDirectory;

        this.server = CloudServer.getInstance();

        if (this.server.getPluginManager().getPlugin("GenoaPlugin").isPresent()) {
            this.normalPlugin = GenoaPlugin.get();
        } else {
            this.normalPlugin = null; // Just so IntelliJ stops complaining
            this.logger.error("The normal Genoa plugin was not found in your cloudburst installation.");
            this.logger.error("Make sure it is installed correctly!");
            this.server.shutdown();
        }

        INSTANCE = this;
    }

    @Listener
    public void onInitialization(ServerInitializationEvent event) {
        this.logger.info("Genoa allocator plugin loading...");

        registerServer();
        try {
            startListeningServer();
        } catch (Exception e) {
            this.logger.info("An error occured while starting the allocator server.");
            e.printStackTrace();
            this.server.shutdown();
        }

        this.logger.info("Genoa allocator plugin has loaded!");
    }

    private void registerServer() {
        try {

        IpInformation ipInformation = new IpInformation();
        ipInformation.setIp(getOutboundIp());
        ipInformation.setPort(this.server.getPort());

        String apiKey = GenoaUtils.SendApiCommand(GenoaServerCommand.RegisterServer, null, OBJECT_MAPPER.writeValueAsString(ipInformation));

        GenoaUtils.setServerApiKey(UUID.fromString(apiKey));

        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private String getOutboundIp() {
        // https://stackoverflow.com/a/38342964
        try(final DatagramSocket socket = new DatagramSocket()){
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            String ip = socket.getLocalAddress().getHostAddress();
            return ip;
        } catch (UnknownHostException | SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void startListeningServer() throws IOException {
        // TODO: Add http server (private/instance/new)
        HttpServer server = HttpServer.create(new InetSocketAddress(81), 0);
        server.createContext("/private/instance/new", (request -> {

            String requestString = new BufferedReader(new InputStreamReader(request.getRequestBody())).lines().collect(Collectors.joining());
            ServerInstanceRequestInfo instanceRequestInfo = OBJECT_MAPPER.readValue(requestString, ServerInstanceRequestInfo.class);
            onBuildplateLoadRequest(instanceRequestInfo);

            request.sendResponseHeaders(200, 0);
            request.close();
        }));
        server.setExecutor(null);
        server.start();
    }

    private void onBuildplateLoadRequest(ServerInstanceRequestInfo instance) {

        downloadBuildplate(instance.getBuildplateId(), instance.getPlayerId());

        BuildplateLoader.registerBuildplate(instance.getBuildplateId().toString());

        this.playerInstanceMap.put(instance.getPlayerId(), instance.getInstanceId());
        this.instanceBuildplateMap.put(instance.getInstanceId(), instance.getBuildplateId());

        markServerAsReady(instance.getInstanceId());
    }

    private void markServerAsReady(UUID instanceId) {
        ServerInstanceInfo info = new ServerInstanceInfo();
        info.setInstanceId(instanceId);
        info.setBuildplateId(this.instanceBuildplateMap.get(instanceId));

        try {
            String request = OBJECT_MAPPER.writeValueAsString(info);
            GenoaUtils.SendApiCommand(GenoaServerCommand.MarkServerAsReady, null, request);
        } catch (Exception e) {
            this.logger.error("Something went wrong while trying to mark the server as ready.");
            e.printStackTrace();
        }
    }

    private void downloadBuildplate(UUID buildplateId, String playerId) {
        ServerBuildplateRequest req = new ServerBuildplateRequest();
        req.setBuildplateId(buildplateId);
        req.setPlayerId(playerId);

        try {

        String request = OBJECT_MAPPER.writeValueAsString(req);
        String buildplate = GenoaUtils.SendApiCommand(GenoaServerCommand.GetBuildplate, null, request);

        File buildplateFile = new File(buildplateId.toString() + ".json");
        buildplateFile.createNewFile();

        FileWriter fileWriter = new FileWriter(buildplateId.toString() + ".json");
        fileWriter.write(buildplate);
        fileWriter.close();

        } catch (IOException e) {
            this.logger.error("An error occured while downloading the buildplate!");
            e.printStackTrace();
        }
    }

    @Listener
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerId = player.getSkin().getSkinId().split("-")[5].toUpperCase();

        if (playerInstanceMap.containsKey(playerId)) {
            String buildplateId = instanceBuildplateMap.get(playerInstanceMap.get(playerId)).toString();
            Location spawnLocation = this.server.getLevel(buildplateId).getSpawnLocation();
            player.teleportImmediate(spawnLocation);
        }
    }

    @Listener
    public void onDisconnect(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerId = player.getSkin().getSkinId().split("-")[5].toUpperCase();

        if (playerInstanceMap.containsKey(playerId)) {
            UUID instanceId = playerInstanceMap.get(playerId);
            UUID buildplateId = instanceBuildplateMap.get(instanceId);
            playerInstanceMap.remove(playerId);
            instanceBuildplateMap.remove(instanceId);

            this.server.unloadLevel(this.server.getLevel(buildplateId.toString()));

            deleteBuildplate(buildplateId);
            // TODO: Add request to edit buildplate on core api so that the model gets updated
        }
    }

    public void deleteBuildplate(UUID buildplateId) {
        File buildplateFile = new File(buildplateId.toString() + ".json");
        try {
            buildplateFile.delete();
        } catch (Exception e) {
            this.logger.error("An error occured while trying to delete a buildplate.");
            e.printStackTrace();
        }
    }

    public static GenoaAllocatorPlugin get() {
        return INSTANCE;
    }

    @Override
    public Object getPlugin() {
        return this;
    }
}
