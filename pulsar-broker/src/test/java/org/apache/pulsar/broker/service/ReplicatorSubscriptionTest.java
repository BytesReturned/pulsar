/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.google.common.collect.Sets;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Cleanup;
import org.apache.bookkeeper.mledger.Position;
import org.apache.pulsar.broker.BrokerTestUtil;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.broker.service.persistent.ReplicatedSubscriptionsController;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.MessageRoutingMode;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.policies.data.PartitionedTopicStats;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.apache.pulsar.common.util.collections.ConcurrentOpenHashMap;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Tests replicated subscriptions (PIP-33)
 */
@Test(groups = "broker")
public class ReplicatorSubscriptionTest extends ReplicatorTestBase {
    private static final Logger log = LoggerFactory.getLogger(ReplicatorSubscriptionTest.class);

    @Override
    @BeforeClass(timeOut = 300000)
    public void setup() throws Exception {
        super.setup();
    }

    @Override
    @AfterClass(alwaysRun = true, timeOut = 300000)
    public void cleanup() throws Exception {
        super.cleanup();
    }

    /**
     * Tests replicated subscriptions across two regions
     */
    @Test
    public void testReplicatedSubscriptionAcrossTwoRegions() throws Exception {
        String namespace = BrokerTestUtil.newUniqueName("pulsar/replicatedsubscription");
        String topicName = "persistent://" + namespace + "/mytopic";
        String subscriptionName = "cluster-subscription";
        // Subscription replication produces duplicates, https://github.com/apache/pulsar/issues/10054
        // TODO: duplications shouldn't be allowed, change to "false" when fixing the issue
        boolean allowDuplicates = true;
        // this setting can be used to manually run the test with subscription replication disabled
        // it shows that subscription replication has no impact in behavior for this test case
        boolean replicateSubscriptionState = true;

        admin1.namespaces().createNamespace(namespace);
        admin1.namespaces().setNamespaceReplicationClusters(namespace, Sets.newHashSet("r1", "r2"));

        @Cleanup
        PulsarClient client1 = PulsarClient.builder().serviceUrl(url1.toString())
                .statsInterval(0, TimeUnit.SECONDS)
                .build();

        // create subscription in r1
        createReplicatedSubscription(client1, topicName, subscriptionName, replicateSubscriptionState);

        @Cleanup
        PulsarClient client2 = PulsarClient.builder().serviceUrl(url2.toString())
                .statsInterval(0, TimeUnit.SECONDS)
                .build();

        // create subscription in r2
        createReplicatedSubscription(client2, topicName, subscriptionName, replicateSubscriptionState);

        Set<String> sentMessages = new LinkedHashSet<>();

        // send messages in r1
        {
            @Cleanup
            Producer<byte[]> producer = client1.newProducer().topic(topicName)
                    .enableBatching(false)
                    .messageRoutingMode(MessageRoutingMode.SinglePartition)
                    .create();
            int numMessages = 6;
            for (int i = 0; i < numMessages; i++) {
                String body = "message" + i;
                producer.send(body.getBytes(StandardCharsets.UTF_8));
                sentMessages.add(body);
            }
            producer.close();
        }

        Set<String> receivedMessages = new LinkedHashSet<>();

        // consume 3 messages in r1
        try (Consumer<byte[]> consumer1 = client1.newConsumer()
                .topic(topicName)
                .subscriptionName(subscriptionName)
                .replicateSubscriptionState(replicateSubscriptionState)
                .subscribe()) {
            readMessages(consumer1, receivedMessages, 3, allowDuplicates);
        }

        // wait for subscription to be replicated
        Thread.sleep(2 * config1.getReplicatedSubscriptionsSnapshotFrequencyMillis());

        // consume remaining messages in r2
        try (Consumer<byte[]> consumer2 = client2.newConsumer()
                .topic(topicName)
                .subscriptionName(subscriptionName)
                .replicateSubscriptionState(replicateSubscriptionState)
                .subscribe()) {
            readMessages(consumer2, receivedMessages, -1, allowDuplicates);
        }

        // assert that all messages have been received
        assertEquals(new ArrayList<>(sentMessages), new ArrayList<>(receivedMessages), "Sent and received " +
                "messages don't match.");
    }

