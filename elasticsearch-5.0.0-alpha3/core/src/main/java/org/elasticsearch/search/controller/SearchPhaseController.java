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
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.HppcMaps;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.AtomicArray;

import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregation.ReduceContext;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.pipeline.SiblingPipelineAggregator;
import org.elasticsearch.search.dfs.AggregatedDfs;
import org.elasticsearch.search.dfs.DfsSearchResult;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.FetchSearchResultProvider;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.profile.SearchProfileShardResults;
import org.elasticsearch.search.profile.ProfileShardResult;
import org.elasticsearch.search.query.QuerySearchResult;
import org.elasticsearch.search.query.QuerySearchResultProvider;
import org.elasticsearch.search.suggest.Suggest;


import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;



/**
 *
 */
public class SearchPhaseController extends AbstractComponent {


    public static final Comparator<AtomicArray.Entry<? extends QuerySearchResultProvider>> QUERY_RESULT_ORDERING = new Comparator<AtomicArray.Entry<? extends QuerySearchResultProvider>>() {
        @Override
        public int compare(AtomicArray.Entry<? extends QuerySearchResultProvider> o1, AtomicArray.Entry<? extends QuerySearchResultProvider> o2) {
            int i = o1.value.shardTarget().index().compareTo(o2.value.shardTarget().index());
            if (i == 0) {
                i = o1.value.shardTarget().shardId().id() - o2.value.shardTarget().shardId().id();
            }
            return i;
        }
    };

    public static final ScoreDoc[] EMPTY_DOCS = new ScoreDoc[0];

    private final BigArrays bigArrays;
    private final ScriptService scriptService;
    private final ClusterService clusterService;

    @Inject
    public SearchPhaseController(Settings settings, BigArrays bigArrays, ScriptService scriptService, ClusterService clusterService) {
        super(settings);
        this.bigArrays = bigArrays;
        this.scriptService = scriptService;
        this.clusterService = clusterService;
    }

