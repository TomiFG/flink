/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.file.sink;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.serialization.Encoder;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.core.fs.Path;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink;
import org.apache.flink.streaming.api.functions.sink.filesystem.bucketassigners.SimpleVersionedStringSerializer;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.OnCheckpointRollingPolicy;
import org.apache.flink.streaming.api.functions.source.SourceFunction;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Collections;

/**
 * Test program for the {@link StreamingFileSink} and {@link FileSink}.
 *
 * <p>Uses a source that steadily emits a deterministic set of records over 60 seconds, after which
 * it idles and waits for job cancellation. Every record has a unique index that is written to the
 * file.
 *
 * <p>The sink rolls on each checkpoint, with each part file containing a sequence of integers.
 * Adding all committed part files together, and numerically sorting the contents, should result in
 * a complete sequence from 0 (inclusive) to 60000 (exclusive).
 */
public enum FileSinkProgram {
    ;

    public static void main(final String[] args) throws Exception {
        final ParameterTool params = ParameterTool.fromArgs(args);
        final String outputPath = params.getRequired("outputPath");
        final String sinkToTest = params.getRequired("sinkToTest");

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        env.setParallelism(4);
        env.enableCheckpointing(5000L);
        env.setRestartStrategy(
                RestartStrategies.fixedDelayRestart(Integer.MAX_VALUE, Duration.ofSeconds(10L)));

        // generate data, shuffle, sink
        DataStream<Tuple2<Integer, Integer>> source = env.addSource(new Generator(10, 10, 60));

        if (sinkToTest.equalsIgnoreCase("StreamingFileSink")) {
            final StreamingFileSink<Tuple2<Integer, Integer>> sink =
                    StreamingFileSink.forRowFormat(
                                    new Path(outputPath),
                                    (Encoder<Tuple2<Integer, Integer>>)
                                            (element, stream) -> {
                                                PrintStream out = new PrintStream(stream);
                                                out.println(element.f1);
                                            })
                            .withBucketAssigner(new KeyBucketAssigner())
                            .withRollingPolicy(OnCheckpointRollingPolicy.build())
                            .build();

            source.keyBy(0).addSink(sink);
        } else if (sinkToTest.equalsIgnoreCase("FileSink")) {
            FileSink<Tuple2<Integer, Integer>> sink =
                    FileSink.forRowFormat(
                                    new Path(outputPath),
                                    (Encoder<Tuple2<Integer, Integer>>)
                                            (element, stream) -> {
                                                PrintStream out = new PrintStream(stream);
                                                out.println(element.f1);
                                            })
                            .withBucketAssigner(new KeyBucketAssigner())
                            .withRollingPolicy(OnCheckpointRollingPolicy.build())
                            .build();
            source.keyBy(0).sinkTo(sink);
        } else {
            throw new UnsupportedOperationException("Unsupported sink type: " + sinkToTest);
        }

        env.execute("StreamingFileSinkProgram");
    }

    /** Use first field for buckets. */
    public static final class KeyBucketAssigner
            implements BucketAssigner<Tuple2<Integer, Integer>, String> {

        private static final long serialVersionUID = 987325769970523326L;

        @Override
        public String getBucketId(final Tuple2<Integer, Integer> element, final Context context) {
            return String.valueOf(element.f0);
        }

        @Override
        public SimpleVersionedSerializer<String> getSerializer() {
            return SimpleVersionedStringSerializer.INSTANCE;
        }
    }

    /**
     * Data-generating source function.
     *
     * @deprecated This class is based on the {@link
     *     org.apache.flink.streaming.api.functions.source.SourceFunction} API, which is due to be
     *     removed. Use the new {@link org.apache.flink.api.connector.source.Source} API instead.
     */
    @Deprecated
    public static final class Generator
            implements SourceFunction<Tuple2<Integer, Integer>>, CheckpointedFunction {

        private static final long serialVersionUID = -2819385275681175792L;

        private final int numKeys;
        private final int idlenessMs;
        private final int recordsToEmit;

        private volatile int numRecordsEmitted = 0;
        private volatile boolean canceled = false;

        private ListState<Integer> state = null;

        Generator(final int numKeys, final int idlenessMs, final int durationSeconds) {
            this.numKeys = numKeys;
            this.idlenessMs = idlenessMs;

            this.recordsToEmit = ((durationSeconds * 1000) / idlenessMs) * numKeys;
        }

        @Override
        public void run(final SourceContext<Tuple2<Integer, Integer>> ctx) throws Exception {
            while (numRecordsEmitted < recordsToEmit) {
                synchronized (ctx.getCheckpointLock()) {
                    for (int i = 0; i < numKeys; i++) {
                        ctx.collect(Tuple2.of(i, numRecordsEmitted));
                        numRecordsEmitted++;
                    }
                }
                Thread.sleep(idlenessMs);
            }

            while (!canceled) {
                Thread.sleep(50);
            }
        }

        @Override
        public void cancel() {
            canceled = true;
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            state =
                    context.getOperatorStateStore()
                            .getListState(
                                    new ListStateDescriptor<Integer>(
                                            "state", IntSerializer.INSTANCE));

            for (Integer i : state.get()) {
                numRecordsEmitted += i;
            }
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            state.update(Collections.singletonList(numRecordsEmitted));
        }
    }
}