    @Test
    public void testReplicatedSubscribeAndSwitchToStandbyCluster() throws Exception {
        final String namespace = BrokerTestUtil.newUniqueName("pulsar/ns_");
        final String topicName = BrokerTestUtil.newUniqueName("persistent://" + namespace + "/tp_");
        final String subscriptionName = "s1";
        final boolean isReplicatedSubscription = true;
        final int messagesCount = 20;
        final LinkedHashSet<String> sentMessages = new LinkedHashSet<>();
        final Set<String> receivedMessages = Collections.synchronizedSet(new LinkedHashSet<>());
        admin1.namespaces().createNamespace(namespace);
        admin1.namespaces().setNamespaceReplicationClusters(namespace, Sets.newHashSet("r1", "r2"));
        admin1.topics().createNonPartitionedTopic(topicName);
        admin1.topics().createSubscription(topicName, subscriptionName, MessageId.earliest, isReplicatedSubscription);
        final PersistentTopic topic1 =
                (PersistentTopic) pulsar1.getBrokerService().getTopic(topicName, false).join().get();

        // Send messages
        // Wait for the topic created on the cluster2.
        // Wait for the snapshot created.
        final PulsarClient client1 = PulsarClient.builder().serviceUrl(url1.toString()).build();
        Producer<String> producer1 = client1.newProducer(Schema.STRING).topic(topicName).enableBatching(false).create();
        Consumer<String> consumer1 = client1.newConsumer(Schema.STRING).topic(topicName)
                .subscriptionName(subscriptionName).replicateSubscriptionState(isReplicatedSubscription).subscribe();
        for (int i = 0; i < messagesCount / 2; i++) {
            String msg = i + "";
            producer1.send(msg);
            sentMessages.add(msg);
        }
        Awaitility.await().untilAsserted(() -> {
            ConcurrentOpenHashMap<String, ? extends Replicator> replicators = topic1.getReplicators();
            assertTrue(replicators != null && replicators.size() == 1, "Replicator should started");
            assertTrue(replicators.values().iterator().next().isConnected(), "Replicator should be connected");
            assertTrue(topic1.getReplicatedSubscriptionController().get().getLastCompletedSnapshotId().isPresent(),
                    "One snapshot should be finished");
        });
        final PersistentTopic topic2 =
                (PersistentTopic) pulsar2.getBrokerService().getTopic(topicName, false).join().get();
        Awaitility.await().untilAsserted(() -> {
            assertTrue(topic2.getReplicatedSubscriptionController().isPresent(),
                    "Replicated subscription controller should created");
        });
        for (int i = messagesCount / 2; i < messagesCount; i++) {
            String msg = i + "";
            producer1.send(msg);
            sentMessages.add(msg);
        }

        // Consume half messages and wait the subscription created on the cluster2.
        for (int i = 0; i < messagesCount / 2; i++){
            Message<String> message = consumer1.receive(2, TimeUnit.SECONDS);
            if (message == null) {
                fail("Should not receive null.");
            }
            receivedMessages.add(message.getValue());
            consumer1.acknowledge(message);
        }
        Awaitility.await().untilAsserted(() -> {
            assertNotNull(topic2.getSubscriptions().get(subscriptionName), "Subscription should created");
        });

        // Switch client to cluster2.
        // Since the cluster1 was not crash, all messages will be replicated to the cluster2.
        consumer1.close();
        final PulsarClient client2 = PulsarClient.builder().serviceUrl(url2.toString()).build();
        final Consumer consumer2 = client2.newConsumer(Schema.AUTO_CONSUME()).topic(topicName)
                .subscriptionName(subscriptionName).replicateSubscriptionState(isReplicatedSubscription).subscribe();

        // Verify all messages will be consumed.
        Awaitility.await().untilAsserted(() -> {
            while (true) {
                Message message = consumer2.receive(2, TimeUnit.SECONDS);
                if (message != null) {
                    receivedMessages.add(message.getValue().toString());
                    consumer2.acknowledge(message);
                } else {
                    break;
                }
            }
            assertEquals(receivedMessages.size(), sentMessages.size());
        });

        consumer2.close();
        producer1.close();
        client1.close();
        client2.close();
    }

