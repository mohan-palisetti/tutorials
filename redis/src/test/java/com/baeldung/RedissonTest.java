package com.baeldung;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.redisson.Redisson;
import org.redisson.RedissonMultiLock;
import org.redisson.api.*;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.Assert.assertEquals;

public class RedissonTest {

    private static RedisServer redisServer;
    private static RedissonClient client;

    @BeforeClass
    public static void setUp() throws IOException {
        redisServer = new RedisServer(6379);
        redisServer.start();
        client = Redisson.create();
    }

    @AfterClass
    public static void destroy() {
        redisServer.stop();
        client.shutdown();
    }

    @Test
    public void givenMultipleKeysInRedis_thenGetAllKeys() {
        client.getBucket("key1").set("key1");
        client.getBucket("key2").set("key2");
        client.getBucket("key3").set("key3");

        RKeys keys = client.getKeys();

        assert(keys.count() >= 3);
    }

    @Test
    public void givenKeysWithPatternInRedis_thenGetPatternKeys() {
        client.getBucket("key1").set("key1");
        client.getBucket("key2").set("key2");
        client.getBucket("key3").set("key3");
        client.getBucket("id4").set("id4");

        RKeys keys = client.getKeys();

        Iterable<String> keysWithPattern
          = keys.getKeysByPattern("key*");

        List keyWithPatternList
          = StreamSupport.stream(
            keysWithPattern.spliterator(),
              false).collect(Collectors.toList());

        assert(keyWithPatternList.size() == 3);
    }

    @Test
    public void givenAnObject_thenSaveToRedis() {
        RBucket<Ledger> bucket = client.getBucket("ledger");
        Ledger ledger = new Ledger();
        ledger.setName("ledger1");
        bucket.set(ledger);

        Ledger returnedLedger = bucket.get();

        assert(
          returnedLedger != null
            && returnedLedger.getName().equals("ledger1"));
    }

    @Test
    public void givenALong_thenSaveLongToRedisAndAtomicallyIncrement(){
        Long value = 5L;

        RAtomicLong atomicLong
          = client.getAtomicLong("myAtomicLong");
        atomicLong.set(value);
        Long returnValue = atomicLong.incrementAndGet();

        assert(returnValue == 6L);
    }

    @Test
    public void givenTopicSubscribedToAChannel_thenReceiveMessageFromChannel() throws ExecutionException, InterruptedException {
        CompletableFuture<String> future = new CompletableFuture<>();

        RTopic<CustomMessage> subscribeTopic = client.getTopic("baeldung");
        subscribeTopic.addListener(new MessageListener<CustomMessage>() {
            @Override
            public void onMessage(String channel, CustomMessage customMessage) {
                future.complete(customMessage.getMessage());
            }
        });

        RTopic<CustomMessage> recieveTopic = client.getTopic("baeldung");
        long clientsReceivedMessage
          = recieveTopic.publish(new CustomMessage("This is a message"));

        assertEquals("This is a message", future.get());

    }

    @Test
    public void givenAMap_thenSaveMapToRedis(){
        RMap<String, Ledger> map = client.getMap("ledger");
        map.put("123", new Ledger("ledger"));

        assert(map.get("123").getName().equals("ledger"));
    }

    @Test
    public void givenASet_thenSaveSetToRedis(){
        RSet<Ledger> ledgerSet = client.getSet("ledgerSet");
        ledgerSet.add(new Ledger("ledger"));

        assert(ledgerSet.contains(new Ledger("ledger")));
    }

    @Test
    public void givenAList_thenSaveListToRedis(){
        RList<Ledger> ledgerList = client.getList("ledgerList");
        ledgerList.add(new Ledger("ledger"));

        assert(ledgerList.contains(new Ledger("ledger")));
    }

    @Test
    public void givenLockSet_thenEnsureCanUnlock(){
        RLock lock = client.getLock("lock");
        lock.lock();
        assert(lock.isLocked());

        lock.unlock();
        assert(!lock.isLocked());
    }

    @Test
    public void givenMultipleLocksSet_thenEnsureAllCanUnlock(){
        RedissonClient clientInstance1 = Redisson.create();
        RedissonClient clientInstance2 = Redisson.create();
        RedissonClient clientInstance3 = Redisson.create();

        RLock lock1 = clientInstance1.getLock("lock1");
        RLock lock2 = clientInstance2.getLock("lock2");
        RLock lock3 = clientInstance3.getLock("lock3");

        RedissonMultiLock lock = new RedissonMultiLock(lock1, lock2, lock3);
        lock.lock();
        assert(lock1.isLocked() && lock2.isLocked() && lock3.isLocked());

        lock.unlock();
        assert(!(lock1.isLocked() || lock2.isLocked() || lock3.isLocked()));
    }

    @Test
    public void givenRemoteServiceMethodRegistered_thenInvokeMethod(){
        RRemoteService remoteService = client.getRemoteService();
        LedgerServiceImpl ledgerServiceImpl = new LedgerServiceImpl();

        remoteService.register(LedgerServiceInterface.class, ledgerServiceImpl);


        LedgerServiceInterface ledgerService
                = remoteService.get(LedgerServiceInterface.class);

        List<String> entries = ledgerService.getEntries(10);

        assert(entries.size() == 3 && entries.contains("entry1"));
    }

    @Test
    public void givenLiveObjectPersisted_thenGetLiveObject(){
        RLiveObjectService service = client.getLiveObjectService();

        LedgerLiveObject ledger = new LedgerLiveObject();
        ledger.setName("ledger1");

        ledger = service.persist(ledger);

        LedgerLiveObject returnLedger
          = service.get(LedgerLiveObject.class, "ledger1");

        assert(ledger.getName().equals(returnLedger.getName()));
    }

    @Test
    public void givenMultipleOperations_thenDoAllAtomically(){
        RBatch batch = client.createBatch();
        batch.getMap("ledgerMap").fastPutAsync("1", "2");
        batch.getMap("ledgerMap").putAsync("2", "5");

        List<?> result = batch.execute();

        RMap<String, String> map = client.getMap("ledgerMap");
        assert(result.size() > 0 && map.get("1").equals("2"));
    }

    @Test
    public void givenLUAScript_thenExecuteScriptOnRedis(){
        client.getBucket("foo").set("bar");
        String result = client.getScript().eval(RScript.Mode.READ_ONLY,
                "return redis.call('get', 'foo')", RScript.ReturnType.VALUE);

        assert(result.equals("bar"));
    }

    @Test
    public void givenLowLevelRedisCommands_thenExecuteLowLevelCommandsOnRedis(){
        RedisClient client = new RedisClient("localhost", 6379);
        RedisConnection conn = client.connect();
        conn.sync(StringCodec.INSTANCE, RedisCommands.SET, "test", 0);

        String testValue = conn.sync(StringCodec.INSTANCE, RedisCommands.GET, "test");

        conn.closeAsync();
        client.shutdown();

        assert(testValue.equals("0"));
    }
}
