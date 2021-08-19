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
package org.apache.lucene.search;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.FieldValueHitQueue.Entry;
import org.apache.lucene.search.MaxScoreAccumulator.DocAndScore;
import org.apache.lucene.search.TotalHits.Relation;

/**
 * A {@link Collector} that sorts by {@link SortField} using {@link FieldComparator}s.
 *
 * <p>See the {@link #create(org.apache.lucene.search.Sort, int, int)} method for instantiating a
 * TopFieldCollector.
 *
 * @lucene.experimental
 */
public abstract class TopFieldCollector extends TopDocsCollector<Entry> {

  // TODO: one optimization we could do is to pre-fill
  // the queue with sentinel value that guaranteed to
  // always compare lower than a real hit; this would
  // save having to check queueFull on each insert

  private abstract class TopFieldLeafCollector implements LeafCollector {

    final LeafFieldComparator comparator;
    final int reverseMul;
    Scorable scorer;
    boolean collectedAllCompetitiveHits = false;

    TopFieldLeafCollector(FieldValueHitQueue<Entry> queue, Sort sort, LeafReaderContext context)
        throws IOException {
      // as all segments are sorted in the same way, enough to check only the 1st segment for
      // indexSort
      if (searchSortPartOfIndexSort == null) {
        final Sort indexSort = context.reader().getMetaData().getSort();
        searchSortPartOfIndexSort = canEarlyTerminate(sort, indexSort);
        if (searchSortPartOfIndexSort) {
          firstComparator.disableSkipping();
        }
      }
      LeafFieldComparator[] comparators = queue.getComparators(context);
      int[] reverseMuls = queue.getReverseMul();
      if (comparators.length == 1) {
        this.reverseMul = reverseMuls[0];
        this.comparator = comparators[0];
      } else {
        this.reverseMul = 1;
        this.comparator = new MultiLeafFieldComparator(comparators, reverseMuls);
      }
    }

    void countHit(int doc) throws IOException {
      ++totalHits;
      hitsThresholdChecker.incrementHitCount();

      if (minScoreAcc != null && (totalHits & minScoreAcc.modInterval) == 0) {
        updateGlobalMinCompetitiveScore(scorer);
      }
      if (scoreMode.isExhaustive() == false
          && totalHitsRelation == TotalHits.Relation.EQUAL_TO
          && hitsThresholdChecker.isThresholdReached()) {
        // for the first time hitsThreshold is reached, notify comparator about this
        comparator.setHitsThresholdReached();
        totalHitsRelation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
      }
    }

    boolean thresholdCheck(int doc) throws IOException {
      if (collectedAllCompetitiveHits || reverseMul * comparator.compareBottom(doc) <= 0) {
        // since docs are visited in doc Id order, if compare is 0, it means
        // this document is largest than anything else in the queue, and
        // therefore not competitive.
        if (searchSortPartOfIndexSort) {
          if (hitsThresholdChecker.isThresholdReached()) {
            totalHitsRelation = Relation.GREATER_THAN_OR_EQUAL_TO;
            throw new CollectionTerminatedException();
          } else {
            collectedAllCompetitiveHits = true;
          }
        } else if (totalHitsRelation == TotalHits.Relation.EQUAL_TO) {
          // we can start setting the min competitive score if the
          // threshold is reached for the first time here.
          updateMinCompetitiveScore(scorer);
        }
        return true;
      }
      return false;
    }

    void collectCompetitiveHit(int doc) throws IOException {
      // This hit is competitive - replace bottom element in queue & adjustTop
      comparator.copy(bottom.slot, doc);
      updateBottom(doc);
      comparator.setBottom(bottom.slot);
      updateMinCompetitiveScore(scorer);
    }

    void collectAnyHit(int doc, int hitsCollected) throws IOException {
      // Startup transient: queue hasn't gathered numHits yet
      int slot = hitsCollected - 1;
      // Copy hit into queue
      comparator.copy(slot, doc);
      add(slot, doc);
      if (queueFull) {
        comparator.setBottom(bottom.slot);
        updateMinCompetitiveScore(scorer);
      }
    }

    @Override
    public void setScorer(Scorable scorer) throws IOException {
      this.scorer = scorer;
      comparator.setScorer(scorer);
      minCompetitiveScore = 0f;
      updateMinCompetitiveScore(scorer);
      if (minScoreAcc != null) {
        updateGlobalMinCompetitiveScore(scorer);
      }
    }

    @Override
    public DocIdSetIterator competitiveIterator() throws IOException {
      return comparator.competitiveIterator();
    }
  }

  static boolean canEarlyTerminate(Sort searchSort, Sort indexSort) {
    return canEarlyTerminateOnDocId(searchSort) || canEarlyTerminateOnPrefix(searchSort, indexSort);
  }