    /**
     * If there's no traffic, the snapshot creation should stop and then resume when traffic comes back
     */
    @Test
    public void testReplicationSnapshotStopWhenNoTraffic() throws Exception {
        String namespace = BrokerTestUtil.newUniqueName("pulsar/replicatedsubscription");
        String topicName = "persistent://" + namespace + "/mytopic";
        String subscriptionName = "cluster-subscription";

        admin1.namespaces().createNamespace(namespace);
        admin1.namespaces().setNamespaceReplicationClusters(namespace, Sets.newHashSet("r1", "r2"));

        @Cleanup
        PulsarClient client1 = PulsarClient.builder()
                .serviceUrl(url1.toString())
                .statsInterval(0, TimeUnit.SECONDS)
                .build();

        // create subscription in r1
        createReplicatedSubscription(client1, topicName, subscriptionName, true);

        // Validate that no snapshots are created before messages are published
        Thread.sleep(2 * config1.getReplicatedSubscriptionsSnapshotFrequencyMillis());
        PersistentTopic t1 = (PersistentTopic) pulsar1.getBrokerService()
                .getTopic(topicName, false).get().get();
        ReplicatedSubscriptionsController rsc1 = t1.getReplicatedSubscriptionController().get();
        // no snapshot should have been created before any messages are published
        assertTrue(rsc1.getLastCompletedSnapshotId().isEmpty());

        @Cleanup
        PulsarClient client2 = PulsarClient.builder()
                .serviceUrl(url2.toString())
                .statsInterval(0, TimeUnit.SECONDS)
                .build();

        Set<String> sentMessages = new LinkedHashSet<>();

        // send messages in r1
        {
            @Cleanup
            Producer<String> producer = client1.newProducer(Schema.STRING)
                    .topic(topicName)
                    .create();
            for (int i = 0; i < 10; i++) {
                producer.send("hello-" + i);
            }
        }

        // Wait for last snapshots to be created
        Thread.sleep(2 * config1.getReplicatedSubscriptionsSnapshotFrequencyMillis());

        // In R1
        Position p1 = t1.getLastPosition();
        String snapshot1 = rsc1.getLastCompletedSnapshotId().get();

        // In R2

        PersistentTopic t2 = (PersistentTopic) pulsar1.getBrokerService()
                .getTopic(topicName, false).get().get();
        ReplicatedSubscriptionsController rsc2 = t2.getReplicatedSubscriptionController().get();
        Position p2 = t2.getLastPosition();
        String snapshot2 = rsc2.getLastCompletedSnapshotId().get();

        // There shouldn't be anymore snapshots
        Thread.sleep(2 * config1.getReplicatedSubscriptionsSnapshotFrequencyMillis());
        assertEquals(t1.getLastPosition(), p1);
        assertEquals(rsc1.getLastCompletedSnapshotId().get(), snapshot1);

        assertEquals(t2.getLastPosition(), p2);
        assertEquals(rsc2.getLastCompletedSnapshotId().get(), snapshot2);


        @Cleanup
        Producer<String> producer2 = client2.newProducer(Schema.STRING)
                .topic(topicName)
                .create();
        for (int i = 0; i < 10; i++) {
            producer2.send("hello-" + i);
        }

        Thread.sleep(2 * config1.getReplicatedSubscriptionsSnapshotFrequencyMillis());

        // Now we should have one or more snapshots
        assertNotEquals(t1.getLastPosition(), p1);
        assertNotEquals(rsc1.getLastCompletedSnapshotId().get(), snapshot1);

        assertNotEquals(t2.getLastPosition(), p2);
        assertNotEquals(rsc2.getLastCompletedSnapshotId().get(), snapshot2);
    }

