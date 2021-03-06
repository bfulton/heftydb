/*
 * Copyright (c) 2014. Jordan Williams
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jordanwilliams.heftydb.table.file;

import com.jordanwilliams.heftydb.data.Key;
import com.jordanwilliams.heftydb.io.AppendChannelFile;
import com.jordanwilliams.heftydb.io.AppendFile;
import com.jordanwilliams.heftydb.offheap.BloomFilter;
import com.jordanwilliams.heftydb.state.Paths;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Writes a BloomFilter out to a file.
 */
public class TableBloomFilterWriter {

    private static final double FALSE_POSITIVE_PROBABILITY = 0.01;

    private final BloomFilter.Builder filterBuilder;
    private final AppendFile filterFile;

    private TableBloomFilterWriter(AppendFile filterFile, long approxRecordCount) {
        this.filterBuilder = new BloomFilter.Builder(approxRecordCount, FALSE_POSITIVE_PROBABILITY);
        this.filterFile = filterFile;
    }

    public void write(Key key) throws IOException {
        filterBuilder.put(key);
    }

    public void finish() throws IOException {
        BloomFilter filter = filterBuilder.build();
        ByteBuffer filterBuffer = filter.memory().directBuffer();
        filterFile.append(filterBuffer);
        filterFile.close();
        filter.memory().release();
    }

    public static TableBloomFilterWriter open(long tableId, Paths paths, long approxRecordCount) throws IOException {
        AppendFile filterFile = AppendChannelFile.open(paths.filterPath(tableId));
        return new TableBloomFilterWriter(filterFile, approxRecordCount);
    }
}
