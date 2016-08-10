/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.controller;


import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.ObjectObjectHashMap;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.HasContextAndHeaders;
import org.elasticsearch.common.collect.HppcMaps;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.SiblingPipelineAggregator;
import org.elasticsearch.search.dfs.AggregatedDfs;
import org.elasticsearch.search.dfs.DfsSearchResult;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.FetchSearchResultProvider;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.profile.InternalProfileShardResults;
import org.elasticsearch.search.profile.ProfileShardResult;
import org.elasticsearch.search.query.QuerySearchResult;
import org.elasticsearch.search.query.QuerySearchResultProvider;
import org.elasticsearch.search.suggest.Suggest;

import java.io.IOException;
import java.util.*;

import static org.elasticsearch.common.util.CollectionUtils.eagerTransform;
import static org.elasticsearch.search.controller.SearchPhaseController.EMPTY_DOCS;
import static org.elasticsearch.search.controller.SearchPhaseController.QUERY_RESULT_ORDERING;

public class OSearchPhaseController {
    public ScoreDoc[] sortDocs(boolean optimizeSingleShard, boolean ignoreFrom, AtomicArray<? extends QuerySearchResultProvider> resultsArr) throws IOException {
        List<? extends AtomicArray.Entry<? extends QuerySearchResultProvider>> results = resultsArr.asList();
        if (results.isEmpty()) {
            return EMPTY_DOCS;
        }

        if (optimizeSingleShard) {
            boolean canOptimize = false;
            QuerySearchResult result = null;
            int shardIndex = -1;
            if (results.size() == 1) {
                canOptimize = true;
                result = results.get(0).value.queryResult();
                shardIndex = results.get(0).index;
            } else {
                // lets see if we only got hits from a single shard, if so, we can optimize...
                for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : results) {
                    if (entry.value.queryResult().topDocs().scoreDocs.length > 0) {
                        if (result != null) { // we already have one, can't really optimize
                            canOptimize = false;
                            break;
                        }
                        canOptimize = true;
                        result = entry.value.queryResult();
                        shardIndex = entry.index;
                    }
                }
            }
            if (canOptimize) {
                int offset = result.from();
                if (ignoreFrom) {
                    offset = 0;
                }
                ScoreDoc[] scoreDocs = result.topDocs().scoreDocs;
                if (scoreDocs.length == 0 || scoreDocs.length < offset) {
                    return EMPTY_DOCS;
                }

                int resultDocsSize = result.size();
                if ((scoreDocs.length - offset) < resultDocsSize) {
                    resultDocsSize = scoreDocs.length - offset;
                }
                ScoreDoc[] docs = new ScoreDoc[resultDocsSize];
                for (int i = 0; i < resultDocsSize; i++) {
                    ScoreDoc scoreDoc = scoreDocs[offset + i];
                    scoreDoc.shardIndex = shardIndex;
                    docs[i] = scoreDoc;
                }
                return docs;
            }
        }

        @SuppressWarnings("unchecked")
        AtomicArray.Entry<? extends QuerySearchResultProvider>[] sortedResults = results.toArray(new AtomicArray.Entry[results.size()]);
        Arrays.sort(sortedResults, QUERY_RESULT_ORDERING);
        QuerySearchResultProvider firstResult = sortedResults[0].value;

        int topN = firstResult.queryResult().size();
        if (firstResult.includeFetch()) {
            // if we did both query and fetch on the same go, we have fetched all the docs from each shards already, use them...
            // this is also important since we shortcut and fetch only docs from "from" and up to "size"
            topN *= sortedResults.length;
        }

        int from = firstResult.queryResult().from();
        if (ignoreFrom) {
            from = 0;
        }