  private static boolean canEarlyTerminateOnDocId(Sort searchSort) {
    final SortField[] fields1 = searchSort.getSort();
    return SortField.FIELD_DOC.equals(fields1[0]);
  }

  private static boolean canEarlyTerminateOnPrefix(Sort searchSort, Sort indexSort) {
    if (indexSort != null) {
      final SortField[] fields1 = searchSort.getSort();
      final SortField[] fields2 = indexSort.getSort();
      // early termination is possible if fields1 is a prefix of fields2
      if (fields1.length > fields2.length) {
        return false;
      }
      return Arrays.asList(fields1).equals(Arrays.asList(fields2).subList(0, fields1.length));
    } else {
      return false;
    }
  }

  /*
   * Implements a TopFieldCollector over one SortField criteria, with tracking
   * document scores and maxScore.
   */
  static class SimpleFieldCollector extends TopFieldCollector {
    final Sort sort;
    final FieldValueHitQueue<Entry> queue;

    public SimpleFieldCollector(
        Sort sort,
        FieldValueHitQueue<Entry> queue,
        int numHits,
        HitsThresholdChecker hitsThresholdChecker,
        MaxScoreAccumulator minScoreAcc) {
      super(queue, numHits, hitsThresholdChecker, sort.needsScores(), minScoreAcc);
      this.sort = sort;
      this.queue = queue;
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
      docBase = context.docBase;

      return new TopFieldLeafCollector(queue, sort, context) {

        @Override
        public void collect(int doc) throws IOException {
          countHit(doc);
          if (queueFull) {
            if (thresholdCheck(doc)) {
              return;
            }
            collectCompetitiveHit(doc);
          } else {
            collectAnyHit(doc, totalHits);
          }
        }
      };
    }
  }

  /*
   * Implements a TopFieldCollector when after != null.
   */
  static final class PagingFieldCollector extends TopFieldCollector {

    final Sort sort;
    int collectedHits;
    final FieldValueHitQueue<Entry> queue;
    final FieldDoc after;

    public PagingFieldCollector(
        Sort sort,
        FieldValueHitQueue<Entry> queue,
        FieldDoc after,
        int numHits,
        HitsThresholdChecker hitsThresholdChecker,
        MaxScoreAccumulator minScoreAcc) {
      super(queue, numHits, hitsThresholdChecker, sort.needsScores(), minScoreAcc);
      this.sort = sort;
      this.queue = queue;
      this.after = after;

      FieldComparator<?>[] comparators = queue.comparators;
      // Tell all comparators their top value:
      for (int i = 0; i < comparators.length; i++) {
        @SuppressWarnings("unchecked")
        FieldComparator<Object> comparator = (FieldComparator<Object>) comparators[i];
        comparator.setTopValue(after.fields[i]);
      }
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
      docBase = context.docBase;
      final int afterDoc = after.doc - docBase;

      return new TopFieldLeafCollector(queue, sort, context) {

        @Override
        public void collect(int doc) throws IOException {
          countHit(doc);
          if (queueFull) {
            if (thresholdCheck(doc)) {
              return;
            }
          }
          final int topCmp = reverseMul * comparator.compareTop(doc);
          if (topCmp > 0 || (topCmp == 0 && doc <= afterDoc)) {
            // Already collected on a previous page
            if (totalHitsRelation == TotalHits.Relation.EQUAL_TO) {
              // check if totalHitsThreshold is reached and we can update competitive score
              // necessary to account for possible update to global min competitive score
              updateMinCompetitiveScore(scorer);
            }
            return;
          }
          if (queueFull) {
            collectCompetitiveHit(doc);
          } else {
            collectedHits++;
            collectAnyHit(doc, collectedHits);
          }
        }
      };
    }
  }

  private static final ScoreDoc[] EMPTY_SCOREDOCS = new ScoreDoc[0];

  final int numHits;
  final HitsThresholdChecker hitsThresholdChecker;
  final FieldComparator<?> firstComparator;
  final boolean canSetMinScore;

  Boolean searchSortPartOfIndexSort = null; // shows if Search Sort if a part of the Index Sort

  // an accumulator that maintains the maximum of the segment's minimum competitive scores
  final MaxScoreAccumulator minScoreAcc;
  // the current local minimum competitive score already propagated to the underlying scorer
  float minCompetitiveScore;

  final int numComparators;
  FieldValueHitQueue.Entry bottom = null;
  boolean queueFull;
  int docBase;
  final boolean needsScores;
  final ScoreMode scoreMode;

