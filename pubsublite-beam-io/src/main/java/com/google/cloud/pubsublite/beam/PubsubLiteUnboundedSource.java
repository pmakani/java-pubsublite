/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pubsublite.beam;

import static com.google.cloud.pubsublite.internal.Preconditions.checkState;

import com.google.cloud.pubsublite.Offset;
import com.google.cloud.pubsublite.Partition;
import com.google.cloud.pubsublite.SequencedMessage;
import com.google.cloud.pubsublite.internal.wire.Committer;
import com.google.cloud.pubsublite.internal.wire.SubscriberFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import io.grpc.StatusException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.io.UnboundedSource;
import org.apache.beam.sdk.options.PipelineOptions;

class PubsubLiteUnboundedSource extends UnboundedSource<SequencedMessage, OffsetCheckpointMark> {
  private final SubscriberOptions subscriberOptions;

  PubsubLiteUnboundedSource(SubscriberOptions options) {
    this.subscriberOptions = options;
  }

  @Override
  public List<? extends UnboundedSource<SequencedMessage, OffsetCheckpointMark>> split(
      int desiredNumSplits, PipelineOptions options) {
    ImmutableList.Builder<PubsubLiteUnboundedSource> builder = ImmutableList.builder();
    for (List<Partition> partitionSubset :
        Iterables.partition(subscriberOptions.partitions(), desiredNumSplits)) {
      if (partitionSubset.isEmpty()) continue;
      try {
        builder.add(
            new PubsubLiteUnboundedSource(
                subscriberOptions
                    .toBuilder()
                    .setPartitions(ImmutableSet.copyOf(partitionSubset))
                    .build()));
      } catch (StatusException e) {
        throw e.getStatus().asRuntimeException();
      }
    }
    return builder.build();
  }

  @Override
  public UnboundedReader<SequencedMessage> createReader(
      PipelineOptions options, @Nullable OffsetCheckpointMark checkpointMark) throws IOException {
    try {
      ImmutableMap<Partition, SubscriberFactory> subscriberFactories =
          subscriberOptions.getSubscriberFactories();
      ImmutableMap<Partition, Committer> committers = subscriberOptions.getCommitters();
      ImmutableMap.Builder<Partition, PubsubLiteUnboundedReader.SubscriberState> statesBuilder =
          ImmutableMap.builder();
      for (Partition partition : subscriberFactories.keySet()) {
        checkState(committers.containsKey(partition));
        PubsubLiteUnboundedReader.SubscriberState state =
            new PubsubLiteUnboundedReader.SubscriberState();
        state.committer = committers.get(partition);
        if (checkpointMark != null && checkpointMark.partitionOffsetMap.containsKey(partition)) {
          Offset checkpointed = checkpointMark.partitionOffsetMap.get(partition);
          state.lastDelivered = Optional.of(checkpointed);
          state.subscriber =
              new BufferingPullSubscriber(
                  subscriberFactories.get(partition),
                  subscriberOptions.flowControlSettings(),
                  checkpointed);
        } else {
          state.subscriber =
              new BufferingPullSubscriber(
                  subscriberFactories.get(partition), subscriberOptions.flowControlSettings());
        }
        statesBuilder.put(partition, state);
      }
      return new PubsubLiteUnboundedReader(this, statesBuilder.build());
    } catch (StatusException e) {
      throw new IOException(e);
    }
  }

  @Override
  public Coder<OffsetCheckpointMark> getCheckpointMarkCoder() {
    return OffsetCheckpointMark.getCoder();
  }

  @Override
  public Coder<SequencedMessage> getOutputCoder() {
    return new SequencedMessageCoder();
  }
}
