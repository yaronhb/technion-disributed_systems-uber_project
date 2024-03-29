package server;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.javatuples.Pair;
import uber.proto.objects.Ride;
import uber.proto.zk.Task;
import uber.proto.zk.TaskList;
import zookeeper.ZKConnection;
import zookeeper.ZKPath;

import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class QueueProcessor {
    final static Logger log = LogManager.getLogger();

    final ShardServer server;
    final ZKConnection zk;
    final BlockingQueue<Pair<Integer, TaskList>> tasksQueue;
    private final ZKPath queueRoot;

    Object insertLock;
    AtomicInteger lastOp;
    AtomicInteger lastAddedOp;

    public QueueProcessor(ShardServer server, ZKConnection zk) {
        this.server = server;
        this.zk = zk;
        tasksQueue = new PriorityBlockingQueue<>(
                100,
                Comparator.comparingInt(Pair::getValue0)
        );

        this.queueRoot = server.shardRoot.append("queue");
        insertLock = new Object();
    }

    public void initialize() throws KeeperException, InterruptedException {
        lastOp = new AtomicInteger(-1);
        lastAddedOp = new AtomicInteger(-1);
        this.updateTasks();
        new Thread(this::processTasks).start();
    }

    void doTaskList(List<Task> taskList, Integer opID) {
        for (Task item : taskList) {
            switch (item.getTaskCase()) {
                case RESERVE -> {
                    var task = item.getReserve();
                    var reservation = task.getReservation();
                    var rideID = utils.UUID.fromID(task.getRideID());
                    var seat = task.getSeat();
                    var srcCity = utils.UUID.fromID(task.getSource().getId());

                    this.server.data.reserveSeat(srcCity, rideID, seat, reservation);
                }
                case INVALIDSEATLOCK -> {
                    var task = item.getInvalidSeatLock();
                    var rideID = utils.UUID.fromID(task.getRideID());
                    var seat = task.getSeat();
                    try {
                        this.server.invalidateSeatLock(rideID, seat);
                    } catch (InterruptedException | KeeperException e) {
                        log.error("Exception thrown during processing invalidation task", e);
                    }
                }
                case SNAPSHOT -> {
                    var task = item.getSnapshot();
                    var snapshotID = utils.UUID.fromID(task.getSnapshotID());
                    var toServerID = utils.UUID.fromID(task.getSendTo().getServerID());
                    var toShardID = utils.UUID.fromID(task.getSendTo().getShardID());
                    var targetServerID = utils.UUID.fromID(task.getRequestedServer().getServerID());

                    if (targetServerID.equals(this.server.id)) {
                        this.server.sendSnapshot(snapshotID, toShardID, toServerID);
                    }
                }

                case ADDRIDE -> {
                    var task = item.getAddRide();
                    var ride = task.getRide();
                    var rideID = utils.UUID.fromID(ride.getId());

                    server.data.addRide(rideID, ride);
                }
                case ADDPATH -> {
                    var task = item.getAddPath();
                    var path = task.getPath();
                    var transactionID = utils.UUID.fromID(path.getTransactionID());

                    server.data.addPath(transactionID, path);
                }
                case TASK_NOT_SET -> {
                    log.warn("Task in task list {} is empty", opID);
                }
            }
        }
    }


    void updateTasks() {
        log.debug("Updating tasks from shard task queue");
        try {

            final var filter = String.format("op_%010d", lastAddedOp.get());
            var children = this.zk
                    .getChildrenStr(queueRoot, this::watchForUpdate)
                    .stream().filter(s -> filter.compareTo(s) < 0)
                    .sorted()
                    .collect(Collectors.toList());
            log.debug("Filtered tasks the downloaded tasks from queue ()");
            synchronized (insertLock) {
                for (var child : children) {
                    int opID = Integer.parseInt(child.substring("op_".length()));
                    if (lastAddedOp.get() < opID) {
                        TaskList taskList = getTaskList(child);
                        tasksQueue.add(Pair.with(opID, taskList));
                        lastAddedOp.set(opID);

                        log.debug("Added new task list {} to tasks queue", opID);
                    }
                }
            }
            log.debug("Finished updating tasks from shard task queue");
        } catch (KeeperException e) {
            log.error("KeeperException during downloading tasks from the queue", e);
        } catch (InterruptedException e) {
            log.error("InterruptedException during downloading tasks from the queue", e);
        }
    }
    private void watchForUpdate(WatchedEvent event) {
        if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
            this.updateTasks();
        }
    }

    private TaskList getTaskList(String child) throws KeeperException, InterruptedException {
        var data = this.zk.getData(queueRoot.append(child));
        try {
            return TaskList.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            log.error("Failed to parse task list", e);
        }
        return null;
    }


    void processTasks() {
        log.info("Queue processor started");
        for (; ; ) {
            try {
                var lastOP = lastOp.incrementAndGet();
                Integer opID;
                TaskList taskList;
                while (true) {
                    var element = tasksQueue.take();
                    opID = element.getValue0();
                    taskList = element.getValue1();
                    if (opID == lastOP) {
                        break;
                    } else {
                        tasksQueue.add(element);
                    }
                }

                log.info("Started handling task {} for tasks queue", opID);
                try {
                    this.doTaskList(taskList.getTaskListList(), opID);
                } catch (Exception e) {
                    log.error(new ParameterizedMessage("An exception was thrown while processing task list {}\n:{}", opID), e);
                }
                log.info("Handling task {} for tasks queue was successful", opID);

                this.markTaskDoneAsync(opID);

            } catch (InterruptedException e) {
                log.warn("Interrupted exception was thrown during a task process", e);
            }
        }
    }

    public void markTaskDoneAsync(int opID) {
        log.debug("Marking Task {} as done", opID);
        var taskPath = queueRoot.append(String.format("op_%010d", lastAddedOp.get()));
        var myPath = taskPath.append(this.server.id.toString());

        this.zk.createNode(myPath, CreateMode.PERSISTENT, (rc1, path) -> {
            if (rc1 != KeeperException.Code.OK) {
                log.warn("Error on creation of task {} done indicator node:\nPath: {}\nSent Path: {}\nCode: {}", opID, path.str(), myPath.str(), rc1);
                return;
            }

            this.zk.getChildrenStr(taskPath, (rc2, children) -> {
                if (rc2 != KeeperException.Code.OK) {
                    log.error("Error on get children of task {} done indicator node\n:{}", opID, rc2);
                    return;
                }
                var serversThatInvalidated = children
                        .stream()
                        .map(UUID::fromString)
                        .collect(Collectors.toSet());
                var servers = this.server.serversInShard().keySet();

                if (serversThatInvalidated.containsAll(servers)) {
                    this.zk.deleteSubTree(taskPath, rc3 -> {
                        log.info("Task {} was permanently removed from the queue", opID);
                    });
                }
            });

        });
    }

}