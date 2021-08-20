/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util;

import java.util.Collection;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;

/** Test utility collector that uses FixedBitSet to record hits. */
public class FixedBitSetCollector extends SimpleCollector {
  private final FixedBitSet hits;
  private int docBase;

  public FixedBitSetCollector(int maxDoc) {
    hits = new FixedBitSet(maxDoc);
  }

  @Override
  public ScoreMode scoreMode() {
    return ScoreMode.COMPLETE_NO_SCORES;
  }

  @Override
  protected void doSetNextReader(LeafReaderContext context) {
    docBase = context.docBase;
  }

  @Override
  public void collect(int doc) {
    hits.set(docBase + doc);
  }

  public FixedBitSet getHits() {
    return hits;
  }

  public static CollectorManager<FixedBitSetCollector, FixedBitSet> createManager(int maxDoc) {
    return new CollectorManager<>() {
      @Override
      public FixedBitSetCollector newCollector() {
        return new FixedBitSetCollector(maxDoc);
      }

      @Override
      public FixedBitSet reduce(Collection<FixedBitSetCollector> collectors) {
        FixedBitSet result = new FixedBitSet(maxDoc);
        collectors.forEach(c -> result.or(c.getHits()));
        return result;
      }
    };
  }
}
