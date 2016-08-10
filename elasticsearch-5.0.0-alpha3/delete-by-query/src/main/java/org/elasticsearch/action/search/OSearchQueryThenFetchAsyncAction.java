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

package org.elasticsearch.action.search;

import com.carrotsearch.hppc.IntArrayList;
import org.apache.lucene.search.ScoreDoc;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.action.SearchServiceTransportAction;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.controller.OSearchPhaseController;
import org.elasticsearch.search.controller.SearchPhaseController;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.ShardFetchSearchRequest;
import org.elasticsearch.search.internal.InternalSearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.internal.ShardSearchTransportRequest;
import org.elasticsearch.search.profile.InternalProfileShardResults;
import org.elasticsearch.search.profile.ProfileShardResult;
import org.elasticsearch.search.query.QuerySearchResultProvider;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class OSearchQueryThenFetchAsyncAction extends OAbstractSearchAsyncAction<QuerySearchResultProvider> {

    final AtomicArray<FetchSearchResult> fetchResults;
    final AtomicArray<IntArrayList> docIdsToLoad;

    OSearchQueryThenFetchAsyncAction(ESLogger logger, SearchServiceTransportAction searchService,
                                            ClusterService clusterService, IndexNameExpressionResolver indexNameExpressionResolver,
                                            SearchPhaseController searchPhaseController, ThreadPool threadPool,
                                            SearchRequest request, ActionListener<SearchResponse> listener) {
        super(logger, searchService, clusterService, indexNameExpressionResolver, searchPhaseController, threadPool, request, listener);
        fetchResults = new AtomicArray<>(firstResults.length());
        docIdsToLoad = new AtomicArray<>(firstResults.length());
    }

    @Override
    protected String firstPhaseName() {
        return "query";
    }

    @Override
    protected void sendExecuteFirstPhase(DiscoveryNode node, ShardSearchTransportRequest request,
                                         ActionListener<QuerySearchResultProvider> listener) {
        searchService.sendExecuteQuery(node, request, listener);
    }

    @Override
    protected void moveToSecondPhase() throws Exception {
        boolean useScroll = request.scroll() != null;
        boolean oss = searchPhaseController.optimizeSingleShard();
        OSearchPhaseController os = new OSearchPhaseController();

        sortedShardList = os.sortDocs(oss, useScroll, firstResults);
        searchPhaseController.fillDocIdsToLoad(docIdsToLoad, sortedShardList);

        if (docIdsToLoad.asList().isEmpty()) {
            finishHim();
            return;
        }

        final ScoreDoc[] lastEmittedDocPerShard = searchPhaseController.getLastEmittedDocPerShard(
            request, sortedShardList, firstResults.length()
        );
        final AtomicInteger counter = new AtomicInteger(docIdsToLoad.asList().size());
        for (AtomicArray.Entry<IntArrayList> entry : docIdsToLoad.asList()) {
            QuerySearchResultProvider queryResult = firstResults.get(entry.index);
            DiscoveryNode node = nodes.get(queryResult.shardTarget().nodeId());
            ShardFetchSearchRequest fetchSearchRequest = createFetchRequest(queryResult.queryResult(), entry, lastEmittedDocPerShard);
            executeFetch(entry.index, queryResult.shardTarget(), counter, fetchSearchRequest, node);
        }
    }

    void executeFetch(final int shardIndex, final SearchShardTarget shardTarget, final AtomicInteger counter,
                      final ShardFetchSearchRequest fetchSearchRequest, DiscoveryNode node) {
        searchService.sendExecuteFetch(node, fetchSearchRequest, new ActionListener<FetchSearchResult>() {
            @Override
            public void onResponse(FetchSearchResult result) {
                result.shardTarget(shardTarget);
                fetchResults.set(shardIndex, result);
                if (counter.decrementAndGet() == 0) {
                    finishHim();
                }
            }

            @Override
            public void onFailure(Throwable t) {
                // the search context might not be cleared on the node where the fetch was executed for example
                // because the action was rejected by the thread pool. in this case we need to send a dedicated
                // request to clear the search context. by setting docIdsToLoad to null, the context will be cleared
                // in TransportSearchTypeAction.releaseIrrelevantSearchContexts() after the search request is done.
                docIdsToLoad.set(shardIndex, null);
                onFetchFailure(t, fetchSearchRequest, shardIndex, shardTarget, counter);
            }
        });
    }

    void onFetchFailure(Throwable t, ShardFetchSearchRequest fetchSearchRequest, int shardIndex, SearchShardTarget shardTarget,
                        AtomicInteger counter) {
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] Failed to execute fetch phase", t, fetchSearchRequest.id());
        }
        this.addShardFailure(shardIndex, shardTarget, t);
        successfulOps.decrementAndGet();
        if (counter.decrementAndGet() == 0) {
            finishHim();
        }
    }

    private void finishHim() {
        threadPool.executor(ThreadPool.Names.SEARCH).execute(new ActionRunnable<SearchResponse>(listener) {
            @Override
            public void doRun() throws IOException {
                final InternalSearchResponse internalResponse2;
                final InternalSearchResponse internalResponse;
                List<? extends AtomicArray.Entry<? extends QuerySearchResultProvider>> queryResults = firstResults.asList();

                if (!queryResults.isEmpty())
                {
                    internalResponse2 = searchPhaseController.merge(sortedShardList, firstResults,
                        fetchResults, request);

                    InternalAggregations aggregations = (InternalAggregations) internalResponse2.aggregations();

                    OSearchPhaseController o = new OSearchPhaseController();

                    Suggest suggest = internalResponse2.suggest();

                    Map<String, List<ProfileShardResult>> profileResults = new HashMap<>(queryResults.size());
                    profileResults = internalResponse2.profile();
                    InternalProfileShardResults shardResults = new InternalProfileShardResults(profileResults);

                    boolean timedOut = internalResponse2.timedOut();

                    Boolean terminatedEarly = internalResponse2.terminatedEarly();

                    InternalSearchHits hits = o.merge(sortedShardList, firstResults, fetchResults, request);

                    internalResponse = new InternalSearchResponse(hits, aggregations, suggest, shardResults, timedOut, terminatedEarly);


                }
                else
                {
                    internalResponse = InternalSearchResponse.empty();
                }

                logger.debug("after getting extra source");

                String scrollId = null;
                if (request.scroll() != null) {
                    scrollId = TransportSearchHelper.buildScrollId(request.searchType(), firstResults, null);
                }
                listener.onResponse(new SearchResponse(internalResponse, scrollId, expectedSuccessfulOps,
                    successfulOps.get(), buildTookInMillis(), buildShardFailures()));
                releaseIrrelevantSearchContexts(firstResults, docIdsToLoad);
            }

            @Override
            public void onFailure(Throwable t) {
                try {
                    ReduceSearchPhaseException failure = new ReduceSearchPhaseException("fetch", "", t, buildShardFailures());
                    if (logger.isDebugEnabled()) {
                        logger.debug("failed to reduce search", failure);
                    }
                    super.onFailure(failure);
                } finally {
                    releaseIrrelevantSearchContexts(firstResults, docIdsToLoad);
                }
            }
        });
    }
}