    public AggregatedDfs aggregateDfs(AtomicArray<DfsSearchResult> results) {
        ObjectObjectHashMap<Term, TermStatistics> termStatistics = HppcMaps.newNoNullKeysMap();
        ObjectObjectHashMap<String, CollectionStatistics> fieldStatistics = HppcMaps.newNoNullKeysMap();
        long aggMaxDoc = 0;
        for (AtomicArray.Entry<DfsSearchResult> lEntry : results.asList()) {
            final Term[] terms = lEntry.value.terms();
            final TermStatistics[] stats = lEntry.value.termStatistics();
            assert terms.length == stats.length;
            for (int i = 0; i < terms.length; i++) {
                assert terms[i] != null;
                TermStatistics existing = termStatistics.get(terms[i]);
                if (existing != null) {
                    assert terms[i].bytes().equals(existing.term());
                    // totalTermFrequency is an optional statistic we need to check if either one or both
                    // are set to -1 which means not present and then set it globally to -1
                    termStatistics.put(terms[i], new TermStatistics(existing.term(),
                        existing.docFreq() + stats[i].docFreq(),
                        optionalSum(existing.totalTermFreq(), stats[i].totalTermFreq())));
                } else {
                    termStatistics.put(terms[i], stats[i]);
                }

            }

            assert !lEntry.value.fieldStatistics().containsKey(null);
            final Object[] keys = lEntry.value.fieldStatistics().keys;
            final Object[] values = lEntry.value.fieldStatistics().values;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != null) {
                    String key = (String) keys[i];
                    CollectionStatistics value = (CollectionStatistics) values[i];
                    assert key != null;
                    CollectionStatistics existing = fieldStatistics.get(key);
                    if (existing != null) {
                        CollectionStatistics merged = new CollectionStatistics(
                            key, existing.maxDoc() + value.maxDoc(),
                            optionalSum(existing.docCount(), value.docCount()),
                            optionalSum(existing.sumTotalTermFreq(), value.sumTotalTermFreq()),
                            optionalSum(existing.sumDocFreq(), value.sumDocFreq())
                        );
                        fieldStatistics.put(key, merged);
                    } else {
                        fieldStatistics.put(key, value);
                    }
                }
            }
            aggMaxDoc += lEntry.value.maxDoc();
        }
        return new AggregatedDfs(termStatistics, fieldStatistics, aggMaxDoc);
    }

    private static long optionalSum(long left, long right) {
        return Math.min(left, right) == -1 ? -1 : left + right;
    }

    /**
     * @param ignoreFrom Whether to ignore the from and sort all hits in each shard result.
     *                   Enabled only for scroll search, because that only retrieves hits of length 'size' in the query phase.
     * @param resultsArr Shard result holder
     */
    public ScoreDoc[] sortDocs(boolean ignoreFrom, AtomicArray<? extends QuerySearchResultProvider> resultsArr) throws IOException {
        List<? extends AtomicArray.Entry<? extends QuerySearchResultProvider>> results = resultsArr.asList();
        if (results.isEmpty()) {
            return EMPTY_DOCS;
        }

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

        @SuppressWarnings("unchecked")
        AtomicArray.Entry<? extends QuerySearchResultProvider>[] sortedResults = results.toArray(new AtomicArray.Entry[results.size()]);
        Arrays.sort(sortedResults, QUERY_RESULT_ORDERING);
        QuerySearchResultProvider firstResult = sortedResults[0].value;


        int topN;

        if(!firstResult.queryResult().osc_ua().equals("false")){
            topN = firstResult.queryResult().size() +firstResult.queryResult().from()+1000;
        }
        else{
            topN = firstResult.queryResult().size() +firstResult.queryResult().from();
        }

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

    public ScoreDoc[] getLastEmittedDocPerShard(SearchRequest request, ScoreDoc[] sortedShardList, int numShards) {
        if (request.scroll() != null) {
            return getLastEmittedDocPerShard(sortedShardList, numShards);
        } else {
            return null;
        }
    }

    public ScoreDoc[] getLastEmittedDocPerShard(ScoreDoc[] sortedShardList, int numShards) {
        ScoreDoc[] lastEmittedDocPerShard = new ScoreDoc[numShards];
        for (ScoreDoc scoreDoc : sortedShardList) {
            lastEmittedDocPerShard[scoreDoc.shardIndex] = scoreDoc;
        }
        return lastEmittedDocPerShard;
    }

    /**
     * Builds an array, with potential null elements, with docs to load.
     */
    public void fillDocIdsToLoad(AtomicArray<IntArrayList> docsIdsToLoad, ScoreDoc[] shardDocs) {
        for (ScoreDoc shardDoc : shardDocs) {
            IntArrayList list = docsIdsToLoad.get(shardDoc.shardIndex);
            if (list == null) {
                list = new IntArrayList(); // can't be shared!, uses unsafe on it later on
                docsIdsToLoad.set(shardDoc.shardIndex, list);
            }
            list.add(shardDoc.doc);
        }
    }

    public InternalSearchResponse merge(ScoreDoc[] sortedDocs, AtomicArray<? extends QuerySearchResultProvider> queryResultsArr,
                                        AtomicArray<? extends FetchSearchResultProvider> fetchResultsArr) {

        List<? extends AtomicArray.Entry<? extends QuerySearchResultProvider>> queryResults = queryResultsArr.asList();
        List<? extends AtomicArray.Entry<? extends FetchSearchResultProvider>> fetchResults = fetchResultsArr.asList();

        if (queryResults.isEmpty()) {
            return InternalSearchResponse.empty();
        }

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
        boolean timedOut = false;
        Boolean terminatedEarly = null;
        for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
            QuerySearchResult result = entry.value.queryResult();
            if (result.searchTimedOut()) {
                timedOut = true;
            }
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


        if(!firstResult.queryResult().osc_ua().equals("false"))
        {
            String skus[] = new String[firstResult.queryResult().size()];
            String query = ("{\"from\":").concat((String.valueOf(firstResult.queryResult().from()).concat(((",\"size\":").concat((String.valueOf(firstResult.queryResult().size()).concat(",\"results\":[")))))));

            int counter = -1;
            int count = 0;
            int start = firstResult.queryResult().from();
            int stop = firstResult.queryResult().size()+firstResult.queryResult().from();
            StringBuilder ss = new StringBuilder();


            List<InternalSearchHit> hits = new ArrayList<>();


                if (!fetchResults.isEmpty()) {
                    for (ScoreDoc shardDoc : sortedDocs) {

                        counter++;
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
                                searchHit.sortValues(fieldDoc.fields, firstResult.sortValueFormats());
                                if (sortScoreIndex != -1) {
                                    searchHit.score(((Number) fieldDoc.fields[sortScoreIndex]).floatValue());
                                }
                            }

                            if (counter < stop && counter >= start) {
    //                        hits2.add(searchHit);
                                skus[count] = searchHit.getId();
                                count++;
                            }

                            hits.add(searchHit);

                            ss = ss.append(("{\"SKU\":").concat((searchHit.getId()).concat((",\"score\":").concat((String.valueOf(searchHit.getScore()).concat("},"))))));

                        }
                    }
                }

                query = query.concat(ss.toString());

                if (query != null && query.length() > 0 && query.charAt(query.length() - 1) == ',') {
                    query = query.substring(0, query.length() - 1);
                }

                query = query.concat("]}");

                //String body = Strings.toString(new InternalSearchHits(hits.toArray(new InternalSearchHit[hits.size()]), totalHits, maxScore),true);


                // merge suggest results
                Suggest suggest = null;
                if (!queryResults.isEmpty()) {
                    final Map<String, List<Suggest.Suggestion>> groupedSuggestions = new HashMap<>();
                    boolean hasSuggestions = false;
                    for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
                        Suggest shardResult = entry.value.queryResult().queryResult().suggest();

                        if (shardResult == null) {
                            continue;
                        }
                        hasSuggestions = true;
                        Suggest.group(groupedSuggestions, shardResult);
                    }

                    suggest = hasSuggestions ? new Suggest(Suggest.reduce(groupedSuggestions)) : null;
                }

                // merge addAggregation
                InternalAggregations aggregations = null;
                if (!queryResults.isEmpty()) {
                    if (firstResult.aggregations() != null && firstResult.aggregations().asList() != null) {
                        List<InternalAggregations> aggregationsList = new ArrayList<>(queryResults.size());
                        for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
                            aggregationsList.add((InternalAggregations) entry.value.queryResult().aggregations());
                        }
                        ReduceContext reduceContext = new ReduceContext(bigArrays, scriptService, clusterService.state());
                        aggregations = InternalAggregations.reduce(aggregationsList, reduceContext);
                    }
                }

                //Collect profile results
                SearchProfileShardResults shardResults = null;
                if (!queryResults.isEmpty() && firstResult.profileResults() != null) {
                    Map<String, List<ProfileShardResult>> profileResults = new HashMap<>(queryResults.size());
                    for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
                        String key = entry.value.queryResult().shardTarget().toString();
                        profileResults.put(key, entry.value.queryResult().profileResults());
                    }
                    shardResults = new SearchProfileShardResults(profileResults);
                }

                if (aggregations != null) {
                    List<SiblingPipelineAggregator> pipelineAggregators = firstResult.pipelineAggregators();
                    if (pipelineAggregators != null) {
                        List<InternalAggregation> newAggs = StreamSupport.stream(aggregations.spliterator(), false).map((p) -> {
                            return (InternalAggregation) p;
                        }).collect(Collectors.toList());
                        for (SiblingPipelineAggregator pipelineAggregator : pipelineAggregators) {
                            ReduceContext reduceContext = new ReduceContext(bigArrays, scriptService, clusterService.state());
                            InternalAggregation newAgg = pipelineAggregator.doReduce(new InternalAggregations(newAggs), reduceContext);
                            newAggs.add(newAgg);
                        }
                        aggregations = new InternalAggregations(newAggs);
                    }
                }


                String response = callURL("http://odoscope.cloud/praxisdienst.de/decisions?maxItems=20&token=XfRqxAgR7XwGW3qzmj2Mxw&categoryID=2dd41e75a881635aa52be72e7f6d2eeb&prodId=421109&hostname=praxisdienst.de");
    //       String response = callURL("http://www.google.com");

    //        String response = "{\n" +
    //            "  \"variants\": [\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"311201\",\n" +
    //            "    \"score\": 18879\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"311101\",\n" +
    //            "    \"score\": 5275\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"421100\",\n" +
    //            "    \"score\": 5059\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"131185\",\n" +
    //            "    \"score\": 4344\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"602535\",\n" +
    //            "    \"score\": 4187\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"425115\",\n" +
    //            "    \"score\": 3992\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"602272\",\n" +
    //            "    \"score\": 3969\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"602221\",\n" +
    //            "    \"score\": 3840\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"602540\",\n" +
    //            "    \"score\": 3823\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"350000\",\n" +
    //            "    \"score\": 3771\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"360250\",\n" +
    //            "    \"score\": 3439\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"312101\",\n" +
    //            "    \"score\": 3359\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"130400\",\n" +
    //            "    \"score\": 3332\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"603034\",\n" +
    //            "    \"score\": 3294\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"430105blau\",\n" +
    //            "    \"score\": 3286\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"425100\",\n" +
    //            "    \"score\": 3092\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"126022\",\n" +
    //            "    \"score\": 3006\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"318401\",\n" +
    //            "    \"score\": 2782\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"314101\",\n" +
    //            "    \"score\": 2570\n" +
    //            "   },\n" +
    //            "   {\n" +
    //            "    \"variantID\": \"350600\",\n" +
    //            "    \"score\": 2483\n" +
    //            "   }\n" +
    //            "  ],\n" +
    //            "  \"responseStatus\": {\n" +
    //            "   \"responseCode\": 200,\n" +
    //            "   \"responseMessage\": \"\"\n" +
    //            "  }\n" +
    //            " }";


                String delims = "[,.?/|\n}{\" /:\" ]+";
                String[] tokens = response.split(delims);

                String skids1[] = new String[20];
                String skore1[] = new String[20];

                int k;
                for (k = 0; k < tokens.length - 1; k++) {
                    if (tokens[k].equals("variantID"))
                        break;
                }

                int ct = 0;

                while (!(tokens[k].equals("]"))) {
                    skids1[ct] = tokens[k + 1];
                    skore1[ct] = tokens[k + 3];
                    ct++;
                    k = k + 4;
                }

    //        String skids[] = new String[20];
    //        String skore[] = new String[20];
    //
    //        for(int j=0;j<20;j++)
    //        {
    //            skids[j]="";
    //            skore[j]="";
    //
    //        }
    //
    //        int i =0;
    //        int tt = 0;
    //        int end  = response.length()-90;
    //        boolean found = false;
    //        while(i<end)
    //        {
    //            if((response.substring(i, i+11)).equals("\"variantID\""))
    //            {
    //                int t=i+14;
    //                while(response.charAt(t)!='"')
    //                {
    //                    skids[tt] = skids[tt]+response.charAt(t);
    //                    t++;
    //                }
    //                i = i+27;
    //                found = true;
    //            }
    //            if(found)
    //            {
    //                if ((response.substring(i, i + 7)).equals("\"score\"")) {
    //                    int t = i + 9;
    //                    while (response.charAt(t) != '\n') {
    //                        skore[tt] = skore[tt] + response.charAt(t);
    //                        t++;
    //                    }
    //                    tt++;
    //                    i = i + 20;
    //                }
    //            }
    //            i++;
    //        }


                InternalSearchHit finalhits[] = hits.toArray(new InternalSearchHit[hits.size()]);
                InternalSearchHit finalfinalhits[] = new InternalSearchHit[firstResult.queryResult().size()];
                count = 0;

                for (int itr = hits.size() - 1; itr > -1; itr--) {
                    if (count == (firstResult.queryResult().size())) {
                        break;
                    }

                    if ((finalhits[itr].getId()).equals(skus[count])) {

                        finalhits[itr].score(Float.valueOf(skore1[count]));
                        finalfinalhits[count] = finalhits[itr];
                        count++;
                        itr = hits.size() - 1;
                    }
                }

                InternalSearchHits searchHits = new InternalSearchHits(finalfinalhits, totalHits, maxScore);

                return new InternalSearchResponse(searchHits, aggregations, suggest, shardResults, timedOut, terminatedEarly);
        }
        else
        {
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
                            searchHit.sortValues(fieldDoc.fields, firstResult.sortValueFormats());
                            if (sortScoreIndex != -1) {
                                searchHit.score(((Number) fieldDoc.fields[sortScoreIndex]).floatValue());
                            }
                        }

                        hits.add(searchHit);
                    }
                }
            }

            // merge suggest results
            Suggest suggest = null;
            if (!queryResults.isEmpty()) {
                final Map<String, List<Suggest.Suggestion>> groupedSuggestions = new HashMap<>();
                boolean hasSuggestions = false;
                for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
                    Suggest shardResult = entry.value.queryResult().queryResult().suggest();

                    if (shardResult == null) {
                        continue;
                    }
                    hasSuggestions = true;
                    Suggest.group(groupedSuggestions, shardResult);
                }

                suggest = hasSuggestions ? new Suggest(Suggest.reduce(groupedSuggestions)) : null;
            }

            // merge addAggregation
            InternalAggregations aggregations = null;
            if (!queryResults.isEmpty()) {
                if (firstResult.aggregations() != null && firstResult.aggregations().asList() != null) {
                    List<InternalAggregations> aggregationsList = new ArrayList<>(queryResults.size());
                    for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
                        aggregationsList.add((InternalAggregations) entry.value.queryResult().aggregations());
                    }
                    ReduceContext reduceContext = new ReduceContext(bigArrays, scriptService, clusterService.state());
                    aggregations = InternalAggregations.reduce(aggregationsList, reduceContext);
                }
            }

            //Collect profile results
            SearchProfileShardResults shardResults = null;
            if (!queryResults.isEmpty() && firstResult.profileResults() != null) {
                Map<String, List<ProfileShardResult>> profileResults = new HashMap<>(queryResults.size());
                for (AtomicArray.Entry<? extends QuerySearchResultProvider> entry : queryResults) {
                    String key = entry.value.queryResult().shardTarget().toString();
                    profileResults.put(key, entry.value.queryResult().profileResults());
                }
                shardResults = new SearchProfileShardResults(profileResults);
            }

            if (aggregations != null) {
                List<SiblingPipelineAggregator> pipelineAggregators = firstResult.pipelineAggregators();
                if (pipelineAggregators != null) {
                    List<InternalAggregation> newAggs = StreamSupport.stream(aggregations.spliterator(), false).map((p) -> {
                        return (InternalAggregation) p;
                    }).collect(Collectors.toList());
                    for (SiblingPipelineAggregator pipelineAggregator : pipelineAggregators) {
                        ReduceContext reduceContext = new ReduceContext(bigArrays, scriptService, clusterService.state());
                        InternalAggregation newAgg = pipelineAggregator.doReduce(new InternalAggregations(newAggs), reduceContext);
                        newAggs.add(newAgg);
                    }
                    aggregations = new InternalAggregations(newAggs);
                }
            }

            InternalSearchHits searchHits = new InternalSearchHits(hits.toArray(new InternalSearchHit[hits.size()]), totalHits, maxScore);

            return new InternalSearchResponse(searchHits, aggregations, suggest, shardResults, timedOut, terminatedEarly);
        }



    }


