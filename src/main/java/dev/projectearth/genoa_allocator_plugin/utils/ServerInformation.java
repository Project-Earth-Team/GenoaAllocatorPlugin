package dev.projectearth.genoa_allocator_plugin.utils;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ServerInformation {
    private UUID serverId;
    private String ip;
    private int port;
}