    @Test(timeOut = 30000)
    public void testReplicatedSubscriptionRestApi1() throws Exception {
        final String namespace = BrokerTestUtil.newUniqueName("pulsar/replicatedsubscription");
        final String topicName = "persistent://" + namespace + "/topic-rest-api1";
        final String subName = "sub";
        // Subscription replication produces duplicates, https://github.com/apache/pulsar/issues/10054
        // TODO: duplications shouldn't be allowed, change to "false" when fixing the issue
        final boolean allowDuplicates = true;

        admin1.namespaces().createNamespace(namespace);
        admin1.namespaces().setNamespaceReplicationClusters(namespace, Sets.newHashSet("r1", "r2"));

        @Cleanup
        final PulsarClient client1 = PulsarClient.builder().serviceUrl(url1.toString())
                .statsInterval(0, TimeUnit.SECONDS).build();

        // Create subscription in r1
        createReplicatedSubscription(client1, topicName, subName, true);

        @Cleanup
        final PulsarClient client2 = PulsarClient.builder().serviceUrl(url2.toString())
                .statsInterval(0, TimeUnit.SECONDS).build();

        // Create subscription in r2
        createReplicatedSubscription(client2, topicName, subName, true);

        TopicStats stats = admin1.topics().getStats(topicName);
        assertTrue(stats.getSubscriptions().get(subName).isReplicated());

        // Disable replicated subscription in r1
        admin1.topics().setReplicatedSubscriptionStatus(topicName, subName, false);
        stats = admin1.topics().getStats(topicName);
        assertFalse(stats.getSubscriptions().get(subName).isReplicated());
        stats = admin2.topics().getStats(topicName);
        assertTrue(stats.getSubscriptions().get(subName).isReplicated());

        // Disable replicated subscription in r2
        admin2.topics().setReplicatedSubscriptionStatus(topicName, subName, false);
        stats = admin2.topics().getStats(topicName);
        assertFalse(stats.getSubscriptions().get(subName).isReplicated());

        // Unload topic in r1
        admin1.topics().unload(topicName);
        Awaitility.await().untilAsserted(() -> {
            TopicStats stats2 = admin1.topics().getStats(topicName);
            assertFalse(stats2.getSubscriptions().get(subName).isReplicated());
        });

        // Make sure the replicated subscription is actually disabled
        final int numMessages = 20;
        final Set<String> sentMessages = new LinkedHashSet<>();
        final Set<String> receivedMessages = new LinkedHashSet<>();

        Producer<byte[]> producer = client1.newProducer().topic(topicName).enableBatching(false).create();
        sentMessages.clear();
        publishMessages(producer, 0, numMessages, sentMessages);
        producer.close();

        Consumer<byte[]> consumer1 = client1.newConsumer().topic(topicName).subscriptionName(subName).subscribe();
        receivedMessages.clear();
        readMessages(consumer1, receivedMessages, numMessages, false);
        assertEquals(receivedMessages, sentMessages);
        consumer1.close();

        Consumer<byte[]> consumer2 = client2.newConsumer().topic(topicName).subscriptionName(subName).subscribe();
        receivedMessages.clear();
        readMessages(consumer2, receivedMessages, numMessages, false);
        assertEquals(receivedMessages, sentMessages);
        consumer2.close();

        // Enable replicated subscription in r1
        admin1.topics().setReplicatedSubscriptionStatus(topicName, subName, true);
        stats = admin1.topics().getStats(topicName);
        assertTrue(stats.getSubscriptions().get(subName).isReplicated());
        stats = admin2.topics().getStats(topicName);
        assertFalse(stats.getSubscriptions().get(subName).isReplicated());

        // Enable replicated subscription in r2
        admin2.topics().setReplicatedSubscriptionStatus(topicName, subName, true);
        stats = admin2.topics().getStats(topicName);
        assertTrue(stats.getSubscriptions().get(subName).isReplicated());

        // Make sure the replicated subscription is actually enabled
        sentMessages.clear();
        receivedMessages.clear();

        producer = client1.newProducer().topic(topicName).enableBatching(false).create();
        publishMessages(producer, 0, numMessages / 2, sentMessages);
        producer.close();
        Thread.sleep(2 * config1.getReplicatedSubscriptionsSnapshotFrequencyMillis());

        consumer1 = client1.newConsumer().topic(topicName).subscriptionName(subName).subscribe();
        final int numReceivedMessages1 = readMessages(consumer1, receivedMessages, numMessages / 2, allowDuplicates);
        consumer1.close();

        producer = client1.newProducer().topic(topicName).enableBatching(false).create();
        publishMessages(producer, numMessages / 2, numMessages / 2, sentMessages);
        producer.close();
        Thread.sleep(2 * config1.getReplicatedSubscriptionsSnapshotFrequencyMillis());

        consumer2 = client2.newConsumer().topic(topicName).subscriptionName(subName).subscribe();
        final int numReceivedMessages2 = readMessages(consumer2, receivedMessages, -1, allowDuplicates);
        consumer2.close();

        assertEquals(receivedMessages, sentMessages);
        assertTrue(numReceivedMessages1 < numMessages,
                String.format("numReceivedMessages1 (%d) should be less than %d", numReceivedMessages1, numMessages));
        assertTrue(numReceivedMessages2 < numMessages,
                String.format("numReceivedMessages2 (%d) should be less than %d", numReceivedMessages2, numMessages));
    }