//    private String echoCuties(String query) throws IOException {
//        // Encode the query
//        String encodedQuery = URLEncoder.encode(query, "UTF-8");
//        // This is the data that is going to be send to itcuties.com via POST request
//        // 'e' parameter contains data to echo
//        String postData = "e=" + encodedQuery;
//
//        // Connect to google.com
//        URL url = new URL("http://echo.itcuties.com");
//        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
//        connection.setDoOutput(true);
//        connection.setRequestMethod("POST");
//        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
//        connection.setRequestProperty("Content-Length",  String.valueOf(postData.length()));
//
//        // Write data
//        OutputStream os = connection.getOutputStream();
//        os.write(postData.getBytes());
//
//        // Read response
//        StringBuilder responseSB = new StringBuilder();
//        BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
//
//        String line;
//        while ( (line = br.readLine()) != null)
//            responseSB.append(line);
//
//        // Close streams
//        br.close();
//          os.close();
//
//        return "test";
//
//    }
//

    public static String callURL(String myURL) {

        StringBuilder sb = new StringBuilder();
        URLConnection urlConn = null;
        InputStreamReader in = null;

        try {

            URL url = new URL(myURL);

            urlConn = url.openConnection();
            //urlConn.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:40.0) Gecko/20100101 Firefox/40.1");
//            System.out.println(urlConn.getHeaderFields());


            if (urlConn != null)
                urlConn.setReadTimeout(60 * 1000);

            if (urlConn != null && urlConn.getInputStream() != null)
            {

                in = new InputStreamReader(urlConn.getInputStream(),
                    Charset.defaultCharset());

                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(urlConn.getInputStream(),
                    Charset.defaultCharset()));

                if (bufferedReader != null) {
                    int cp;
                    while ((cp = bufferedReader.read()) != -1) {
                        sb.append((char) cp);
                    }
                    bufferedReader.close();
                }
            }
            in.close();
        } catch (Exception e) {
            throw new RuntimeException("Exception while calling URL:"+ myURL, e);
        }
        return sb.toString();
    }

}
