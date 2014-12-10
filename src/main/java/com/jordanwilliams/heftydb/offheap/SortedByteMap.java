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

package com.jordanwilliams.heftydb.offheap;

import com.jordanwilliams.heftydb.data.Key;
import com.jordanwilliams.heftydb.data.Value;
import com.jordanwilliams.heftydb.util.Sizes;
import com.jordanwilliams.heftydb.util.VarInts;
import sun.misc.Unsafe;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A sorted block of key-value entries optimized for efficient binary search and backed by off-heap memory. A binary
 * search over a SortedByteMap requires no object allocations, and is thus quite fast.
 */
public class SortedByteMap implements Offheap, Iterable<SortedByteMap.Entry> {

    private static final Unsafe unsafe = JVMUnsafe.unsafe;
    private static final int PAGE_SIZE = unsafe.pageSize();

    public static class Entry {

        private final Key key;
        private final Value value;

        public Entry(Key key, Value value) {
            this.key = key;
            this.value = value;
        }

        public Key key() {
            return key;
        }

        public Value value() {
            return value;
        }

        @Override
        public String toString() {
            return "Entry{" +
                    "key=" + key +
                    ", value=" + value +
                    '}';
        }
    }

    public static class Builder {

        private final List<Entry> entries = new LinkedList<Entry>();
        private ByteBuffer keyPrefix = null;
        private int keyPrefixSize = 0;

        public void add(Key key, Value value) {
            entries.add(new Entry(key, value));
            ByteBuffer keyData = key.data();
            if (keyPrefix == null) {
                keyPrefix = keyData.duplicate();
                keyPrefixSize = keyData.remaining();
            } else {
                keyPrefixSize = Math.min(keyPrefixSize, keyData.remaining());
                for (int i = 0; i < keyPrefixSize; i++) {
                    if (keyPrefix.get(i) != keyData.get(i)) {
                        keyPrefixSize = i;
                        break;
                    }
                }
            }
        }

        public SortedByteMap build() {
            return new SortedByteMap(serializeEntries());
        }

        private MemoryPointer serializeEntries() {
            //Allocate pointer
            int memorySize = 0;
            int[] entryOffsets = new int[entries.size()];

            memorySize += Sizes.INT_SIZE; //Key prefix size
            memorySize += keyPrefixSize; //Key prefix
            memorySize += Sizes.INT_SIZE; //MemoryPointer count
            memorySize += Sizes.INT_SIZE * entries.size(); //Pointers

            //Compute pointer size
            int counter = 0;

            for (Entry entry : entries) {
                entryOffsets[counter] = memorySize;
                int keySize = entry.key().size() - keyPrefixSize;
                memorySize += VarInts.computeRawVarint32Size(keySize);
                memorySize += keySize;
                memorySize += VarInts.computeRawVarint64Size(entry.key().snapshotId());
                memorySize += VarInts.computeRawVarint32Size(entry.value().size());
                memorySize += entry.value().size();
                counter++;
            }

            MemoryPointer pointer = MemoryAllocator.allocate(memorySize, PAGE_SIZE);
            ByteBuffer memoryBuffer = pointer.directBuffer();

            //Key prefix
            memoryBuffer.putInt(keyPrefixSize);
            for (int i = 0; i < keyPrefixSize; i++) {
                memoryBuffer.put(keyPrefix.get(i));
            }

            //Pack pointers
            memoryBuffer.putInt(entries.size());

            for (int i = 0; i < entryOffsets.length; i++) {
                memoryBuffer.putInt(entryOffsets[i]);
            }

            //Pack entries
            for (Entry entry : entries) {
                Key key = entry.key();
                Value value = entry.value();

                key.data().rewind();
                value.data().rewind();

                //Key
                int keySize = key.size() - keyPrefixSize;
                VarInts.writeRawVarint32(memoryBuffer, keySize);

                ByteBuffer keyData = key.data();
                for (int i = keyPrefixSize; i < key.size(); i++) {
                    memoryBuffer.put(keyData.get(i));
                }

                VarInts.writeRawVarint64(memoryBuffer, key.snapshotId());

                //Value
                VarInts.writeRawVarint32(memoryBuffer, value.size());

                ByteBuffer valueData = value.data();
                for (int i = 0; i < value.size(); i++) {
                    memoryBuffer.put(valueData.get(i));
                }

                key.data().rewind();
                value.data().rewind();
            }

            memoryBuffer.rewind();

            return pointer;
        }
    }