  // Declaring the constructor private prevents extending this class by anyone
  // else. Note that the class cannot be final since it's extended by the
  // internal versions. If someone will define a constructor with any other
  // visibility, then anyone will be able to extend the class, which is not what
  // we want.
  private TopFieldCollector(
      FieldValueHitQueue<Entry> pq,
      int numHits,
      HitsThresholdChecker hitsThresholdChecker,
      boolean needsScores,
      MaxScoreAccumulator minScoreAcc) {
    super(pq);
    this.needsScores = needsScores;
    this.numHits = numHits;
    this.hitsThresholdChecker = hitsThresholdChecker;
    this.numComparators = pq.getComparators().length;
    this.firstComparator = pq.getComparators()[0];
    int reverseMul = pq.reverseMul[0];

    if (firstComparator.getClass().equals(FieldComparator.RelevanceComparator.class)
        && reverseMul == 1 // if the natural sort is preserved (sort by descending relevance)
        && hitsThresholdChecker.getHitsThreshold() != Integer.MAX_VALUE) {
      scoreMode = ScoreMode.TOP_SCORES;
      canSetMinScore = true;
    } else {
      canSetMinScore = false;
      if (hitsThresholdChecker.getHitsThreshold() != Integer.MAX_VALUE) {
        scoreMode = needsScores ? ScoreMode.TOP_DOCS_WITH_SCORES : ScoreMode.TOP_DOCS;
      } else {
        scoreMode = needsScores ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES;
      }
    }
    this.minScoreAcc = minScoreAcc;
  }

  @Override
  public ScoreMode scoreMode() {
    return scoreMode;
  }

  protected void updateGlobalMinCompetitiveScore(Scorable scorer) throws IOException {
    assert minScoreAcc != null;
    if (canSetMinScore && hitsThresholdChecker.isThresholdReached()) {
      // we can start checking the global maximum score even
      // if the local queue is not full because the threshold
      // is reached.
      DocAndScore maxMinScore = minScoreAcc.get();
      if (maxMinScore != null && maxMinScore.score > minCompetitiveScore) {
        scorer.setMinCompetitiveScore(maxMinScore.score);
        minCompetitiveScore = maxMinScore.score;
        totalHitsRelation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
      }
    }
  }

  protected void updateMinCompetitiveScore(Scorable scorer) throws IOException {
    if (canSetMinScore && queueFull && hitsThresholdChecker.isThresholdReached()) {
      assert bottom != null;
      float minScore = (float) firstComparator.value(bottom.slot);
      if (minScore > minCompetitiveScore) {
        scorer.setMinCompetitiveScore(minScore);
        minCompetitiveScore = minScore;
        totalHitsRelation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
        if (minScoreAcc != null) {
          minScoreAcc.accumulate(bottom.doc, minScore);
        }
      }
    }
  }

  /**
   * Creates a new {@link TopFieldCollector} from the given arguments.
   *
   * <p><b>NOTE</b>: The instances returned by this method pre-allocate a full array of length
   * <code>numHits</code>.
   *
   * @param sort the sort criteria (SortFields).
   * @param numHits the number of results to collect.
   * @param totalHitsThreshold the number of docs to count accurately. If the query matches more
   *     than {@code totalHitsThreshold} hits then its hit count will be a lower bound. On the other
   *     hand if the query matches less than or exactly {@code totalHitsThreshold} hits then the hit
   *     count of the result will be accurate. {@link Integer#MAX_VALUE} may be used to make the hit
   *     count accurate, but this will also make query processing slower.
   * @return a {@link TopFieldCollector} instance which will sort the results by the sort criteria.
   * @deprecated This method is being deprecated in favor of using the constructor of {@link
   *     TopFieldCollectorManager} due to its support for concurrency in IndexSearcher
   */
  @Deprecated
  public static TopFieldCollector create(Sort sort, int numHits, int totalHitsThreshold) {
    return new TopFieldCollectorManager(sort, numHits, null, totalHitsThreshold, false)
        .newCollector();
  }

