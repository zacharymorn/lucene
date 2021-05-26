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
package org.apache.lucene.index;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.lucene.analysis.CannedTokenStream;
import org.apache.lucene.analysis.Token;
import org.apache.lucene.document.*;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.TestUtil;
import org.apache.lucene.util.VectorUtil;
import org.junit.Test;

public class TestCheckIndex extends BaseTestCheckIndex {
  private Directory directory;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    directory = newDirectory();
  }

  @Override
  public void tearDown() throws Exception {
    directory.close();
    super.tearDown();
  }

  @Test
  public void testDeletedDocs() throws IOException {
    testDeletedDocs(directory);
  }

  @Test
  public void testChecksumsOnly() throws IOException {
    testChecksumsOnly(directory);
  }

  @Test
  public void testChecksumsOnlyVerbose() throws IOException {
    testChecksumsOnlyVerbose(directory);
  }

  @Test
  public void testObtainsLock() throws IOException {
    testObtainsLock(directory);
  }

  @Test
  public void testCheckIndexAllValid() throws Exception {
    try (Directory dir = newDirectory()) {
      int liveDocCount = 1 + random().nextInt(10);
      IndexWriterConfig conifg = newIndexWriterConfig();
      conifg.setIndexSort(new Sort(new SortField("sort_field", SortField.Type.INT, true)));
      conifg.setSoftDeletesField("soft_delete");
      try (IndexWriter w = new IndexWriter(dir, conifg)) {
        for (int i = 0; i < liveDocCount; i++) {
          Document doc = new Document();

          // stored field
          doc.add(new StringField("id", Integer.toString(random().nextInt()), Field.Store.YES));
          doc.add(new StoredField("field", "value" + TestUtil.randomSimpleString(random())));

          // vector
          doc.add(new VectorField("v1", randomVector(3), VectorValues.SimilarityFunction.NONE));
          doc.add(new VectorField("v2", randomVector(3), VectorValues.SimilarityFunction.NONE));

          // doc value
          doc.add(new NumericDocValuesField("dv", random().nextLong()));

          // point value
          byte[] point = new byte[4];
          NumericUtils.intToSortableBytes(random().nextInt(), point, 0);
          doc.add(new BinaryPoint("point", point));

          // term vector
          Token token1 =
              new Token("bar", 0, 3) {
                {
                  setPayload(new BytesRef("pay1"));
                }
              };
          Token token2 =
              new Token("bar", 4, 8) {
                {
                  setPayload(new BytesRef("pay2"));
                }
              };
          FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
          ft.setStoreTermVectors(true);
          ft.setStoreTermVectorPositions(true);
          ft.setStoreTermVectorPayloads(true);
          doc.add(new Field("termvector", new CannedTokenStream(token1, token2), ft));

          w.addDocument(doc);
        }

        Document tombstone = new Document();
        tombstone.add(new NumericDocValuesField("soft_delete", 1));
        w.softUpdateDocument(
            new Term("id", "1"), tombstone, new NumericDocValuesField("soft_delete", 1));
      }

      ByteArrayOutputStream output = new ByteArrayOutputStream();
      CheckIndex.Status status = TestUtil.checkIndex(dir, false, true, output);

      assertEquals(1, status.segmentInfos.size());

      String segmentId = "[Segment _0]";
      CheckIndex.Status.SegmentInfoStatus segStatus = status.segmentInfos.get(0);

      // confirm live docs testing status
      assertEquals(0, segStatus.liveDocStatus.numDeleted);
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[LiveDocs]"));
      assertNull(segStatus.liveDocStatus.error);

      // confirm field infos testing status
      assertEquals(8, segStatus.fieldInfoStatus.totFields);
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[FieldInfos]"));
      assertNull(segStatus.fieldInfoStatus.error);

      // confirm field norm (from term vector) testing status
      assertEquals(1, segStatus.fieldNormStatus.totFields);
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[FieldNorms]"));
      assertNull(segStatus.fieldNormStatus.error);

      // confirm term index testing status
      assertTrue(segStatus.termIndexStatus.termCount > 0);
      assertTrue(segStatus.termIndexStatus.totFreq > 0);
      assertTrue(segStatus.termIndexStatus.totPos > 0);
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[Postings]"));
      assertNull(segStatus.termIndexStatus.error);

      // confirm stored field testing status
      // add storedField from tombstone doc
      assertEquals(liveDocCount + 1, segStatus.storedFieldStatus.docCount);
      assertEquals(2 * liveDocCount, segStatus.storedFieldStatus.totFields);
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[StoredFields]"));
      assertNull(segStatus.storedFieldStatus.error);

      // confirm term vector testing status
      assertEquals(liveDocCount, segStatus.termVectorStatus.docCount);
      assertEquals(liveDocCount, segStatus.termVectorStatus.totVectors);
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[TermVectors]"));
      assertNull(segStatus.termVectorStatus.error);

      // confirm doc values testing status
      assertEquals(2, segStatus.docValuesStatus.totalNumericFields);
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[DocValues]"));
      assertNull(segStatus.docValuesStatus.error);

      // confirm point values testing status
      assertEquals(1, segStatus.pointsStatus.totalValueFields);
      assertEquals(liveDocCount, segStatus.pointsStatus.totalValuePoints);
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[Points]"));
      assertNull(segStatus.pointsStatus.error);

      // confirm vector testing status
      assertEquals(2 * liveDocCount, segStatus.vectorValuesStatus.totalVectorValues);
      assertEquals(2, segStatus.vectorValuesStatus.totalVectorFields);
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[Vectors]"));
      assertNull(segStatus.vectorValuesStatus.error);

      // confirm index sort testing status
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId + "[Sort]"));
      assertNull(segStatus.indexSortStatus.error);

      // confirm soft deletes testing status
      assertTrue(output.toString(IOUtils.UTF_8).contains(segmentId));
      assertTrue(output.toString(IOUtils.UTF_8).contains("test: check soft deletes"));
      assertNull(segStatus.softDeletesStatus.error);
    }
  }

  private float[] randomVector(int dim) {
    float[] v = new float[dim];
    for (int i = 0; i < dim; i++) {
      v[i] = random().nextFloat();
    }
    VectorUtil.l2normalize(v);
    return v;
  }
}