        final TopDocs mergedTopDocs;
        if (firstResult.queryResult().topDocs() instanceof TopFieldDocs) {
            TopFieldDocs firstTopDocs = (TopFieldDocs) firstResult.queryResult().topDocs();
            final Sort sort = new Sort(firstTopDocs.fields);

            final TopFieldDocs[] shardTopDocs = new TopFieldDocs[resultsArr.length()];
            for (AtomicArray.Entry<? extends QuerySearchResultProvider> sortedResult : sortedResults) {
                TopDocs topDocs = sortedResult.value.queryResult().topDocs();
                // the 'index' field is the position in the resultsArr atomic array
                shardTopDocs[sortedResult.index] = (TopFieldDocs) topDocs;
            }
            // TopDocs#merge can't deal with null shard TopDocs
            for (int i = 0; i < shardTopDocs.length; ++i) {
                if (shardTopDocs[i] == null) {
                    shardTopDocs[i] = new TopFieldDocs(0, new FieldDoc[0], sort.getSort(), Float.NaN);
                }
            }
            mergedTopDocs = TopDocs.merge(sort, from, topN, shardTopDocs);
        } else {
            final TopDocs[] shardTopDocs = new TopDocs[resultsArr.length()];
            for (AtomicArray.Entry<? extends QuerySearchResultProvider> sortedResult : sortedResults) {
                TopDocs topDocs = sortedResult.value.queryResult().topDocs();
                // the 'index' field is the position in the resultsArr atomic array
                shardTopDocs[sortedResult.index] = topDocs;
            }
            // TopDocs#merge can't deal with null shard TopDocs
            for (int i = 0; i < shardTopDocs.length; ++i) {
                if (shardTopDocs[i] == null) {
                    shardTopDocs[i] = Lucene.EMPTY_TOP_DOCS;
                }
            }
            mergedTopDocs = TopDocs.merge(from, topN, shardTopDocs);
        }
        return mergedTopDocs.scoreDocs;
    }

    public InternalSearchHits merge(ScoreDoc[] sortedDocs, AtomicArray<? extends QuerySearchResultProvider> queryResultsArr,
                                        AtomicArray<? extends FetchSearchResultProvider> fetchResultsArr, HasContextAndHeaders headersContext) {

        List<? extends AtomicArray.Entry<? extends QuerySearchResultProvider>> queryResults = queryResultsArr.asList();
        List<? extends AtomicArray.Entry<? extends FetchSearchResultProvider>> fetchResults = fetchResultsArr.asList();


        QuerySearchResult firstResult = queryResults.get(0).value.queryResult();

        boolean sorted = false;
        int sortScoreIndex = -1;
        if (firstResult.topDocs() instanceof TopFieldDocs) {
            sorted = true;
            TopFieldDocs fieldDocs = (TopFieldDocs) firstResult.queryResult().topDocs();
            for (int i = 0; i < fieldDocs.fields.length; i++) {
                if (fieldDocs.fields[i].getType() == SortField.Type.SCORE) {
                    sortScoreIndex = i;
                }
            }
        }

        // count the total (we use the query result provider here, since we might not get any hits (we scrolled past them))
        long totalHits = 0;
        float maxScore = Float.NEGATIVE_INFINITY;

        Boolean terminatedEarly = null;
        for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
            QuerySearchResult result = entry.value.queryResult();

            if (result.terminatedEarly() != null) {
                if (terminatedEarly == null) {
                    terminatedEarly = result.terminatedEarly();
                } else if (result.terminatedEarly()) {
                    terminatedEarly = true;
                }
            }
            totalHits += result.topDocs().totalHits;
            if (!Float.isNaN(result.topDocs().getMaxScore())) {
                maxScore = Math.max(maxScore, result.topDocs().getMaxScore());
            }
        }
        if (Float.isInfinite(maxScore)) {
            maxScore = Float.NaN;
        }

        // clean the fetch counter
        for (AtomicArray.Entry<? extends FetchSearchResultProvider> entry : fetchResults) {
            entry.value.fetchResult().initCounter();
        }

        // merge hits
        List<InternalSearchHit> hits = new ArrayList<>();
        if (!fetchResults.isEmpty()) {
            for (ScoreDoc shardDoc : sortedDocs) {
                FetchSearchResultProvider fetchResultProvider = fetchResultsArr.get(shardDoc.shardIndex);
                if (fetchResultProvider == null) {
                    continue;
                }
                FetchSearchResult fetchResult = fetchResultProvider.fetchResult();
                int index = fetchResult.counterGetAndIncrement();
                if (index < fetchResult.hits().internalHits().length) {
                    InternalSearchHit searchHit = fetchResult.hits().internalHits()[index];
                    searchHit.score(shardDoc.score);
                    searchHit.shard(fetchResult.shardTarget());

                    if (sorted) {
                        FieldDoc fieldDoc = (FieldDoc) shardDoc;
                        searchHit.sortValues(fieldDoc.fields);
                        if (sortScoreIndex != -1) {
                            searchHit.score(((Number) fieldDoc.fields[sortScoreIndex]).floatValue());
                        }
                    }

                    hits.add(searchHit);
                }
            }
        }


        InternalSearchHits searchHits = new InternalSearchHits(hits.toArray(new InternalSearchHit[hits.size()]), totalHits, maxScore);

        return searchHits;
    }

}
