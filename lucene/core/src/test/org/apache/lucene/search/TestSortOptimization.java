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

import static org.apache.lucene.search.SortField.FIELD_DOC;
import static org.apache.lucene.search.SortField.FIELD_SCORE;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.IntRange;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.LuceneTestCase;

public class TestSortOptimization extends LuceneTestCase {

  public void testLongSortOptimization() throws IOException {

    final Directory dir = newDirectory();
    final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    final int numDocs = atLeast(10000);
    for (int i = 0; i < numDocs; ++i) {
      final Document doc = new Document();
      doc.add(new NumericDocValuesField("my_field", i));
      doc.add(new LongPoint("my_field", i));
      writer.addDocument(doc);
      if (i == 7000) writer.flush(); // two segments
    }
    final IndexReader reader = DirectoryReader.open(writer);
    writer.close();
    IndexSearcher searcher = new IndexSearcher(reader);
    final SortField sortField = new SortField("my_field", SortField.Type.LONG);
    final Sort sort = new Sort(sortField);
    final int numHits = 3;
    final int totalHitsThreshold = 3;

    { // simple sort
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);

      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);

      assertEquals(topDocs.scoreDocs.length, numHits);
      for (int i = 0; i < numHits; i++) {
        FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[i];
        assertEquals(i, ((Long) fieldDoc.fields[0]).intValue());
      }
      assertTrue(
          collectorManager.getCollectors().stream().anyMatch(TopFieldCollector::isEarlyTerminated));
      assertTrue(topDocs.totalHits.value < numDocs);
    }

    { // paging sort with after
      long afterValue = 2;
      FieldDoc after = new FieldDoc(2, Float.NaN, new Long[] {afterValue});
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, after, totalHitsThreshold);
      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(topDocs.scoreDocs.length, numHits);
      for (int i = 0; i < numHits; i++) {
        FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[i];
        assertEquals(afterValue + 1 + i, fieldDoc.fields[0]);
      }
      assertTrue(
          collectorManager.getCollectors().stream().anyMatch(TopFieldCollector::isEarlyTerminated));
      assertTrue(topDocs.totalHits.value < numDocs);
    }

    { // test that if there is the secondary sort on _score, scores are filled correctly
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(
              new Sort(sortField, FIELD_SCORE), numHits, totalHitsThreshold);
      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(topDocs.scoreDocs.length, numHits);
      for (int i = 0; i < numHits; i++) {
        FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[i];
        assertEquals(i, ((Long) fieldDoc.fields[0]).intValue());
        float score = (float) fieldDoc.fields[1];
        assertEquals(1.0, score, 0.001);
      }
      assertTrue(
          collectorManager.getCollectors().stream().anyMatch(TopFieldCollector::isEarlyTerminated));
      assertTrue(topDocs.totalHits.value < numDocs);
    }

    { // test that if numeric field is a secondary sort, no optimization is run
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(
              new Sort(FIELD_SCORE, sortField), numHits, totalHitsThreshold);
      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(topDocs.scoreDocs.length, numHits);
      assertEquals(
          topDocs.totalHits.value,
          numDocs); // assert that all documents were collected => optimization was not run
    }

    reader.close();
    dir.close();
  }

  /**
   * test that even if a field is not indexed with points, optimized sort still works as expected,
   * although no optimization will be run
   */
  public void testLongSortOptimizationOnFieldNotIndexedWithPoints() throws IOException {
    final Directory dir = newDirectory();
    final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    final int numDocs = atLeast(100);
    // my_field is not indexed with points
    for (int i = 0; i < numDocs; ++i) {
      final Document doc = new Document();
      doc.add(new NumericDocValuesField("my_field", i));
      writer.addDocument(doc);
    }
    final IndexReader reader = DirectoryReader.open(writer);
    writer.close();
    IndexSearcher searcher = new IndexSearcher(reader);
    final SortField sortField = new SortField("my_field", SortField.Type.LONG);
    final Sort sort = new Sort(sortField);
    final int numHits = 3;
    final int totalHitsThreshold = 3;

    final TopFieldCollectorManager collectorManager =
        new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);
    TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
    assertEquals(
        topDocs.scoreDocs.length, numHits); // sort still works and returns expected number of docs
    for (int i = 0; i < numHits; i++) {
      FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[i];
      assertEquals(i, ((Long) fieldDoc.fields[0]).intValue()); // returns expected values
    }
    assertEquals(
        topDocs.totalHits.value,
        numDocs); // assert that all documents were collected => optimization was not run

    reader.close();
    dir.close();
  }

  public void testSortOptimizationWithMissingValues() throws IOException {
    final Directory dir = newDirectory();
    final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    final int numDocs = atLeast(10000);
    for (int i = 0; i < numDocs; ++i) {
      final Document doc = new Document();
      if ((i % 500) != 0) { // miss values on every 500th document
        doc.add(new NumericDocValuesField("my_field", i));
        doc.add(new LongPoint("my_field", i));
      }
      writer.addDocument(doc);
      if (i == 7000) writer.flush(); // two segments
    }
    final IndexReader reader = DirectoryReader.open(writer);
    writer.close();
    IndexSearcher searcher = new IndexSearcher(reader);
    final int numHits = 3;
    final int totalHitsThreshold = 3;

    { // test that optimization is not run when missing value setting of SortField is competitive
      final SortField sortField = new SortField("my_field", SortField.Type.LONG);
      sortField.setMissingValue(0L); // set a competitive missing value
      final Sort sort = new Sort(sortField);
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);
      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(topDocs.scoreDocs.length, numHits);
      assertEquals(
          topDocs.totalHits.value,
          numDocs); // assert that all documents were collected => optimization was not run
    }
    { // test that optimization is run when missing value setting of SortField is NOT competitive
      final SortField sortField = new SortField("my_field", SortField.Type.LONG);
      sortField.setMissingValue(100L); // set a NON competitive missing value
      final Sort sort = new Sort(sortField);
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);
      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(topDocs.scoreDocs.length, numHits);
      assertTrue(
          topDocs.totalHits.value
              < numDocs); // assert that some docs were skipped => optimization was run
    }

    reader.close();
    dir.close();
  }

  public void testSortOptimizationEqualValues() throws IOException {
    final Directory dir = newDirectory();
    final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    final int numDocs = atLeast(10000);
    for (int i = 1; i <= numDocs; ++i) {
      final Document doc = new Document();
      doc.add(
          new NumericDocValuesField("my_field1", 100)); // all docs have the same value of my_field1
      doc.add(new IntPoint("my_field1", 100));
      doc.add(
          new NumericDocValuesField(
              "my_field2", numDocs - i)); // diff values for the field my_field2
      writer.addDocument(doc);
      if (i == 7000) writer.flush(); // two segments
    }
    final IndexReader reader = DirectoryReader.open(writer);
    writer.close();
    IndexSearcher searcher = new IndexSearcher(reader);
    final int numHits = 3;
    final int totalHitsThreshold = 3;

    { // test that sorting on a single field with equal values uses the optimization
      final SortField sortField = new SortField("my_field1", SortField.Type.INT);
      final Sort sort = new Sort(sortField);
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);
      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(topDocs.scoreDocs.length, numHits);
      for (int i = 0; i < numHits; i++) {
        FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[i];
        assertEquals(100, fieldDoc.fields[0]);
      }
      assertTrue(
          topDocs.totalHits.value
              < numDocs); // assert that some docs were skipped => optimization was run
    }

    { // test that sorting on a single field with equal values and after parameter uses the
      // optimization
      final int afterValue = 100;
      final SortField sortField = new SortField("my_field1", SortField.Type.INT);
      final Sort sort = new Sort(sortField);
      FieldDoc after = new FieldDoc(10, Float.NaN, new Integer[] {afterValue});
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, after, totalHitsThreshold);
      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(topDocs.scoreDocs.length, numHits);
      for (int i = 0; i < numHits; i++) {
        FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[i];
        assertEquals(100, fieldDoc.fields[0]);
      }
      assertTrue(
          topDocs.totalHits.value
              < numDocs); // assert that some docs were skipped => optimization was run
    }

    { // test that sorting on main field with equal values + another field for tie breaks doesn't
      // use optimization
      final SortField sortField1 = new SortField("my_field1", SortField.Type.INT);
      final SortField sortField2 = new SortField("my_field2", SortField.Type.INT);
      final Sort sort = new Sort(sortField1, sortField2);
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);
      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(topDocs.scoreDocs.length, numHits);
      for (int i = 0; i < numHits; i++) {
        FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[i];
        assertEquals(100, fieldDoc.fields[0]); // sort on 1st field as expected
        assertEquals(i, fieldDoc.fields[1]); // sort on 2nd field as expected
      }
      assertEquals(topDocs.scoreDocs.length, numHits);
      assertEquals(
          topDocs.totalHits.value,
          numDocs); // assert that all documents were collected => optimization was not run
    }

    reader.close();
    dir.close();
  }

  public void testFloatSortOptimization() throws IOException {
    final Directory dir = newDirectory();
    final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    final int numDocs = atLeast(10000);
    for (int i = 0; i < numDocs; ++i) {
      final Document doc = new Document();
      float f = 1f * i;
      doc.add(new FloatDocValuesField("my_field", f));
      doc.add(new FloatPoint("my_field", i));
      writer.addDocument(doc);
    }
    final IndexReader reader = DirectoryReader.open(writer);
    writer.close();
    IndexSearcher searcher = new IndexSearcher(reader);
    final SortField sortField = new SortField("my_field", SortField.Type.FLOAT);
    final Sort sort = new Sort(sortField);
    final int numHits = 3;
    final int totalHitsThreshold = 3;

    { // simple sort
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);

      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(topDocs.scoreDocs.length, numHits);
      for (int i = 0; i < numHits; i++) {
        FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[i];
        assertEquals(1f * i, fieldDoc.fields[0]);
      }
      assertTrue(
          collectorManager.getCollectors().stream().anyMatch(TopFieldCollector::isEarlyTerminated));
      assertTrue(topDocs.totalHits.value < numDocs);
    }

    reader.close();
    dir.close();
  }

  /**
   * Test that a search with sort on [_doc, other fields] across multiple indices doesn't miss any
   * documents.
   */
  public void testDocSortOptimizationMultipleIndices() throws IOException {
    final int numIndices = 3;
    final int numDocsInIndex = atLeast(50);
    Directory[] dirs = new Directory[numIndices];
    IndexReader[] readers = new IndexReader[numIndices];
    for (int i = 0; i < numIndices; i++) {
      dirs[i] = newDirectory();
      try (IndexWriter writer = new IndexWriter(dirs[i], new IndexWriterConfig())) {
        for (int docID = 0; docID < numDocsInIndex; docID++) {
          final Document doc = new Document();
          doc.add(new NumericDocValuesField("my_field", docID * numIndices + i));
          writer.addDocument(doc);
        }
        writer.flush();
      }
      readers[i] = DirectoryReader.open(dirs[i]);
    }

    final int size = 7;
    final int totalHitsThreshold = 7;
    final Sort sort = new Sort(FIELD_DOC, new SortField("my_field", SortField.Type.LONG));
    TopFieldDocs[] topDocs = new TopFieldDocs[numIndices];
    int curNumHits;
    FieldDoc after = null;
    long collectedDocs = 0;
    long totalDocs = 0;
    int numHits = 0;
    do {
      for (int i = 0; i < numIndices; i++) {
        IndexSearcher searcher = newSearcher(readers[i]);
        final TopFieldCollectorManager collectorManager =
            new TopFieldCollectorManager(sort, size, after, totalHitsThreshold);
        topDocs[i] = searcher.search(new MatchAllDocsQuery(), collectorManager);
        for (int docID = 0; docID < topDocs[i].scoreDocs.length; docID++) {
          topDocs[i].scoreDocs[docID].shardIndex = i;
        }
        collectedDocs += topDocs[i].totalHits.value;
        totalDocs += numDocsInIndex;
      }
      TopFieldDocs mergedTopDcs = TopDocs.merge(sort, size, topDocs);
      curNumHits = mergedTopDcs.scoreDocs.length;
      numHits += curNumHits;
      if (curNumHits > 0) {
        after = (FieldDoc) mergedTopDcs.scoreDocs[curNumHits - 1];
      }
    } while (curNumHits > 0);

    for (int i = 0; i < numIndices; i++) {
      readers[i].close();
      dirs[i].close();
    }

    final int expectedNumHits = numDocsInIndex * numIndices;
    assertEquals(expectedNumHits, numHits);
    // check that the optimization was run, as very few docs were collected
    assertTrue(collectedDocs < totalDocs);
  }

  public void testDocSortOptimizationWithAfter() throws IOException {
    final Directory dir = newDirectory();
    final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    final int numDocs = atLeast(150);
    for (int i = 0; i < numDocs; ++i) {
      final Document doc = new Document();
      writer.addDocument(doc);
      if ((i > 0) && (i % 50 == 0)) {
        writer.flush();
      }
    }

    final IndexReader reader = DirectoryReader.open(writer);
    writer.close();
    IndexSearcher searcher = newSearcher(reader);
    final int numHits = 10;
    final int totalHitsThreshold = 10;
    final int[] searchAfters = {3, 10, numDocs - 10};
    for (int searchAfter : searchAfters) {
      // sort by _doc with search after should trigger optimization
      {
        final Sort sort = new Sort(FIELD_DOC);
        FieldDoc after = new FieldDoc(searchAfter, Float.NaN, new Integer[] {searchAfter});
        final TopFieldCollectorManager collectorManager =
            new TopFieldCollectorManager(sort, numHits, after, totalHitsThreshold);
        TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
        int expNumHits =
            (searchAfter >= (numDocs - numHits)) ? (numDocs - searchAfter - 1) : numHits;
        assertEquals(expNumHits, topDocs.scoreDocs.length);
        for (int i = 0; i < topDocs.scoreDocs.length; i++) {
          int expectedDocID = searchAfter + 1 + i;
          assertEquals(expectedDocID, topDocs.scoreDocs[i].doc);
        }
        assertTrue(
            collectorManager.getCollectors().stream()
                .anyMatch(TopFieldCollector::isEarlyTerminated));
        // check that very few docs were collected
        assertTrue(topDocs.totalHits.value < numDocs);
      }

      // sort by _doc + _score with search after should trigger optimization
      {
        final Sort sort = new Sort(FIELD_DOC, FIELD_SCORE);
        FieldDoc after = new FieldDoc(searchAfter, Float.NaN, new Object[] {searchAfter, 1.0f});
        final TopFieldCollectorManager collectorManager =
            new TopFieldCollectorManager(sort, numHits, after, totalHitsThreshold);
        TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
        int expNumHits =
            (searchAfter >= (numDocs - numHits)) ? (numDocs - searchAfter - 1) : numHits;
        assertEquals(expNumHits, topDocs.scoreDocs.length);
        for (int i = 0; i < topDocs.scoreDocs.length; i++) {
          int expectedDocID = searchAfter + 1 + i;
          assertEquals(expectedDocID, topDocs.scoreDocs[i].doc);
        }
        assertTrue(
            collectorManager.getCollectors().stream()
                .anyMatch(TopFieldCollector::isEarlyTerminated));
        // assert that very few docs were collected
        assertTrue(topDocs.totalHits.value < numDocs);
      }

      // sort by _doc desc should not trigger optimization
      {
        final Sort sort = new Sort(new SortField(null, SortField.Type.DOC, true));
        FieldDoc after = new FieldDoc(searchAfter, Float.NaN, new Integer[] {searchAfter});
        final TopFieldCollectorManager collectorManager =
            new TopFieldCollectorManager(sort, numHits, after, totalHitsThreshold);
        TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
        int expNumHits = (searchAfter < numHits) ? searchAfter : numHits;
        assertEquals(expNumHits, topDocs.scoreDocs.length);
        for (int i = 0; i < topDocs.scoreDocs.length; i++) {
          int expectedDocID = searchAfter - 1 - i;
          assertEquals(expectedDocID, topDocs.scoreDocs[i].doc);
        }
        // assert that all documents were collected
        assertEquals(numDocs, topDocs.totalHits.value);
      }
    }

    reader.close();
    dir.close();
  }

  public void testDocSortOptimization() throws IOException {
    final Directory dir = newDirectory();
    final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    final int numDocs = atLeast(100);
    int seg = 1;
    for (int i = 0; i < numDocs; ++i) {
      final Document doc = new Document();
      doc.add(new LongPoint("lf", i));
      doc.add(new StoredField("slf", i));
      doc.add(new StringField("tf", "seg" + seg, Field.Store.YES));
      writer.addDocument(doc);
      if ((i > 0) && (i % 50 == 0)) {
        writer.flush();
        seg++;
      }
    }
    final IndexReader reader = DirectoryReader.open(writer);
    writer.close();

    final int numHits = 3;
    final int totalHitsThreshold = 3;
    final Sort sort = new Sort(FIELD_DOC);

    // sort by _doc should skip all non-competitive documents
    {
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);
      IndexSearcher searcher = newSearcher(reader);
      TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), collectorManager);
      assertEquals(numHits, topDocs.scoreDocs.length);
      for (int i = 0; i < numHits; i++) {
        assertEquals(i, topDocs.scoreDocs[i].doc);
      }
      assertTrue(
          collectorManager.getCollectors().stream().anyMatch(TopFieldCollector::isEarlyTerminated));
      assertTrue(topDocs.totalHits.value < 10); // assert that very few docs were collected
    }

    // sort by _doc with a bool query should skip all non-competitive documents
    {
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);
      int lowerRange = 40;
      BooleanQuery.Builder bq = new BooleanQuery.Builder();
      bq.add(LongPoint.newRangeQuery("lf", lowerRange, Long.MAX_VALUE), BooleanClause.Occur.MUST);
      bq.add(new TermQuery(new Term("tf", "seg1")), BooleanClause.Occur.MUST);
      IndexSearcher searcher = newSearcher(reader);

      TopDocs topDocs = searcher.search(bq.build(), collectorManager);
      assertEquals(numHits, topDocs.scoreDocs.length);
      for (int i = 0; i < numHits; i++) {
        Document d = searcher.doc(topDocs.scoreDocs[i].doc);
        assertEquals(Integer.toString(i + lowerRange), d.get("slf"));
        assertEquals("seg1", d.get("tf"));
      }
      assertTrue(
          collectorManager.getCollectors().stream().anyMatch(TopFieldCollector::isEarlyTerminated));
      assertTrue(topDocs.totalHits.value < 10); // assert that very few docs were collected
    }

    reader.close();
    dir.close();
  }

  /**
   * Test that sorting on _doc works correctly. This test goes through
   * DefaultBulkSorter::scoreRange, where scorerIterator is BitSetIterator. As a conjunction of this
   * BitSetIterator with DocComparator's iterator, we get BitSetConjunctionDISI.
   * BitSetConjuctionDISI advances based on the DocComparator's iterator, and doesn't consider that
   * its BitSetIterator may have advanced passed a certain doc.
   */
  public void testDocSort() throws IOException {
    final Directory dir = newDirectory();
    final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    final int numDocs = 4;
    for (int i = 0; i < numDocs; ++i) {
      final Document doc = new Document();
      doc.add(new StringField("id", "id" + i, Field.Store.NO));
      if (i < 2) {
        doc.add(new LongPoint("lf", 1));
      }
      writer.addDocument(doc);
    }
    final IndexReader reader = DirectoryReader.open(writer);
    writer.close();

    IndexSearcher searcher = newSearcher(reader);
    searcher.setQueryCache(null);
    final int numHits = 10;
    final int totalHitsThreshold = 10;
    final Sort sort = new Sort(FIELD_DOC);

    {
      final TopFieldCollectorManager collectorManager =
          new TopFieldCollectorManager(sort, numHits, totalHitsThreshold);
      BooleanQuery.Builder bq = new BooleanQuery.Builder();
      bq.add(LongPoint.newExactQuery("lf", 1), BooleanClause.Occur.MUST);
      bq.add(new TermQuery(new Term("id", "id3")), BooleanClause.Occur.MUST_NOT);
      TopDocs topDocs = searcher.search(bq.build(), collectorManager);
      assertEquals(2, topDocs.scoreDocs.length);
    }

    reader.close();
    dir.close();
  }

  public void testPointValidation() throws IOException {
    final Directory dir = newDirectory();
    final RandomIndexWriter writer = new RandomIndexWriter(random(), dir);
    Document doc = new Document();

    doc.add(new IntPoint("intField", 4));
    doc.add(new NumericDocValuesField("intField", 4));

    doc.add(new LongPoint("longField", 42));
    doc.add(new NumericDocValuesField("longField", 42));

    doc.add(new IntRange("intRange", new int[] {1}, new int[] {10}));
    doc.add(new NumericDocValuesField("intRange", 4));

    writer.addDocument(doc);
    IndexReader reader = writer.getReader();
    writer.close();

    IndexSearcher searcher = newSearcher(reader);

    SortField longSortOnIntField = new SortField("intField", SortField.Type.LONG);
    assertThrows(
        IllegalArgumentException.class,
        () -> searcher.search(new MatchAllDocsQuery(), 1, new Sort(longSortOnIntField)));
    // assert that when sort optimization is disabled we can use LONG sort on int field
    longSortOnIntField.setOptimizeSortWithPoints(false);
    searcher.search(new MatchAllDocsQuery(), 1, new Sort(longSortOnIntField));

    SortField intSortOnLongField = new SortField("longField", SortField.Type.INT);
    assertThrows(
        IllegalArgumentException.class,
        () -> searcher.search(new MatchAllDocsQuery(), 1, new Sort(intSortOnLongField)));
    // assert that when sort optimization is disabled we can use INT sort on long field
    intSortOnLongField.setOptimizeSortWithPoints(false);
    searcher.search(new MatchAllDocsQuery(), 1, new Sort(intSortOnLongField));

    SortField intSortOnIntRangeField = new SortField("intRange", SortField.Type.INT);
    assertThrows(
        IllegalArgumentException.class,
        () -> searcher.search(new MatchAllDocsQuery(), 1, new Sort(intSortOnIntRangeField)));
    // assert that when sort optimization is disabled we can use INT sort on intRange field
    intSortOnIntRangeField.setOptimizeSortWithPoints(false);
    searcher.search(new MatchAllDocsQuery(), 1, new Sort(intSortOnIntRangeField));

    reader.close();
    dir.close();
  }

  public void testMaxDocVisited() throws IOException {
    Directory dir = newDirectory();
    IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    int numDocs = atLeast(10000);
    long offset = 100 + random().nextInt(100);
    long smallestValue = 50 + random().nextInt(50);
    boolean flushed = false;
    for (int i = 0; i < numDocs; ++i) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("my_field", i + offset));
      doc.add(new LongPoint("my_field", i + offset));
      writer.addDocument(doc);
      if (i >= 5000 && flushed == false) {
        flushed = true;
        writer.flush();
        // Index the smallest value to the first slot of the second segment
        doc = new Document();
        doc.add(new NumericDocValuesField("my_field", smallestValue));
        doc.add(new LongPoint("my_field", smallestValue));
        writer.addDocument(doc);
      }
    }
    IndexReader reader = DirectoryReader.open(writer);
    writer.close();
    IndexSearcher searcher = new IndexSearcher(reader);
    SortField sortField = new SortField("my_field", SortField.Type.LONG);
    TopFieldDocs topDocs =
        searcher.search(new MatchAllDocsQuery(), 1 + random().nextInt(100), new Sort(sortField));
    FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[0];
    assertEquals(smallestValue, ((Long) fieldDoc.fields[0]).intValue());
    reader.close();
    dir.close();
  }

  public void testRandomLong() throws IOException {
    Directory dir = newDirectory();
    IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
    List<Long> seqNos = LongStream.range(0, atLeast(10_000)).boxed().collect(Collectors.toList());
    Collections.shuffle(seqNos, random());
    int pendingDocs = 0;
    for (long seqNo : seqNos) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("seq_no", seqNo));
      doc.add(new LongPoint("seq_no", seqNo));
      writer.addDocument(doc);
      pendingDocs++;
      if (pendingDocs > 500 && random().nextInt(100) <= 5) {
        pendingDocs = 0;
        writer.flush();
      }
    }
    writer.flush();
    seqNos.sort(Long::compare);
    IndexReader reader = DirectoryReader.open(writer);
    writer.close();
    IndexSearcher searcher = new IndexSearcher(reader);
    SortField sortField = new SortField("seq_no", SortField.Type.LONG);
    int visitedHits = 0;
    ScoreDoc after = null;
    while (visitedHits < seqNos.size()) {
      int batch = 1 + random().nextInt(100);
      Query query =
          random().nextBoolean()
              ? new MatchAllDocsQuery()
              : LongPoint.newRangeQuery("seq_no", 0, Long.MAX_VALUE);
      TopDocs topDocs = searcher.searchAfter(after, query, batch, new Sort(sortField));
      int expectedHits = Math.min(seqNos.size() - visitedHits, batch);
      assertEquals(expectedHits, topDocs.scoreDocs.length);
      after = topDocs.scoreDocs[expectedHits - 1];
      for (int i = 0; i < topDocs.scoreDocs.length; i++) {
        FieldDoc fieldDoc = (FieldDoc) topDocs.scoreDocs[i];
        long expectedSeqNo = seqNos.get(visitedHits);
        assertEquals(expectedSeqNo, ((Long) fieldDoc.fields[0]).intValue());
        visitedHits++;
      }
    }
    reader.close();
    dir.close();
  }
}
