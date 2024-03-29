package server;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import uber.proto.objects.ID;
import uber.proto.zk.Server;
import uber.proto.zk.Shard;
import zookeeper.ZK;
import zookeeper.ZKPath;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ServersWatcher {
    static final Logger log = LogManager.getLogger();

    final ShardServer server;

    final Map<UUID, Map<UUID, Boolean>> watchRemove;
    final Map<UUID, List<Runnable>> groupsWatchers;
    final Executor executor;

    public ServersWatcher(ShardServer server, Executor executor) {
        this.server = server;
        watchRemove = new ConcurrentHashMap<>();
        groupsWatchers = new ConcurrentHashMap<>();
        this.executor = executor;
    }

    public void initialize() throws KeeperException, InterruptedException {
        this.watchShards();
    }

    void addShard(UUID shardID) {
        Function<UUID, Map<UUID, String>> computer = k -> {
            var path = ZK.Path("shards", shardID.toString());
            uber.proto.zk.Shard data = null;
            try {
                byte[] _data = server.zk.getData(path);
                data = Shard.parseFrom(_data);
            } catch (KeeperException e) {
                log.error("Error when pulling shard data", e);
            } catch (InvalidProtocolBufferException e) {
                log.error("Shard (ProtocolBuf object) failed to parse, shouldn't happen", e);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Map<UUID, String> cities = new ConcurrentHashMap<>();
            for (int i = 0; i < data.getCitiesCount(); i++) {
                var city = data.getCities(i);
                var id = utils.UUID.fromID(city.getId());
                var name = city.getName();
                cities.put(id, name);
                server.cityShard.putIfAbsent(id, shardID);
                server.cityID.putIfAbsent(name, id);
                server.cityName.putIfAbsent(id, name);
                var location = city.getLocation();
                server.cityLoc.putIfAbsent(id, location);
                log.info("Added City {} at location ({},{}) to shard {} ",
                        name, location.getX(), location.getY(), shardID);
            }
            return cities;
        };
        server.shardsCities.computeIfAbsent(shardID, computer);
    }
    void watchShards() throws InterruptedException, KeeperException {
        var path = ZK.Path("shards");
        try {
            server.zk.createPersistentPath(path);
        } catch (KeeperException e) {
            log.error("Error upon creating shard root ZNode", e);
            throw e;
        }

        List<ZKPath> children = null;
        try {
            children = server.zk.getChildren(path);
        } catch (KeeperException | InterruptedException err) {
            err.printStackTrace();
            log.error("Exception on getting existing shards list", err);
            throw err;
        }

        for (var child : children) {
            watchShard(child);
        }
        server.zk.addPersistentRecursiveWatch(path, this::shardsWatcher);
    }


    void shardsWatcher(WatchedEvent e) {
        if (e.getType() == Watcher.Event.EventType.NodeCreated) {
            var path = ZKPath.fromStr(e.getPath());
            if (path.length() != 2) {
                return;
            }
            watchShard(path);
        }

    }
    private void watchShard(ZKPath path) {
        UUID shardID = UUID.fromString(path.get(path.length() - 1));
        this.addShard(shardID);

        path = path.append("servers");
        try {
            server.zk.createPersistentPath(path);
        } catch (KeeperException | InterruptedException err) {
            log.error("Error upon creating shard root ZNode", err);
        }

        try {
            final var pathCopy = path;
            server.zk.addPersistentRecursiveWatch(path, event -> {
                log.debug("Watch event for path {} : {}", pathCopy.str(), event);
                if (event.getType() == Watcher.Event.EventType.NodeCreated) {
                    var epath = ZKPath.fromStr(event.getPath());
                    addServerToShardMembership(shardID, epath);
                    logMembership(shardID);

                } else if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                    var epath = ZKPath.fromStr(event.getPath());
                    removeServerFromShardMembership(shardID, epath);
                    logMembership(shardID);
                }
            });
        } catch (KeeperException | InterruptedException err) {
            log.error("Exception when adding watch to shard " + shardID, err);
        }
        List<ZKPath> children = null;
        try {
            children = server.zk.getChildren(path);
        } catch (KeeperException | InterruptedException err) {
            err.printStackTrace();
            log.error("Exception on getting existing node's children", err);
            return;
        }

        for (var child : children) {
            addServerToShardMembership(shardID, child);
        }
        logMembership(shardID);
    }
    void addServerToShardMembership(UUID shardID, ZKPath path) {
        UUID id = UUID.fromString(path.get(path.length() - 1));

        Function<UUID, Server> computer = (x) -> {
            log.debug("Adding new server from path {}", path.str());
            Server s = null;
            try {
                byte[] data = server.zk.getData(path);
                s = Server.parseFrom(data);
            } catch (KeeperException e) {
                if (e.code() == KeeperException.Code.NONODE) {
                    log.error("Created node does not exist in Membership Updating (Watcher)", e);
                } else {
                    log.error("KeeperException in Membership Updating (Watcher)", e);
                    e.printStackTrace();
                }
                return null;
            } catch (InterruptedException e) {
                e.printStackTrace();
                log.error("InterruptedException in Membership Updating (Watcher)", e);
                return null;
            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
                log.error("Server (ProtocolBuf object) failed to parse, shouldn't happen", e);
            }
            log.info("Added new server {} to shard {}, at {}:{}(grpc),{}(rest)",
                    id, shardID, s.getHost(), s.getPorts().getGrpc(), s.getPorts().getRest());
            return s;
        };


        var shard = getShard(shardID);

        shard.computeIfAbsent(id, computer);

    }
    private Map<UUID, Server> getShard(UUID shardID) {
        return server.shardsServers
                .computeIfAbsent(shardID, k -> {
                    log.info("Adding new shard {}", k);
                    return new ConcurrentHashMap<>();
                });
    }
    private void logMembership(UUID shardID) {
        var shard = getShard(shardID);
        log.info("Updated membership of shard has {} servers #{} :\n\t{}",
                shard.size(), shardID,
                shard.keySet()
                        .stream()
                        .map(UUID::toString)
                        .collect(Collectors.joining("\n\t"))
        );
    }

    void removeServerFromShardMembership(UUID shardID, ZKPath path) {
        UUID serverID = UUID.fromString(path.get(path.length() - 1));
        var shard = getShard(shardID);
        shard.remove(serverID);

        Runnable onRemove = () -> {
            var groups = getWatchRemoveServerList(serverID);
            for (UUID group : groups.keySet()) {
                var groupList = this.groupsWatchers.get(group);
                if (groupList == null) continue;// Double checked locking

                synchronized (groupList) {
                    if (this.groupsWatchers.get(group) == null) continue; // Double checked locking
                    for (Runnable runnable : groupList) {
                        try {
                            runnable.run();
                        } catch (Exception e) {
                            log.error("Exception when running remove watcher", e);
                        }
                    }
                    removeWatchRemoveGroup(group);
                }
            }
        };

        executor.execute(onRemove);
        log.info("Removed server {} from shard {}", serverID, shardID);
    }

    List<Runnable> getWatchRemoveGroup(UUID groupID) {
        return this.groupsWatchers.computeIfAbsent(groupID,
                k -> Collections.synchronizedList(new LinkedList<>()));
    }

    public List<Runnable> removeWatchRemoveGroup(UUID groupID) {
        return this.groupsWatchers.remove(groupID);
    }

    Map<UUID, Boolean> getWatchRemoveServerList(UUID serverID) {
        return this.watchRemove.computeIfAbsent(serverID,
                k -> new ConcurrentHashMap<>());
    }

    public void addWatchRemoveGroup(UUID serverID, UUID groupID) {
        var server = getWatchRemoveServerList(serverID);
        server.put(groupID, true);
    }
    public void addWatchRemove(UUID groupID, Runnable onRemove) {
        var group = getWatchRemoveGroup(groupID);
        group.add(onRemove);
    }

}