    @Test
    public void testGetReplicatedSubscriptionStatus() throws Exception {
        final String namespace = BrokerTestUtil.newUniqueName("pulsar/replicatedsubscription");
        final String topicName1 = "persistent://" + namespace + "/tp-no-part";
        final String topicName2 = "persistent://" + namespace + "/tp-with-part";
        final String subName1 = "sub1";
        final String subName2 = "sub2";

        admin1.namespaces().createNamespace(namespace);
        admin1.topics().createNonPartitionedTopic(topicName1);
        admin1.topics().createPartitionedTopic(topicName2, 3);

        @Cleanup final PulsarClient client = PulsarClient.builder().serviceUrl(url1.toString())
                .statsInterval(0, TimeUnit.SECONDS).build();

        // Create subscription on non-partitioned topic
        createReplicatedSubscription(client, topicName1, subName1, true);
        Awaitility.await().untilAsserted(() -> {
            Map<String, Boolean> status = admin1.topics().getReplicatedSubscriptionStatus(topicName1, subName1);
            assertTrue(status.get(topicName1));
        });
        // Disable replicated subscription on non-partitioned topic
        admin1.topics().setReplicatedSubscriptionStatus(topicName1, subName1, false);
        Awaitility.await().untilAsserted(() -> {
            Map<String, Boolean> status = admin1.topics().getReplicatedSubscriptionStatus(topicName1, subName1);
            assertFalse(status.get(topicName1));
        });

        // Create subscription on partitioned topic
        createReplicatedSubscription(client, topicName2, subName2, true);
        Awaitility.await().untilAsserted(() -> {
            Map<String, Boolean> status = admin1.topics().getReplicatedSubscriptionStatus(topicName2, subName2);
            assertEquals(status.size(), 3);
            for (int i = 0; i < 3; i++) {
                assertTrue(status.get(topicName2 + "-partition-" + i));
            }
        });
        // Disable replicated subscription on partitioned topic
        admin1.topics().setReplicatedSubscriptionStatus(topicName2, subName2, false);
        Awaitility.await().untilAsserted(() -> {
            Map<String, Boolean> status = admin1.topics().getReplicatedSubscriptionStatus(topicName2, subName2);
            assertEquals(status.size(), 3);
            for (int i = 0; i < 3; i++) {
                assertFalse(status.get(topicName2 + "-partition-" + i));
            }
        });
        // Enable replicated subscription on partition-2
        admin1.topics().setReplicatedSubscriptionStatus(topicName2 + "-partition-2", subName2, true);
        Awaitility.await().untilAsserted(() -> {
            Map<String, Boolean> status = admin1.topics().getReplicatedSubscriptionStatus(topicName2, subName2);
            assertEquals(status.size(), 3);
            for (int i = 0; i < 3; i++) {
                if (i == 2) {
                    assertTrue(status.get(topicName2 + "-partition-" + i));
                } else {
                    assertFalse(status.get(topicName2 + "-partition-" + i));
                }
            }
        });
    }

