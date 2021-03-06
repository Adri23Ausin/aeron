/*
 * Copyright 2014-2018 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ReplayMerge;
import io.aeron.archive.status.RecordingPos;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.test.DataCollector;
import io.aeron.test.MediaDriverTestWatcher;
import io.aeron.test.TestMediaDriver;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.LangUtil;
import org.agrona.SystemUtil;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.nio.file.Paths;
import java.util.function.Supplier;

import static io.aeron.archive.ArchiveSystemTests.*;
import static io.aeron.archive.codecs.SourceLocation.REMOTE;
import static org.junit.jupiter.api.Assertions.*;

public class ReplayMergeTest
{
    private static final String MESSAGE_PREFIX = "Message-Prefix-";
    private static final int MIN_MESSAGES_PER_TERM =
        TERM_LENGTH / (MESSAGE_PREFIX.length() + DataHeaderFlyweight.HEADER_LENGTH);

    private static final int PUBLICATION_TAG = 2;
    private static final int STREAM_ID = 1033;

    private static final String CONTROL_ENDPOINT = "localhost:23265";
    private static final String RECORDING_ENDPOINT = "localhost:23266";
    private static final String LIVE_ENDPOINT = "localhost:23267";
    private static final String REPLAY_ENDPOINT = "localhost:0";
    private static final long GROUP_TAG = 99901L;

    private static final int INITIAL_MESSAGE_COUNT = MIN_MESSAGES_PER_TERM * 3;
    private static final int SUBSEQUENT_MESSAGE_COUNT = MIN_MESSAGES_PER_TERM * 3;
    private static final int TOTAL_MESSAGE_COUNT = INITIAL_MESSAGE_COUNT + SUBSEQUENT_MESSAGE_COUNT;

    private final ChannelUriStringBuilder publicationChannel = new ChannelUriStringBuilder()
        .media(CommonContext.UDP_MEDIA)
        .tags("1," + PUBLICATION_TAG)
        .controlEndpoint(CONTROL_ENDPOINT)
        .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)
        .termLength(TERM_LENGTH)
        .taggedFlowControl(GROUP_TAG, 1, "5s");

    private final ChannelUriStringBuilder recordingChannel = new ChannelUriStringBuilder()
        .media(CommonContext.UDP_MEDIA)
        .endpoint(RECORDING_ENDPOINT)
        .controlEndpoint(CONTROL_ENDPOINT)
        .groupTag(GROUP_TAG);

    private final ChannelUriStringBuilder subscriptionChannel = new ChannelUriStringBuilder()
        .media(CommonContext.UDP_MEDIA)
        .controlMode(CommonContext.MDC_CONTROL_MODE_MANUAL);

    private final ChannelUriStringBuilder liveDestination = new ChannelUriStringBuilder()
        .media(CommonContext.UDP_MEDIA)
        .endpoint(LIVE_ENDPOINT)
        .controlEndpoint(CONTROL_ENDPOINT);

    private final ChannelUriStringBuilder replayDestination = new ChannelUriStringBuilder()
        .media(CommonContext.UDP_MEDIA)
        .endpoint(REPLAY_ENDPOINT);

    private final ChannelUriStringBuilder replayChannel = new ChannelUriStringBuilder()
        .media(CommonContext.UDP_MEDIA)
        .isSessionIdTagged(true)
        .sessionId(PUBLICATION_TAG);

    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();
    private final MutableLong receivedMessageCount = new MutableLong();
    private final MutableLong receivedPosition = new MutableLong();
    private final MediaDriver.Context mediaDriverContext = new MediaDriver.Context();
    private final DataCollector dataCollector = new DataCollector();

    private TestMediaDriver driver;
    private Archive archive;
    private Aeron aeron;
    private AeronArchive aeronArchive;
    private int messagesPublished = 0;

    private final FragmentHandler fragmentHandler = new FragmentAssembler(
        (buffer, offset, length, header) ->
        {
            final String expected = MESSAGE_PREFIX + receivedMessageCount.get();
            final String actual = buffer.getStringWithoutLengthAscii(offset, length);

            assertEquals(expected, actual);
            receivedMessageCount.incrementAndGet();
            receivedPosition.set(header.position());
        });

    @RegisterExtension
    public final MediaDriverTestWatcher testWatcher = new MediaDriverTestWatcher();

    @BeforeEach
    public void before()
    {
        final File archiveDir = new File(SystemUtil.tmpDirName(), "archive");

        driver = TestMediaDriver.launch(
            mediaDriverContext
                .termBufferSparseFile(true)
                .publicationTermBufferLength(TERM_LENGTH)
                .threadingMode(ThreadingMode.SHARED)
                .errorHandler(Tests::onError)
                .spiesSimulateConnection(false)
                .dirDeleteOnStart(true),
            testWatcher);

        archive = Archive.launch(
            new Archive.Context()
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .aeronDirectoryName(mediaDriverContext.aeronDirectoryName())
                .errorHandler(Tests::onError)
                .archiveDir(archiveDir)
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .deleteArchiveOnStart(true));

        aeron = Aeron.connect(
            new Aeron.Context()
                .aeronDirectoryName(mediaDriverContext.aeronDirectoryName()));

        aeronArchive = AeronArchive.connect(
            new AeronArchive.Context()
                .errorHandler(Tests::onError)
                .aeron(aeron));

        dataCollector.add(Paths.get(mediaDriverContext.aeronDirectoryName()));
        dataCollector.add(archiveDir.toPath());
    }

    @AfterEach
    public void after()
    {
        if (receivedMessageCount.get() != MIN_MESSAGES_PER_TERM * 6)
        {
            System.out.println(
                "received " + receivedMessageCount.get() + ", sent " + messagesPublished +
                ", total " + (MIN_MESSAGES_PER_TERM * 6));
        }

        CloseHelper.closeAll(aeronArchive, aeron, archive, driver);

        archive.context().deleteDirectory();
        driver.context().deleteDirectory();
    }

    @Test
    @Timeout(30)
    public void shouldMergeFromReplayToLive(final TestInfo testInfo)
    {
        try (Publication publication = aeron.addPublication(publicationChannel.build(), STREAM_ID))
        {
            final int sessionId = publication.sessionId();
            final String recordingChannel = this.recordingChannel.sessionId(sessionId).build();
            final String subscriptionChannel = this.subscriptionChannel.sessionId(sessionId).build();

            aeronArchive.startRecording(recordingChannel, STREAM_ID, REMOTE, true);
            final CountersReader counters = aeron.countersReader();
            final int recordingCounterId = awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, recordingCounterId);

            publishMessages(publication);
            awaitPosition(counters, recordingCounterId, publication.position());

            while (!attemptReplayMerge(recordingId, recordingCounterId, counters, publication, subscriptionChannel))
            {
                Tests.yield();
            }

            assertEquals(TOTAL_MESSAGE_COUNT, receivedMessageCount.get());
            assertEquals(publication.position(), receivedPosition.get());
        }
        catch (final Throwable ex)
        {
            dataCollector.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    private boolean attemptReplayMerge(
        final long recordingId,
        final int recordingCounterId,
        final CountersReader counters,
        final Publication publication,
        final String subscriptionChannel)
    {
        try (Subscription subscription = aeron.addSubscription(subscriptionChannel, STREAM_ID);
            ReplayMerge replayMerge = new ReplayMerge(
                subscription,
                aeronArchive,
                replayChannel.build(),
                replayDestination.build(),
                liveDestination.build(),
                recordingId,
                receivedPosition.get()))
        {
            final MutableLong offerPosition = new MutableLong();
            final Supplier<String> msgOne = () -> String.format("replay did not merge: %s", replayMerge);
            final Supplier<String> msgTwo = () -> String.format(
                "receivedMessageCount=%d < totalMessageCount=%d: replayMerge=%s",
                receivedMessageCount.get(), TOTAL_MESSAGE_COUNT, replayMerge);

            for (int i = messagesPublished; i < TOTAL_MESSAGE_COUNT; i++)
            {
                while ((offerPosition.value = offerMessage(publication, i)) <= 0)
                {
                    if (Publication.BACK_PRESSURED == offerPosition.get())
                    {
                        awaitRecordingPositionChange(
                            replayMerge, counters, recordingCounterId, recordingId, publication);
                        if (0 == replayMerge.poll(fragmentHandler, FRAGMENT_LIMIT) && replayMerge.hasFailed())
                        {
                            return false;
                        }
                    }
                    else if (Publication.NOT_CONNECTED == offerPosition.get())
                    {
                        throw new IllegalStateException("publication is not connected");
                    }
                }

                if (0 == replayMerge.poll(fragmentHandler, FRAGMENT_LIMIT) && replayMerge.hasFailed())
                {
                    return false;
                }
            }

            while (!replayMerge.isMerged())
            {
                if (0 == replayMerge.poll(fragmentHandler, FRAGMENT_LIMIT))
                {
                    if (replayMerge.hasFailed())
                    {
                        return false;
                    }
                    Tests.yieldingWait(msgOne);
                }
            }

            final Image image = replayMerge.image();
            while (receivedMessageCount.get() < TOTAL_MESSAGE_COUNT)
            {
                if (0 == image.poll(fragmentHandler, FRAGMENT_LIMIT))
                {
                    if (image.isClosed())
                    {
                        return false;
                    }
                    Tests.yieldingWait(msgTwo);
                }
            }

            assertTrue(replayMerge.isMerged());
            assertTrue(replayMerge.isLiveAdded());
            assertFalse(replayMerge.hasFailed());
        }

        return true;
    }

    static void awaitRecordingPositionChange(
        final ReplayMerge replayMerge,
        final CountersReader counters,
        final int counterId,
        final long recordingId,
        final Publication publication)
    {
        final long position = publication.position();
        final long initialTimestampNs = System.nanoTime();
        final long currentPosition = counters.getCounterValue(counterId);
        final Supplier<String> msg = () -> String.format(
            "publicationPosition=%d, recordingPosition=%d, timeSinceLastChangeMs=%d, replayMerge=%s",
            position,
            currentPosition,
            (System.nanoTime() - initialTimestampNs) / 1_000_000,
            replayMerge);

        do
        {
            Tests.yieldingWait(msg);

            if (!RecordingPos.isActive(counters, counterId, recordingId))
            {
                throw new IllegalStateException("recording not active: " + counterId);
            }
        }
        while (currentPosition == counters.getCounterValue(counterId) && currentPosition < position);
    }

    private long offerMessage(final Publication publication, final int index)
    {
        final int length = buffer.putStringWithoutLengthAscii(0, MESSAGE_PREFIX + index);
        final long offerResult = publication.offer(buffer, 0, length);

        if (offerResult > 0)
        {
            messagesPublished++;
        }

        return offerResult;
    }

    private void publishMessages(final Publication publication)
    {
        for (int i = 0; i < INITIAL_MESSAGE_COUNT; i++)
        {
            final int length = buffer.putStringWithoutLengthAscii(0, MESSAGE_PREFIX + i);

            while (publication.offer(buffer, 0, length) <= 0)
            {
                Tests.yield();
            }

            messagesPublished++;
        }
    }
}