  /**
   * Creates a new {@link TopFieldCollector} from the given arguments.
   *
   * <p><b>NOTE</b>: The instances returned by this method pre-allocate a full array of length
   * <code>numHits</code>.
   *
   * @param sort the sort criteria (SortFields).
   * @param numHits the number of results to collect.
   * @param after only hits after this FieldDoc will be collected
   * @param totalHitsThreshold the number of docs to count accurately. If the query matches more
   *     than {@code totalHitsThreshold} hits then its hit count will be a lower bound. On the other
   *     hand if the query matches less than or exactly {@code totalHitsThreshold} hits then the hit
   *     count of the result will be accurate. {@link Integer#MAX_VALUE} may be used to make the hit
   *     count accurate, but this will also make query processing slower. Setting totalHitsThreshold
   *     less than {@link Integer#MAX_VALUE} instructs Lucene to skip non-competitive documents
   *     whenever possible. For numeric sort fields the skipping functionality works when the same
   *     field is indexed both with doc values and points. In this case, there is an assumption that
   *     the same data is stored in these points and doc values.
   * @return a {@link TopFieldCollector} instance which will sort the results by the sort criteria.
   * @deprecated This method is being deprecated in favor of using the constructor of {@link
   *     TopFieldCollectorManager} due to its support for concurrency in IndexSearcher
   */
  @Deprecated
  public static TopFieldCollector create(
      Sort sort, int numHits, FieldDoc after, int totalHitsThreshold) {
    return new TopFieldCollectorManager(sort, numHits, after, totalHitsThreshold, false)
        .newCollector();
  }

  /**
   * Populate {@link ScoreDoc#score scores} of the given {@code topDocs}.
   *
   * @param topDocs the top docs to populate
   * @param searcher the index searcher that has been used to compute {@code topDocs}
   * @param query the query that has been used to compute {@code topDocs}
   * @throws IllegalArgumentException if there is evidence that {@code topDocs} have been computed
   *     against a different searcher or a different query.
   * @lucene.experimental
   */
  public static void populateScores(ScoreDoc[] topDocs, IndexSearcher searcher, Query query)
      throws IOException {
    // Get the score docs sorted in doc id order
    topDocs = topDocs.clone();
    Arrays.sort(topDocs, Comparator.comparingInt(scoreDoc -> scoreDoc.doc));

    final Weight weight = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE, 1);
    List<LeafReaderContext> contexts = searcher.getIndexReader().leaves();
    LeafReaderContext currentContext = null;
    Scorer currentScorer = null;
    for (ScoreDoc scoreDoc : topDocs) {
      if (currentContext == null
          || scoreDoc.doc >= currentContext.docBase + currentContext.reader().maxDoc()) {
        Objects.checkIndex(scoreDoc.doc, searcher.getIndexReader().maxDoc());
        int newContextIndex = ReaderUtil.subIndex(scoreDoc.doc, contexts);
        currentContext = contexts.get(newContextIndex);
        final ScorerSupplier scorerSupplier = weight.scorerSupplier(currentContext);
        if (scorerSupplier == null) {
          throw new IllegalArgumentException("Doc id " + scoreDoc.doc + " doesn't match the query");
        }
        currentScorer = scorerSupplier.get(1); // random-access
      }
      final int leafDoc = scoreDoc.doc - currentContext.docBase;
      assert leafDoc >= 0;
      final int advanced = currentScorer.iterator().advance(leafDoc);
      if (leafDoc != advanced) {
        throw new IllegalArgumentException("Doc id " + scoreDoc.doc + " doesn't match the query");
      }
      scoreDoc.score = currentScorer.score();
    }
  }

  final void add(int slot, int doc) {
    bottom = pq.add(new Entry(slot, docBase + doc));
    // The queue is full either when totalHits == numHits (in SimpleFieldCollector), in which case
    // slot = totalHits - 1, or when hitsCollected == numHits (in PagingFieldCollector this is hits
    // on the current page) and slot = hitsCollected - 1.
    assert slot < numHits;
    queueFull = slot == numHits - 1;
  }

  final void updateBottom(int doc) {
    // bottom.score is already set to Float.NaN in add().
    bottom.doc = docBase + doc;
    bottom = pq.updateTop();
  }

  /*
   * Only the following callback methods need to be overridden since
   * topDocs(int, int) calls them to return the results.
   */

  @Override
  protected void populateResults(ScoreDoc[] results, int howMany) {
    // avoid casting if unnecessary.
    FieldValueHitQueue<Entry> queue = (FieldValueHitQueue<Entry>) pq;
    for (int i = howMany - 1; i >= 0; i--) {
      results[i] = queue.fillFields(queue.pop());
    }
  }

  @Override
  protected TopDocs newTopDocs(ScoreDoc[] results, int start) {
    if (results == null) {
      results = EMPTY_SCOREDOCS;
    }

    // If this is a maxScoring tracking collector and there were no results,
    return new TopFieldDocs(
        new TotalHits(totalHits, totalHitsRelation),
        results,
        ((FieldValueHitQueue<Entry>) pq).getFields());
  }

  @Override
  public TopFieldDocs topDocs() {
    return (TopFieldDocs) super.topDocs();
  }

  /** Return whether collection terminated early. */
  public boolean isEarlyTerminated() {
    return totalHitsRelation == Relation.GREATER_THAN_OR_EQUAL_TO;
  }
}