    @Test(timeOut = 30000)
    public void testReplicatedSubscriptionRestApi2() throws Exception {
        final String namespace = BrokerTestUtil.newUniqueName("pulsar/replicatedsubscription");
        final String topicName = "persistent://" + namespace + "/topic-rest-api2";
        final String subName = "sub";
        // Subscription replication produces duplicates, https://github.com/apache/pulsar/issues/10054
        // TODO: duplications shouldn't be allowed, change to "false" when fixing the issue
        final boolean allowDuplicates = true;

        admin1.namespaces().createNamespace(namespace);
        admin1.namespaces().setNamespaceReplicationClusters(namespace, Sets.newHashSet("r1", "r2"));
        admin1.topics().createPartitionedTopic(topicName, 2);

        @Cleanup
        final PulsarClient client1 = PulsarClient.builder().serviceUrl(url1.toString())
                .statsInterval(0, TimeUnit.SECONDS).build();

        // Create subscription in r1
        createReplicatedSubscription(client1, topicName, subName, true);

        @Cleanup
        final PulsarClient client2 = PulsarClient.builder().serviceUrl(url2.toString())
                .statsInterval(0, TimeUnit.SECONDS).build();

        // Create subscription in r2
        createReplicatedSubscription(client2, topicName, subName, true);

        PartitionedTopicStats partitionedStats = admin1.topics().getPartitionedStats(topicName, true);
        for (TopicStats stats : partitionedStats.getPartitions().values()) {
            assertTrue(stats.getSubscriptions().get(subName).isReplicated());
        }

        // Disable replicated subscription in r1
        admin1.topics().setReplicatedSubscriptionStatus(topicName, subName, false);
        partitionedStats = admin1.topics().getPartitionedStats(topicName, true);
        for (TopicStats stats : partitionedStats.getPartitions().values()) {
            assertFalse(stats.getSubscriptions().get(subName).isReplicated());
        }

        // Disable replicated subscription in r2
        admin2.topics().setReplicatedSubscriptionStatus(topicName, subName, false);
        partitionedStats = admin2.topics().getPartitionedStats(topicName, true);
        for (TopicStats stats : partitionedStats.getPartitions().values()) {
            assertFalse(stats.getSubscriptions().get(subName).isReplicated());
        }

        // Make sure the replicated subscription is actually disabled
        final int numMessages = 20;
        final Set<String> sentMessages = new LinkedHashSet<>();
        final Set<String> receivedMessages = new LinkedHashSet<>();

        Producer<byte[]> producer = client1.newProducer().topic(topicName).enableBatching(false)
                .messageRoutingMode(MessageRoutingMode.SinglePartition).create();
        sentMessages.clear();
        publishMessages(producer, 0, numMessages, sentMessages);
        producer.close();

        Consumer<byte[]> consumer1 = client1.newConsumer().topic(topicName).subscriptionName(subName).subscribe();
        receivedMessages.clear();
        readMessages(consumer1, receivedMessages, numMessages, false);
        assertEquals(receivedMessages, sentMessages);
        consumer1.close();

        Consumer<byte[]> consumer2 = client2.newConsumer().topic(topicName).subscriptionName(subName).subscribe();
        receivedMessages.clear();
        readMessages(consumer2, receivedMessages, numMessages, false);
        assertEquals(receivedMessages, sentMessages);
        consumer2.close();

        // Enable replicated subscription in r1
        admin1.topics().setReplicatedSubscriptionStatus(topicName, subName, true);
        partitionedStats = admin1.topics().getPartitionedStats(topicName, true);
        for (TopicStats stats : partitionedStats.getPartitions().values()) {
            assertTrue(stats.getSubscriptions().get(subName).isReplicated());
        }

        // Enable replicated subscription in r2
        admin2.topics().setReplicatedSubscriptionStatus(topicName, subName, true);
        partitionedStats = admin2.topics().getPartitionedStats(topicName, true);
        for (TopicStats stats : partitionedStats.getPartitions().values()) {
            assertTrue(stats.getSubscriptions().get(subName).isReplicated());
        }

        // Make sure the replicated subscription is actually enabled
        sentMessages.clear();
        receivedMessages.clear();

        producer = client1.newProducer().topic(topicName).enableBatching(false)
                .messageRoutingMode(MessageRoutingMode.SinglePartition).create();
        publishMessages(producer, 0, numMessages / 2, sentMessages);
        producer.close();
        Thread.sleep(2 * config1.getReplicatedSubscriptionsSnapshotFrequencyMillis());

        consumer1 = client1.newConsumer().topic(topicName).subscriptionName(subName).subscribe();
        final int numReceivedMessages1 = readMessages(consumer1, receivedMessages, numMessages / 2, allowDuplicates);
        consumer1.close();

        producer = client1.newProducer().topic(topicName).enableBatching(false)
                .messageRoutingMode(MessageRoutingMode.SinglePartition).create();
        publishMessages(producer, numMessages / 2, numMessages / 2, sentMessages);
        producer.close();
        Thread.sleep(2 * config1.getReplicatedSubscriptionsSnapshotFrequencyMillis());

        consumer2 = client2.newConsumer().topic(topicName).subscriptionName(subName).subscribe();
        final int numReceivedMessages2 = readMessages(consumer2, receivedMessages, -1, allowDuplicates);
        consumer2.close();

        assertEquals(receivedMessages, sentMessages);
        assertTrue(numReceivedMessages1 < numMessages,
                String.format("numReceivedMessages1 (%d) should be less than %d", numReceivedMessages1, numMessages));
        assertTrue(numReceivedMessages2 < numMessages,
                String.format("numReceivedMessages2 (%d) should be less than %d", numReceivedMessages2, numMessages));
    }

