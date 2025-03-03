/*
 * Copyright (c) 2014, 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, Alibaba Group Holding Limited. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.bench.java.util.concurrent;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class Maps {
    private SimpleRandom rng;
    private Map<Integer, Integer> map;
    private Map<Integer, Integer> staticMap;
    private Integer[] key;

    private int removesPerMaxRandom;
    private int insertsPerMaxRandom;
    private int total;
    private int position;

    @Param("10000")
    private int nkeys;

    @Setup
    public void initTest() {
        int pRemove = 10;
        int pInsert = 90;
        removesPerMaxRandom = (int) ((pRemove / 100.0 * 0x7FFFFFFFL));
        insertsPerMaxRandom = (int) ((pInsert / 100.0 * 0x7FFFFFFFL));

        rng = new SimpleRandom();
        map = new ConcurrentHashMap<>();
        staticMap = new ConcurrentHashMap<>();
        total = 0;
        key = new Integer[nkeys];
        for (int i = 0; i < key.length; ++i) {
            key[i] = rng.next();
            staticMap.put(rng.next(), rng.next());
        }
        position = key.length / 2;
    }

    @Benchmark
    @Threads(4)
    public void testConcurrentHashMap() {
        int pos = position;
        // random-walk around key positions, bunching accesses
        int r = rng.next();
        pos += (r & 7) - 3;
        while (pos >= key.length) {
            pos -= key.length;
        }
        while (pos < 0) {
            pos += key.length;
        }
        Integer k = key[pos];
        Integer x = map.get(k);
        if (x != null) {
            if (x.intValue() != k.intValue()) {
                throw new Error("bad mapping: " + x + " to " + k);
            }

            if (r < removesPerMaxRandom) {
                if (map.remove(k) != null) {
                    pos = total % key.length; // move from position
                }
            }
        } else if (r < insertsPerMaxRandom) {
            ++pos;
            map.put(k, k);
        }
        total += r;
        position = pos;
    }

    @Benchmark
    public ConcurrentHashMap<Integer, Integer> testConcurrentHashMapCopyConstructor() {
        return new ConcurrentHashMap<>(staticMap);
    }

    @Benchmark
    public ConcurrentHashMap<Integer, Integer> testConcurrentHashMapPutAll() {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(nkeys);
        for (int i = 0; i < nkeys; ++i) {
            map.put(rng.next(), rng.next());
        }
        map.putAll(staticMap);
        return map;
    }

    @Benchmark
    public int testConcurrentHashMapIterators() {
        ConcurrentHashMap<Integer, Integer> map = (ConcurrentHashMap<Integer, Integer>) staticMap;
        int sum = 0;
        Enumeration it = map.elements();
        while (it.hasMoreElements()) {
            sum += (int) it.nextElement();
        }
        it = map.keys();
        while (it.hasMoreElements()) {
            sum += (int) it.nextElement();
        }
        return sum;
    }

    private static class SimpleRandom {
        private final static long multiplier = 0x5DEECE66DL;
        private final static long addend = 0xBL;
        private final static long mask = (1L << 48) - 1;
        private final static AtomicLong seq = new AtomicLong(1);
        private long seed = System.nanoTime() + seq.getAndIncrement();

        public int next() {
            long nextSeed = (seed * multiplier + addend) & mask;
            seed = nextSeed;
            return ((int) (nextSeed >>> 17)) & 0x7FFFFFFF;
        }
    }
}
