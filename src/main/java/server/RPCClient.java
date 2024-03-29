package server;

import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uber.proto.rpc.ServerCommunicationGrpc;
import uber.proto.rpc.ShardCommunicationGrpc;
import uber.proto.rpc.UberRideServiceGrpc;
import utils.Host;

import java.net.UnknownHostException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;

public class RPCClient {
    static final Logger log = LogManager.getLogger();

    final ShardServer server;
    final Executor executor;

    final Map<String, Channel> channels;
    final Map<UUID, UberRideServiceGrpc.UberRideServiceBlockingStub> serviceRPCStubs;
    final Map<UUID, ShardCommunicationGrpc.ShardCommunicationStub> shardRPCStubs;
    final Map<UUID, ServerCommunicationGrpc.ServerCommunicationStub> serverRPCStubs;

    public RPCClient(ShardServer server, Executor executor) {
        this.server = server;
        this.serviceRPCStubs = new ConcurrentHashMap<>();
        this.channels = new ConcurrentHashMap<>();
        this.shardRPCStubs = new ConcurrentHashMap<>();
        this.serverRPCStubs = new ConcurrentHashMap<>();
        this.executor = executor;
    }

    public UberRideServiceGrpc.UberRideServiceBlockingStub getServiceServerStub(UUID shardID, UUID serverID) {
        Function<UUID, UberRideServiceGrpc.UberRideServiceBlockingStub> creator = id -> {
            var shard = RPCClient.this
                    .server
                    .shardsServers
                    .get(shardID);
            if (shard == null) {
                log.error("Shard with id {} was not found", shardID);
                return null;
            }

            var server = shard.get(serverID);
            if (server == null) {
                log.error("Server with id {} was not found", serverID);
                return null;
            }

            Channel channel = getChannel(server);
            if (channel == null) return null;
            return UberRideServiceGrpc.newBlockingStub(channel);
        };
        return serviceRPCStubs.computeIfAbsent(serverID, creator);
    }
    public ServerCommunicationGrpc.ServerCommunicationStub getServerStub(UUID shardID, UUID serverID) {
        Function<UUID, ServerCommunicationGrpc.ServerCommunicationStub> creator = id -> {
            var shard = RPCClient.this
                    .server
                    .shardsServers
                    .get(shardID);
            if (shard == null) {
                log.error("Shard with id {} was not found", shardID);
                return null;
            }

            var server = shard.get(serverID);
            if (server == null) {
                log.error("Server with id {} was not found", serverID);
                return null;
            }

            Channel channel = getChannel(server);
            if (channel == null) return null;
            return ServerCommunicationGrpc.newStub(channel);
        };
        return serverRPCStubs.computeIfAbsent(serverID, creator);
    }

    public ShardCommunicationGrpc.ShardCommunicationStub getShardServerStub(UUID serverID) {
        Function<UUID, ShardCommunicationGrpc.ShardCommunicationStub> creator = id -> {
            var server = RPCClient.this
                    .server.shardsServers.get(this.server.shard).get(serverID);
            if (server == null) {
                log.error("Shard Server with id {} was not found", serverID.toString());
                return null;
            }

            Channel channel = getChannel(server);
            if (channel == null) return null;
            return ShardCommunicationGrpc.newStub(channel);
        };
        return shardRPCStubs.computeIfAbsent(serverID, creator);
    }

    private Channel getChannel(uber.proto.zk.Server server) {
        Host host = null;
        try {
            host = new Host(server.getHost(), server.getPorts().getGrpc());
        } catch (UnknownHostException e) {
            log.error("Shard server host is unknown", e);
            return null;
        }
        var channel = RPCClient.this.channels.computeIfAbsent(
                host.str(),
                h -> ManagedChannelBuilder
                        .forTarget(h)
                        // .executor(executor)
                        .usePlaintext()
                        .build()
        );
        return channel;
    }
}
