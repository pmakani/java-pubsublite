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

package com.google.cloud.pubsublite.internal;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** A ChannelCache creates and stores default channels for use with api methods. */
public class ChannelCache {
  private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();

  public ChannelCache() {
    Runtime.getRuntime().addShutdownHook(new Thread(this::onShutdown));
  }

  private void onShutdown() {
    channels.forEachValue(
        channels.size(),
        channel -> {
          try {
            channel.shutdownNow().awaitTermination(60, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        });
  }

  public Channel get(String target) {
    return channels.computeIfAbsent(target, this::newChannel);
  }

  private ManagedChannel newChannel(String target) {
    return ManagedChannelBuilder.forTarget(target).build();
  }
}
