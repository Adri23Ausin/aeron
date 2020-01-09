/*
 * Copyright 2014-2020 Real Logic Limited.
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
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.test.TestMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static io.aeron.SystemTest.spyForChannel;
import static java.time.Duration.ofSeconds;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

public class SpySubscriptionTest
{
    private static List<String> channels()
    {
        return asList(
            "aeron:udp?endpoint=localhost:54325",
            "aeron:udp?endpoint=224.20.30.39:54326|interface=localhost"
        );
    }

    private static final int STREAM_ID = 1001;
    private static final int FRAGMENT_COUNT_LIMIT = 10;
    private static final int PAYLOAD_LENGTH = 10;

    private final MutableInteger fragmentCountSpy = new MutableInteger();
    private final FragmentHandler fragmentHandlerSpy = (buffer1, offset, length, header) -> fragmentCountSpy.value++;

    private final MutableInteger fragmentCountSub = new MutableInteger();
    private final FragmentHandler fragmentHandlerSub = (buffer1, offset, length, header) -> fragmentCountSub.value++;

    private final TestMediaDriver driver = TestMediaDriver.launch(new MediaDriver.Context()
        .errorHandler(Throwable::printStackTrace)
        .dirDeleteOnShutdown(true)
        .publicationTermBufferLength(LogBufferDescriptor.TERM_MIN_LENGTH)
        .threadingMode(ThreadingMode.SHARED));

    private final Aeron aeron = Aeron.connect();

    @AfterEach
    public void after()
    {
        CloseHelper.close(aeron);
        CloseHelper.close(driver);
    }

    @ParameterizedTest
    @MethodSource("channels")
    public void shouldReceivePublishedMessage(final String channel)
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            try (Subscription subscription = aeron.addSubscription(channel, STREAM_ID);
                Subscription spy = aeron.addSubscription(spyForChannel(channel), STREAM_ID);
                Publication publication = aeron.addPublication(channel, STREAM_ID))
            {
                final int expectedMessageCount = 4;
                final UnsafeBuffer srcBuffer = new UnsafeBuffer(new byte[PAYLOAD_LENGTH * expectedMessageCount]);

                for (int i = 0; i < expectedMessageCount; i++)
                {
                    srcBuffer.setMemory(i * PAYLOAD_LENGTH, PAYLOAD_LENGTH, (byte)(65 + i));
                }

                for (int i = 0; i < expectedMessageCount; i++)
                {
                    while (publication.offer(srcBuffer, i * PAYLOAD_LENGTH, PAYLOAD_LENGTH) < 0L)
                    {
                        Thread.yield();
                        SystemTest.checkInterruptedStatus();
                    }
                }

                int numFragments = 0;
                int numSpyFragments = 0;
                do
                {
                    SystemTest.checkInterruptedStatus();

                    numFragments += subscription.poll(fragmentHandlerSub, FRAGMENT_COUNT_LIMIT);
                    numSpyFragments += spy.poll(fragmentHandlerSpy, FRAGMENT_COUNT_LIMIT);
                }
                while (numSpyFragments < expectedMessageCount || numFragments < expectedMessageCount);

                assertEquals(expectedMessageCount, fragmentCountSpy.value);
                assertEquals(expectedMessageCount, fragmentCountSub.value);
            }
        });
    }
}
