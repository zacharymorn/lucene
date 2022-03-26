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
import java.util.Objects;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.Terms;

/**
 * A {@link Query} that matches documents that have a value for a given field as reported by doc
 * values iterators.
 *
 * @deprecated Use {@link org.apache.lucene.search.FieldExistsQuery} instead.
 */
@Deprecated
public final class DocValuesFieldExistsQuery extends FieldExistsQuery {
  private String field;

  /** Create a query that will match documents which have a value for the given {@code field}. */
  public DocValuesFieldExistsQuery(String field) {
    super(field);
    this.field = Objects.requireNonNull(field);
  }

  // nocommit this seems to be generalizable to norms and knn as well given LUCENE-9334, and thus
  // could be moved to the new FieldExistsQuery?
  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    boolean allReadersRewritable = true;
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      Terms terms = leaf.terms(field);
      PointValues pointValues = leaf.getPointValues(field);
      if ((terms == null || terms.getDocCount() != leaf.maxDoc())
          && (pointValues == null || pointValues.getDocCount() != leaf.maxDoc())) {
        allReadersRewritable = false;
        break;
      }
    }
    if (allReadersRewritable) {
      return new MatchAllDocsQuery();
    }
    return super.rewrite(reader);
  }

  /**
   * Returns a {@link DocIdSetIterator} from the given field or null if the field doesn't exist in
   * the reader or if the reader has no doc values for the field.
   */
  public static DocIdSetIterator getDocValuesDocIdSetIterator(String field, LeafReader reader)
      throws IOException {
    FieldInfo fieldInfo = reader.getFieldInfos().fieldInfo(field);
    final DocIdSetIterator iterator;
    if (fieldInfo != null) {
      switch (fieldInfo.getDocValuesType()) {
        case NONE:
          iterator = null;
          break;
        case NUMERIC:
          iterator = reader.getNumericDocValues(field);
          break;
        case BINARY:
          iterator = reader.getBinaryDocValues(field);
          break;
        case SORTED:
          iterator = reader.getSortedDocValues(field);
          break;
        case SORTED_NUMERIC:
          iterator = reader.getSortedNumericDocValues(field);
          break;
        case SORTED_SET:
          iterator = reader.getSortedSetDocValues(field);
          break;
        default:
          throw new AssertionError();
      }
      return iterator;
    }
    return null;
  }
}