    private class AscendingIterator implements Iterator<Entry> {

        private int currentEntryIndex;

        public AscendingIterator(int startIndex) {
            this.currentEntryIndex = startIndex;
        }

        @Override
        public boolean hasNext() {
            return currentEntryIndex < entryCount;
        }

        @Override
        public Entry next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            Entry entry = get(currentEntryIndex);
            currentEntryIndex++;
            return entry;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private class DescendingIterator implements Iterator<Entry> {

        private int currentEntryIndex;

        public DescendingIterator(int startIndex) {
            this.currentEntryIndex = startIndex;
        }

        @Override
        public boolean hasNext() {
            return currentEntryIndex > -1;
        }

        @Override
        public Entry next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            Entry entry = get(currentEntryIndex);
            currentEntryIndex--;
            return entry;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private final MemoryPointer pointer;
    private final int entryCount;
    private final byte[] keyPrefix;

    public SortedByteMap(MemoryPointer pointer) {
        this.pointer = pointer;

        long addr = pointer.address();
        int keyPrefixSize = unsafe.getInt(addr);
        addr += Sizes.INT_SIZE;

        keyPrefix = new byte[keyPrefixSize];
        for (int i = 0; i < keyPrefixSize; i++) {
            keyPrefix[i] = unsafe.getByte(addr + i);
        }
        addr += keyPrefixSize;

        this.entryCount = unsafe.getInt(addr);
    }

    public Entry get(int index) {
        return getEntry(index);
    }

    public int floorIndex(Key key) {
        if (pointer.isFree()) {
            throw new IllegalStateException("Memory was already freed");
        }

        int low = 0;
        int high = entryCount - 1;

        //Binary search
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int compare = compareKeys(key, mid);

            if (compare < 0) {
                low = mid + 1;
            } else if (compare > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        return low - 1;
    }

    public int ceilingIndex(Key key) {
        if (pointer.isFree()) {
            throw new IllegalStateException("Memory was already freed");
        }

        int low = 0;
        int high = entryCount - 1;

        //Binary search
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int compare = compareKeys(key, mid);

            if (compare < 0) {
                low = mid + 1;
            } else if (compare > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        return low;
    }

    public int entryCount() {
        return entryCount;
    }

    public Iterator<Entry> ascendingIterator() {
        return new AscendingIterator(0);
    }

    public Iterator<Entry> ascendingIterator(Key key) {
        Key versionedKey = new Key(key.data(), 0);
        int startIndex = ceilingIndex(versionedKey);
        return new AscendingIterator(startIndex);
    }

    public Iterator<Entry> descendingIterator() {
        return new DescendingIterator(entryCount() - 1);
    }

    public Iterator<Entry> descendingIterator(Key key) {
        Key versionedKey = new Key(key.data(), Long.MAX_VALUE);
        int startIndex = floorIndex(versionedKey);
        return new DescendingIterator(startIndex);
    }

    @Override
    public Iterator<Entry> iterator() {
        return new AscendingIterator(0);
    }

    @Override
    public MemoryPointer memory() {
        return pointer;
    }

    @Override
    public String toString() {
        List<Entry> entries = new ArrayList<Entry>();
        for (Entry entry : this) {
            entries.add(entry);
        }

        return "SortedByteMap{entries=" + entries + "}";
    }

    private Entry getEntry(int index) {
        if (index < 0 || index >= entryCount) {
            throw new IndexOutOfBoundsException("Requested Index: " + index + " Max: " + (entryCount - 1));
        }

        if (pointer.isFree()) {
            throw new IllegalStateException("Memory was already freed");
        }

        int entryOffset = entryOffset(index);
        long startAddress = pointer.address();

        //Key
        int keySize = VarInts.readRawVarint32(unsafe, startAddress + entryOffset);

        ByteBuffer keyBuffer = ByteBuffer.allocate(keyPrefix.length + keySize);
        keyBuffer.put(keyPrefix);
        int keyOffset = entryOffset + VarInts.computeRawVarint32Size(keySize);
        byte[] keyArray = keyBuffer.array();
        long keyAddress = startAddress + keyOffset;

        for (int i = 0; i < keySize; i++) {
            keyArray[keyPrefix.length + i] = unsafe.getByte(keyAddress++);
        }

        long snapshotId = VarInts.readRawVarint64(unsafe, startAddress + keyOffset + keySize);

        keyBuffer.rewind();

        //Value
        int valueOffset = keyOffset + keySize + VarInts.computeRawVarint64Size(snapshotId);
        int valueSize = VarInts.readRawVarint32(unsafe, startAddress + valueOffset);
        ByteBuffer valueBuffer = ByteBuffer.allocate(valueSize);
        valueOffset += VarInts.computeRawVarint32Size(valueSize);
        byte[] valueArray = valueBuffer.array();
        long valueAddress = startAddress + valueOffset;

        for (int i = 0; i < valueSize; i++) {
            valueArray[i] = unsafe.getByte(valueAddress++);
        }

        valueBuffer.rewind();

        return new Entry(new Key(keyBuffer, snapshotId), new Value(valueBuffer));
    }

    private int compareKeys(Key compareKey, int bufferKeyIndex) {
        ByteBuffer compareKeyBuffer = compareKey.data().duplicate();
        compareKeyBuffer.rewind();

        int compareKeyRemaining = compareKeyBuffer.remaining();
        int compareCount = Math.min(keyPrefix.length, compareKeyRemaining);

        //Compare key prefix bytes
        for (int i = 0; i < compareCount; i++) {
            /* TODO: enable unsigned key comparisons, may need to hit elsewhere also
               int bufferKeyVal = 0xff & unsafe.getByte(startAddress + entryOffset + i);
               int compareKeyVal = 0xff & compareKeyArray[i];
             */
            byte bufferKeyVal = keyPrefix[i];
            byte compareKeyVal = compareKeyBuffer.get();
            compareKeyRemaining--;

            if (bufferKeyVal == compareKeyVal) {
                continue;
            }

            if (bufferKeyVal < compareKeyVal) {
                return -1;
            }

            return 1;
        }

        int entryOffset = entryOffset(bufferKeyIndex);
        long startAddress = pointer.address();

        int keySize = VarInts.readRawVarint32(unsafe, startAddress + entryOffset);
        entryOffset += VarInts.computeRawVarint32Size(keySize);

        int bufferKeyRemaining = keySize;
        compareCount = Math.min(bufferKeyRemaining, compareKeyRemaining);

        //Compare key bytes
        for (int i = 0; i < compareCount; i++) {
            /* TODO: enable unsigned key comparisons, may need to hit elsewhere also
               int bufferKeyVal = 0xff & unsafe.getByte(startAddress + entryOffset + i);
               int compareKeyVal = 0xff & compareKeyArray[i];
             */
            byte bufferKeyVal = unsafe.getByte(startAddress + entryOffset + i);
            byte compareKeyVal = compareKeyBuffer.get();
            bufferKeyRemaining--;
            compareKeyRemaining--;

            if (bufferKeyVal == compareKeyVal) {
                continue;
            }

            if (bufferKeyVal < compareKeyVal) {
                return -1;
            }

            return 1;
        }

        int remainingDifference = bufferKeyRemaining - compareKeyRemaining;

        //If key bytes are equal, compare snapshot ids
        if (remainingDifference == 0) {
            long bufferSnapshotId = VarInts.readRawVarint64(unsafe, startAddress + entryOffset + compareCount);
            return Long.compare(bufferSnapshotId, compareKey.snapshotId());
        }

        return remainingDifference;
    }

    private int entryOffset(int index) {
        return unsafe.getInt(pointer.address() + (Sizes.INT_SIZE + keyPrefix.length + Sizes.INT_SIZE + (index * Sizes.INT_SIZE)));
    }
}