    @Test(timeOut = 30000)
    public void testReplicatedSubscriptionRestApi3() throws Exception {
        final String namespace = BrokerTestUtil.newUniqueName("geo/replicatedsubscription");
        final String topicName = "persistent://" + namespace + "/topic-rest-api3";
        final String subName = "sub";
        admin4.tenants().createTenant("geo",
                new TenantInfoImpl(Sets.newHashSet("appid1", "appid4"), Sets.newHashSet(cluster1, cluster4)));
        admin4.namespaces().createNamespace(namespace);
        admin4.namespaces().setNamespaceReplicationClusters(namespace, Sets.newHashSet(cluster1, cluster4));
        admin4.topics().createPartitionedTopic(topicName, 2);

        @Cleanup
        final PulsarClient client4 = PulsarClient.builder().serviceUrl(url4.toString())
                .statsInterval(0, TimeUnit.SECONDS).build();

        Consumer<byte[]> consumer4 = client4.newConsumer().topic(topicName).subscriptionName(subName).subscribe();
        Assert.expectThrows(PulsarAdminException.class, () ->
                admin4.topics().setReplicatedSubscriptionStatus(topicName, subName, true));
        consumer4.close();
    }

    /**
     * Tests replicated subscriptions when replicator producer is closed
     */
    @Test
    public void testReplicatedSubscriptionWhenReplicatorProducerIsClosed() throws Exception {
        String namespace = BrokerTestUtil.newUniqueName("pulsar/replicatedsubscription");
        String topicName = "persistent://" + namespace + "/when-replicator-producer-is-closed";
        String subscriptionName = "sub";

        admin1.namespaces().createNamespace(namespace);
        admin1.namespaces().setNamespaceReplicationClusters(namespace, Sets.newHashSet("r1", "r2"));

        @Cleanup
        PulsarClient client1 = PulsarClient.builder().serviceUrl(url1.toString())
                .statsInterval(0, TimeUnit.SECONDS)
                .build();

        {
            // create consumer in r1
            @Cleanup
            Consumer<byte[]> consumer = client1.newConsumer()
                    .topic(topicName)
                    .subscriptionName(subscriptionName)
                    .replicateSubscriptionState(true)
                    .subscribe();

            // send one message to trigger replication
            @Cleanup
            Producer<byte[]> producer = client1.newProducer().topic(topicName)
                    .enableBatching(false)
                    .messageRoutingMode(MessageRoutingMode.SinglePartition)
                    .create();
            producer.send("message".getBytes(StandardCharsets.UTF_8));

            assertEquals(readMessages(consumer, new HashSet<>(), 1, false), 1);

            // waiting to replicate topic/subscription to r1->r2
            Awaitility.await().until(() -> pulsar2.getBrokerService().getTopics().containsKey(topicName));
            final PersistentTopic topic2 = (PersistentTopic) pulsar2.getBrokerService().getTopic(topicName, false).join().get();
            Awaitility.await().untilAsserted(() -> assertTrue(topic2.getReplicators().get("r1").isConnected()));
            Awaitility.await().untilAsserted(() -> assertNotNull(topic2.getSubscription(subscriptionName)));
        }

        // unsubscribe replicated subscription in r2
        admin2.topics().deleteSubscription(topicName, subscriptionName);
        final PersistentTopic topic2 = (PersistentTopic) pulsar2.getBrokerService().getTopic(topicName, false).join().get();
        assertNull(topic2.getSubscription(subscriptionName));

        // close replicator producer in r2
        final Method closeReplProducersIfNoBacklog = PersistentTopic.class.getDeclaredMethod("closeReplProducersIfNoBacklog", null);
        closeReplProducersIfNoBacklog.setAccessible(true);
        ((CompletableFuture<Void>) closeReplProducersIfNoBacklog.invoke(topic2, null)).join();
        assertFalse(topic2.getReplicators().get("r1").isConnected());

        // send messages in r1
        int numMessages = 6;
        {
            @Cleanup
            Producer<byte[]> producer = client1.newProducer().topic(topicName)
                    .enableBatching(false)
                    .messageRoutingMode(MessageRoutingMode.SinglePartition)
                    .create();
            for (int i = 0; i < numMessages; i++) {
                String body = "message" + i;
                producer.send(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        // consume 6 messages in r1
        Set<String> receivedMessages = new LinkedHashSet<>();
        @Cleanup
        Consumer<byte[]> consumer1 = client1.newConsumer()
                .topic(topicName)
                .subscriptionName(subscriptionName)
                .replicateSubscriptionState(true)
                .subscribe();
        assertEquals(readMessages(consumer1, receivedMessages, numMessages, false), numMessages);

        // wait for subscription to be replicated
        Awaitility.await().untilAsserted(() -> assertTrue(topic2.getReplicators().get("r1").isConnected()));
        Awaitility.await().untilAsserted(() -> assertNotNull(topic2.getSubscription(subscriptionName)));
    }

    void publishMessages(Producer<byte[]> producer, int startIndex, int numMessages, Set<String> sentMessages)
            throws PulsarClientException {
        for (int i = startIndex; i < startIndex + numMessages; i++) {
            final String msg = "msg" + i;
            producer.send(msg.getBytes(StandardCharsets.UTF_8));
            sentMessages.add(msg);
        }
    }

    int readMessages(Consumer<byte[]> consumer, Set<String> messages, int maxMessages, boolean allowDuplicates)
            throws PulsarClientException {
        int count = 0;
        while (count < maxMessages || maxMessages == -1) {
            Message<byte[]> message = consumer.receive(2, TimeUnit.SECONDS);
            if (message != null) {
                count++;
                String body = new String(message.getValue(), StandardCharsets.UTF_8);
                if (!allowDuplicates) {
                    assertFalse(messages.contains(body), "Duplicate message '" + body + "' detected.");
                }
                messages.add(body);
                consumer.acknowledge(message);
            } else {
                break;
            }
        }
        return count;
    }

    void createReplicatedSubscription(PulsarClient pulsarClient, String topicName, String subscriptionName,
                                      boolean replicateSubscriptionState)
            throws PulsarClientException {
        pulsarClient.newConsumer().topic(topicName)
                .subscriptionName(subscriptionName)
                .replicateSubscriptionState(replicateSubscriptionState)
                .subscribe()
                .close();
    }

}
